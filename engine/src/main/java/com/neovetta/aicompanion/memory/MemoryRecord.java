package com.neovetta.aicompanion.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * One memory. The unit the retrieval engine scores over.
 *
 * <h2>Why two time dimensions</h2>
 *
 * A companion that has known someone for months will be told things that stop being true, and will
 * also simply mishear things. Those are different events and collapsing them destroys the audit
 * trail:
 *
 * <ul>
 *   <li><b>The world changed.</b> "My favourite biome is desert" was true, and six months later it
 *       is mesa. The old record is still a correct description of the past. Close its
 *       <em>valid time</em> with {@link #supersededAt} and write a successor.</li>
 *   <li><b>We were wrong.</b> The companion misheard, and the fact was never true at all. Close its
 *       <em>transaction time</em> with {@link #retractedAt}. Nothing about the world changed; our
 *       belief did.</li>
 * </ul>
 *
 * Valid time is {@code [validFrom, validTo)} — when the fact held in the world. Transaction time is
 * {@code [recordedAt, invalidatedAt)} — when this system believed it. A plain "recency wins" model
 * has one timeline and cannot tell the two apart.
 *
 * <p>Nothing is ever deleted. Superseding and retracting both leave the original in place, which is
 * what makes "what did you think you knew last March?" answerable. Hard deletion exists only for
 * compliance (GDPR / COPPA / user request), never for housekeeping.
 *
 * <h2>Three clocks, deliberately separate</h2>
 *
 * <ul>
 *   <li>{@link #validFrom} — when the fact began holding in the world.</li>
 *   <li>{@link #recordedAt} — when this system first believed it.</li>
 *   <li>{@link #lastSeenAt} — when it was most recently <em>restated in conversation</em>.</li>
 * </ul>
 *
 * A fourth, {@code lastAccessedAt} — when the memory was last <em>retrieved</em> — is Park's
 * recency mechanism and is deliberately absent. It belongs to the read path and means nothing
 * until something is querying. It must never be merged into {@link #lastSeenAt}: being recalled
 * is not evidence that a fact still holds, and a memory that refreshes itself every time it is
 * read would never decay, for reasons that are close to impossible to see from the outside.
 *
 * <p>Decay should be measured from {@link #lastSeenAt}, not {@link #validFrom}. Subjects in this
 * corpus recur over spans of up to 293 days; anchored to first mention, a topic revisited monthly
 * reads as a year stale, which biases any decay estimate toward faster forgetting.
 *
 * <h2>Ordering</h2>
 *
 * {@link #serial} is the sole authority for which of two conflicting memories is newer — a
 * monotonic counter compared in code. Timestamps are for reasoning about time, not for resolving
 * contradictions; they can tie, arrive out of order, or come from a source with second granularity.
 * See {@link ContradictionResolver}.
 *
 * <h2>Wire format</h2>
 *
 * Serialized by {@link MemoryCodec} as one JSON object per line. Instants are epoch
 * <b>milliseconds</b>, not ISO-8601: this format is read by both Java and JS, and an integer
 * compares exactly in both with no parsing, formatting, or timezone in the way. Embedding vectors
 * are deliberately absent — they live in packed binary sidecars addressed by {@link #vectorRow},
 * because 768 floats per record as JSON text is unusable.
 */
public record MemoryRecord(
      String id,
      long serial,
      String text,
      String entity,
      String value,
      int occurrences,
      int topicDays,
      Instant lastSeenAt,
      MemoryKind kind,
      Instant validFrom,
      Instant validTo,
      Instant recordedAt,
      Instant invalidatedAt,
      float importance,
      ImportanceSource importanceSource,
      MemoryContext context,
      Partition partition,
      MemoryFrame frame,
      String companionId,
      Provenance provenance,
      MemoryScope scope,
      String worldId,
      Place place,
      int vectorRow) {

   /** {@link #vectorRow} value meaning "not embedded yet". */
   public static final int NO_VECTOR = -1;

   public MemoryRecord {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(entity, "entity");
      Objects.requireNonNull(lastSeenAt, "lastSeenAt");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(validFrom, "validFrom");
      Objects.requireNonNull(recordedAt, "recordedAt");
      Objects.requireNonNull(importanceSource, "importanceSource");
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(partition, "partition");
      Objects.requireNonNull(frame, "frame");
      Objects.requireNonNull(provenance, "provenance");
      // companionId is deliberately nullable — see the accessor doc.

      // A fiction has to belong to someone. An IN_WORLD claim with no companion is unretrievable
      // in principle -- there is no context in which it is true -- and storing it agnostic would
      // hand every companion a fact from a story none of them were in.
      if (frame == MemoryFrame.IN_WORLD && companionId == null) {
         throw new IllegalArgumentException("IN_WORLD memory needs a companionId: " + id);
      }

      // Scope has no safe default, so it is required rather than inferred. PERSON would make a
      // mis-classified world fact visible in every save; WORLD needs an id the caller would not
      // have either. A record that does not know which world it belongs to is a bug, and the
      // resulting leak is invisible -- a wrong-world memory reads exactly like a right one.
      Objects.requireNonNull(scope, "scope");
      if (scope == MemoryScope.WORLD && worldId == null) {
         throw new IllegalArgumentException("WORLD memory needs a worldId: " + id);
      }
      if (scope == MemoryScope.PERSON && worldId != null) {
         // Not harmless: it means something was classified as true-everywhere while still carrying
         // the world it came from, which is the signature of a mis-classified world fact.
         throw new IllegalArgumentException(
               "PERSON memory must not carry a worldId (" + worldId + "): " + id);
      }
      if (place != null && scope != MemoryScope.WORLD) {
         throw new IllegalArgumentException(
               "a memory with a place must be WORLD-scoped: " + id);
      }
      if (occurrences < 1) {
         throw new IllegalArgumentException("occurrences must be >= 1, got " + occurrences);
      }
      // Days this exact claim was stated are a subset of days its subject came up, so topicDays
      // can never be smaller. The invariant matters because topicDays is denormalised entity
      // state living on a per-claim record: when a new value supersedes an old one it has to be
      // carried across, and forgetting silently resets the count to 1 -- which reads as a
      // subject nobody has ever raised.
      if (topicDays < occurrences) {
         throw new IllegalArgumentException(
               "topicDays " + topicDays + " < occurrences " + occurrences + ": " + id);
      }
      if (lastSeenAt.isBefore(validFrom)) {
         throw new IllegalArgumentException(
               "lastSeenAt " + lastSeenAt + " precedes validFrom " + validFrom);
      }
      if (importance < 0.0f || importance > 1.0f) {
         throw new IllegalArgumentException("importance must be in [0,1], got " + importance);
      }
      if (validTo != null && validTo.isBefore(validFrom)) {
         throw new IllegalArgumentException("validTo " + validTo + " precedes validFrom " + validFrom);
      }
      if (invalidatedAt != null && invalidatedAt.isBefore(recordedAt)) {
         throw new IllegalArgumentException(
               "invalidatedAt " + invalidatedAt + " precedes recordedAt " + recordedAt);
      }
   }

   /** True while this memory is still believed — it has not been retracted as mistaken. */
   public boolean isBelieved() {
      return invalidatedAt == null;
   }

   /** True when the memory is both still believed and still describes the present world. */
   public boolean isCurrent() {
      return invalidatedAt == null && validTo == null;
   }

   /** Whether the fact held in the world at {@code t}, ignoring whether we believed it. */
   public boolean wasValidAt(Instant t) {
      return !t.isBefore(validFrom) && (validTo == null || t.isBefore(validTo));
   }

   /** Whether this system believed the fact at {@code t}, ignoring whether it was actually true. */
   public boolean wasBelievedAt(Instant t) {
      return !t.isBefore(recordedAt) && (invalidatedAt == null || t.isBefore(invalidatedAt));
   }

   /**
    * The world changed: this was true until {@code when}, and a successor record takes over from
    * there. The memory stays believed — it remains an accurate description of the past.
    *
    * @throws IllegalStateException if valid time is already closed
    */
   public MemoryRecord supersededAt(Instant when) {
      if (validTo != null) {
         throw new IllegalStateException("already superseded at " + validTo + ": " + id);
      }
      return new MemoryRecord(id, serial, text, entity, value, occurrences, topicDays, lastSeenAt, kind,
            validFrom, when, recordedAt, invalidatedAt, importance, importanceSource, context, partition, frame, companionId,
            provenance, scope, worldId, place, vectorRow);
   }

   /**
    * We were wrong: this was never true and is withdrawn from belief as of {@code when}. Valid time
    * is untouched, because the claim never held — there is no interval to close.
    *
    * @throws IllegalStateException if already retracted
    */
   public MemoryRecord retractedAt(Instant when) {
      if (invalidatedAt != null) {
         throw new IllegalStateException("already retracted at " + invalidatedAt + ": " + id);
      }
      return new MemoryRecord(id, serial, text, entity, value, occurrences, topicDays, lastSeenAt, kind,
            validFrom, validTo, recordedAt, when, importance, importanceSource, context, partition, frame, companionId, provenance,
            scope, worldId, place, vectorRow);
   }

   /**
    * The same fact, asserted again. Not a contradiction — a confirmation.
    *
    * <p>The third thing that can happen to a memory, and the one the bitemporal pair does not
    * cover. Superseding says the world changed; retracting says we were wrong; this says we were
    * told the same thing once more, which is evidence and ought to count as such.
    *
    * <p>It counts for a measured reason, though a more modest one than first reported. Among 843
    * owner-judged facts about the player, outright falsehood falls from 11% where the subject came
    * up on ten days or fewer to 3–6% above that. (An initial 17%→4%, z=4.6 was confounded: the
    * low-recurrence group was only 31% {@code subject="user"} and mostly held misattributed facts
    * about other people, so it measured subject normalisation rather than repetition. See
    * docs/extraction-pipeline.md.)
    *
    * <p>The mechanism stands regardless of effect size: until now resolution destroyed repetition
    * outright. {@code max(serial)} kept the newest record for an entity and discarded every
    * earlier statement of the same thing as though it had been overruled.
    *
    * <p><b>Occurrences count distinct days, not assertions.</b> Median span between repeats of an
    * identical fact in the corpus is one day — that is the extractor pulling the same statement
    * out of adjacent turns, not a person returning to a subject. Same-day repeats still move
    * {@link #lastSeenAt}, because the fact was genuinely restated; they just do not inflate the
    * count that decay and confidence read.
    *
    * <p>Distinct from retrieval reinforcement. Recalling a memory is not evidence about the world
    * and must not touch this — see the note on {@link #lastSeenAt()}.
    *
    * @param when when the fact was restated
    * @throws IllegalStateException if the memory is retracted or its valid time is closed
    */
   public MemoryRecord reinforcedAt(Instant when) {
      if (invalidatedAt != null) {
         throw new IllegalStateException("cannot reinforce a retracted memory: " + id);
      }
      if (validTo != null) {
         throw new IllegalStateException("cannot reinforce a superseded memory: " + id);
      }
      boolean newDay = when.getEpochSecond() / 86_400 != lastSeenAt.getEpochSecond() / 86_400;
      Instant seen = when.isAfter(lastSeenAt) ? when : lastSeenAt;
      return new MemoryRecord(id, serial, text, entity, value,
            newDay ? occurrences + 1 : occurrences, newDay ? topicDays + 1 : topicDays, seen, kind, validFrom, validTo, recordedAt,
            invalidatedAt, importance, importanceSource, context, partition, frame, companionId,
            provenance, scope, worldId, place, vectorRow);
   }

   /** Whether {@code other} confirms this memory rather than contradicting it. */
   public boolean confirms(MemoryRecord other) {
      return entity.equals(other.entity)
            && value != null && value.equalsIgnoreCase(other.value);
   }

   /** Copy carrying a row index into the packed vector sidecars. */
   /**
    * The same memory, now knowing where it happened.
    *
    * <p>For the case where a fact is already held without a location and is restated with one — a
    * confirmation that carries new information. Discarding the location there loses the only part
    * of the restatement that was worth anything.
    *
    * @throws IllegalArgumentException if this memory is not {@link MemoryScope#WORLD}-scoped
    */
   public MemoryRecord withPlace(Place newPlace) {
      return new MemoryRecord(id, serial, text, entity, value, occurrences, topicDays, lastSeenAt,
            kind, validFrom, validTo, recordedAt, invalidatedAt, importance, importanceSource,
            context, partition, frame, companionId, provenance, scope, worldId, newPlace,
            vectorRow);
   }

   public MemoryRecord withVectorRow(int row) {
      return new MemoryRecord(id, serial, text, entity, value, occurrences, topicDays, lastSeenAt, kind,
            validFrom, validTo, recordedAt, invalidatedAt, importance, importanceSource, context,
            partition, frame, companionId, provenance, scope, worldId, place, row);
   }

   /**
    * Whether this memory belongs to one companion's established fiction.
    *
    * <p>A player may run several companions, and each has its own character. "Prefers the forest"
    * has to be attributable to one of them or they merge into a single incoherent personality --
    * which breaks the product's central promise more visibly than forgetting a fact would.
    *
    * <p>Null means the memory is companion-agnostic: facts about the player belong to the player,
    * not to whichever character happened to be told. That asymmetry is deliberate. Every companion
    * should know the player's favourite biome; only Lila should remember what Lila said.
    *
    * <p>Null is therefore the widest possible audience, which makes it the wrong home for anything
    * uncertain. A claim about the player that came out of a story is scoped to the companion whose
    * story it was and marked {@link MemoryFrame#IN_WORLD}; owner review found roughly half of those
    * to be false as biography while the same claims were sound as fiction.
    */
   public boolean isCompanionScoped() {
      return companionId != null;
   }

   public boolean hasVector() {
      return vectorRow != NO_VECTOR;
   }

   public static Builder builder() {
      return new Builder();
   }

   /**
    * Fluent construction.
    *
    * <p>Twenty-one positional components is enough that a transposed pair of same-typed arguments
    * would compile and be wrong — {@code validFrom}/{@code recordedAt} and
    * {@code validTo}/{@code invalidatedAt} are the dangerous pairs, and mixing those up silently
    * inverts the bitemporal semantics this record exists to keep straight.
    *
    * <p>Only the genuinely optional components default. Everything else is left null so the
    * canonical constructor rejects it at {@link #build()} rather than storing a plausible blank.
    */
   public static final class Builder {
      /** Deliberately no default -- see the constructor invariant. */
      private MemoryScope scope;
      private String worldId;
      private Place place;
      private String id;
      private long serial;
      private String text;
      private String entity;
      private String value;
      private int occurrences = 1;
      private int topicDays;
      private Instant lastSeenAt;
      private MemoryKind kind;
      private Instant validFrom;
      private Instant validTo;
      private Instant recordedAt;
      private Instant invalidatedAt;
      private float importance;
      private ImportanceSource importanceSource = ImportanceSource.EXTRACTION;
      private MemoryContext context;
      private Partition partition;
      private MemoryFrame frame = MemoryFrame.REAL;
      private String companionId;
      private Provenance provenance;
      private int vectorRow = NO_VECTOR;

      public Builder id(String v) {
         this.id = v;
         return this;
      }

      public Builder serial(long v) {
         this.serial = v;
         return this;
      }

      public Builder text(String v) {
         this.text = v;
         return this;
      }

      public Builder entity(String v) {
         this.entity = v;
         return this;
      }

      public Builder value(String v) {
         this.value = v;
         return this;
      }

      public Builder occurrences(int v) {
         this.occurrences = v;
         return this;
      }

      public Builder topicDays(int v) {
         this.topicDays = v;
         return this;
      }

      public Builder lastSeenAt(Instant v) {
         this.lastSeenAt = v;
         return this;
      }

      public Builder kind(MemoryKind v) {
         this.kind = v;
         return this;
      }

      public Builder validFrom(Instant v) {
         this.validFrom = v;
         return this;
      }

      public Builder validTo(Instant v) {
         this.validTo = v;
         return this;
      }

      public Builder recordedAt(Instant v) {
         this.recordedAt = v;
         return this;
      }

      public Builder invalidatedAt(Instant v) {
         this.invalidatedAt = v;
         return this;
      }

      public Builder importance(float v) {
         this.importance = v;
         return this;
      }

      public Builder importanceSource(ImportanceSource v) {
         this.importanceSource = v;
         return this;
      }

      public Builder context(MemoryContext v) {
         this.context = v;
         return this;
      }

      public Builder partition(Partition v) {
         this.partition = v;
         return this;
      }

      public Builder frame(MemoryFrame v) {
         this.frame = v;
         return this;
      }

      public Builder companionId(String v) {
         this.companionId = v;
         return this;
      }

      public Builder provenance(Provenance v) {
         this.provenance = v;
         return this;
      }

      public Builder vectorRow(int v) {
         this.vectorRow = v;
         return this;
      }

      /** Required. There is no default; see {@link MemoryScope}. */
      public Builder scope(MemoryScope v) {
         this.scope = v;
         return this;
      }

      /** Sets {@link MemoryScope#WORLD} and the world it belongs to, in one call. */
      public Builder inWorld(String v) {
         this.scope = MemoryScope.WORLD;
         this.worldId = v;
         return this;
      }

      /** Marks this as true of the player everywhere. */
      public Builder aboutPerson() {
         this.scope = MemoryScope.PERSON;
         this.worldId = null;
         return this;
      }

      public Builder worldId(String v) {
         this.worldId = v;
         return this;
      }

      public Builder place(Place v) {
         this.place = v;
         return this;
      }

      public MemoryRecord build() {
         // A memory has been seen once, when it began: restating that at every call site is
         // redundant and easy to forget. The canonical constructor still rejects null, so a
         // record built any other way cannot skip it.
         if (lastSeenAt == null) {
            lastSeenAt = validFrom;
         }
         if (topicDays < occurrences) {
            topicDays = occurrences;   // a claim's own sightings are always topic sightings
         }
         return new MemoryRecord(id, serial, text, entity, value, occurrences,
               topicDays, lastSeenAt, kind, validFrom, validTo, recordedAt, invalidatedAt, importance,
               importanceSource, context, partition, frame, companionId, provenance,
               scope, worldId, place, vectorRow);
      }
   }
}
