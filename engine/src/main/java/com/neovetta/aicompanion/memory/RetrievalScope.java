package com.neovetta.aicompanion.memory;

import java.util.Objects;
import java.util.Set;

/**
 * Who is asking, and therefore which memories exist at all.
 *
 * <p>This is a filter, not a scoring term, and the distinction is load-bearing. A memory outside the
 * scope is not merely unlikely — it must be unreachable. Scoring an {@link MemoryFrame#IN_WORLD}
 * memory low would still let it surface on a query where nothing else matched, and the failure it
 * produces is the worst one this system can make: a companion telling the player something that
 * happened in a story as though it happened to them.
 *
 * <p>Owner review found roughly half of the story-derived claims about the player to be false as
 * biography while sound as fiction. There is no weight that makes "half of these are lies about your
 * life" acceptable, so frame is enforced before anything is ranked.
 *
 * <h2>The rules</h2>
 *
 * <ul>
 *   <li><b>Retracted memories never appear.</b> A record withdrawn as mistaken is not a weak
 *       candidate, it is not a candidate. (Superseded is different — see below.)</li>
 *   <li><b>Superseded memories are excluded by default.</b> "Was true until March" is a fact about
 *       the world and the caller may be asking about March, so {@link #includeSuperseded} exists;
 *       but a companion answering "what is your favourite biome" wants the current answer.</li>
 *   <li><b>Companion-scoped memories need their companion.</b> A null {@code companionId} on a
 *       record means companion-agnostic — facts about the player belong to the player. A non-null
 *       one must match whoever is speaking, or several companions merge into one incoherent
 *       personality.</li>
 *   <li><b>Partition is an eval affordance.</b> {@link Partition#DISTRACTOR} records are another
 *       person's conversations, mixed in to prove retrieval does not pull them. Leaving
 *       {@code partition} null keeps them in the pool, which is what makes a recall number
 *       meaningful; setting it is how the mod would run.</li>
 *   <li><b>Kind is a measurement control.</b> The corpus holds 7,375 raw observations against
 *       1,749 extracted facts, and observations all carry a flat importance of 0.1 — so a scorer
 *       run over the mixture can look like it is rewarding important memories when it is only
 *       separating facts from turns. Restricting to one kind is how that gets told apart.</li>
 * </ul>
 *
 * @param companionId who is speaking; null admits only companion-agnostic memories
 * @param worldId which save the asker is in; null admits only {@link MemoryScope#PERSON} memories
 * @param partition restrict to one partition, or null for all
 * @param includeSuperseded whether records whose valid time has closed are still eligible
 * @param kinds which kinds are eligible; null or empty for all
 */
public record RetrievalScope(String companionId, String worldId, Partition partition,
      boolean includeSuperseded, Set<MemoryKind> kinds) {

   public RetrievalScope {
      kinds = kinds == null || kinds.isEmpty() ? null : Set.copyOf(kinds);
   }

   public RetrievalScope(String companionId, Partition partition, boolean includeSuperseded,
         Set<MemoryKind> kinds) {
      this(companionId, null, partition, includeSuperseded, kinds);
   }

   public RetrievalScope(String companionId, Partition partition, boolean includeSuperseded) {
      this(companionId, null, partition, includeSuperseded, null);
   }

   /** Everything a companion-agnostic caller may see: no companion, both partitions, current only. */
   public static RetrievalScope everything() {
      return new RetrievalScope(null, null, null, false, null);
   }

   /** What one companion may see, across partitions, current only. */
   public static RetrievalScope forCompanion(String companionId) {
      return new RetrievalScope(Objects.requireNonNull(companionId), null, null, false, null);
   }

   /**
    * What one companion may see while playing one save — the production case.
    *
    * <p>Both arguments are required. A companion asking from inside a world and not saying which
    * world is the shape of every cross-save leak this scoping exists to prevent.
    */
   public static RetrievalScope forCompanionInWorld(String companionId, String worldId) {
      return new RetrievalScope(Objects.requireNonNull(companionId, "companionId"),
            Objects.requireNonNull(worldId, "worldId"), null, false, null);
   }

   /** The same scope, bound to a save. */
   public RetrievalScope inWorld(String id) {
      return new RetrievalScope(companionId, id, partition, includeSuperseded, kinds);
   }

   public RetrievalScope withPartition(Partition p) {
      return new RetrievalScope(companionId, worldId, p, includeSuperseded, kinds);
   }

   public RetrievalScope withSuperseded(boolean include) {
      return new RetrievalScope(companionId, worldId, partition, include, kinds);
   }

   public RetrievalScope withKinds(Set<MemoryKind> k) {
      return new RetrievalScope(companionId, worldId, partition, includeSuperseded, k);
   }

   /** Whether this memory is visible to the asker at all. */
   public boolean admits(MemoryRecord r) {
      if (!r.isBelieved()) {
         return false;
      }
      if (!includeSuperseded && r.validTo() != null) {
         return false;
      }
      if (partition != null && r.partition() != partition) {
         return false;
      }
      if (kinds != null && !kinds.contains(r.kind())) {
         return false;
      }
      // A world-scoped memory is reachable only from the save it belongs to. A HARD FILTER, not a
      // weight, for the same reason IN_WORLD is one: a memory from another save reads exactly like
      // a valid one, so ranking it low leaves a failure with no symptom. A null asker worldId --
      // the browser, where there is no world -- admits person-scoped memories only, mirroring how
      // a null companionId admits only companion-agnostic ones.
      if (r.scope() == MemoryScope.WORLD && !r.worldId().equals(worldId)) {
         return false;
      }
      // Covers IN_WORLD implicitly: the record constructor guarantees such memories carry a
      // companionId, so an in-world claim can never satisfy this with a null asker.
      return r.companionId() == null || r.companionId().equals(companionId);
   }
}
