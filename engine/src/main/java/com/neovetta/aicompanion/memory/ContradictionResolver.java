package com.neovetta.aicompanion.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Picks the winner among memories that contradict each other.
 *
 * <p>The rule is {@code max(serial)} among believed records for an entity, compared in code. That
 * is the whole algorithm, and it is deliberately not a model call.
 *
 * <p>Asking an LLM to reason about which of several facts is freshest fails in two specific ways.
 * It overrides explicit "newer wins" instructions with what it learned in training, and it loses
 * track of the largest identifier as the candidate list grows. Both get worse as history gets
 * longer, which is exactly the direction this product moves in — a companion is supposed to
 * accumulate months of it. Deterministic comparison does not degrade, needs no extra call, and
 * costs the player nothing, which under a bring-your-own-key model is real money.
 *
 * <p>Serial is authoritative rather than timestamps because timestamps tie, arrive out of order,
 * and come from sources with coarse granularity. Two facts recorded in the same second still have
 * an unambiguous order.
 *
 * <p>Retracted memories are excluded: a record withdrawn as mistaken should never win a contest
 * regardless of how recent it is. Superseded ones remain eligible, because "was true until March"
 * is still a fact about the world and the caller may be asking about March.
 */
public final class ContradictionResolver {

   private ContradictionResolver() {
   }

   /**
    * The prevailing memory for one entity, with repeat statements of it folded in.
    *
    * <p>Highest serial still wins — that rule is unchanged and remains the sole authority on which
    * of two <em>conflicting</em> claims is current. What changes is that not every repeat is a
    * conflict. Records sharing an entity <em>and</em> a value are the same claim asserted more than
    * once, and discarding the earlier ones threw away evidence: among 843 owner-judged facts about
    * the player, falsehood runs 11% where the subject came up on ten days or fewer against 3–6%
    * above that.
    *
    * <p>So the winner is the highest-serial record carrying the accumulated weight of everything
    * that agreed with it, while records with a <em>different</em> value are what it outranks.
    *
    * <p>Two counts come out of this. {@code occurrences} is how often this exact claim was
    * restated; {@code topicDays} is how often its subject came up at all, whatever was said
    * about it. They measure different things, but ⚠️ {@code topicDays} is only interpretable under
    * a fine-grained entity scheme. Keyed on subject+predicate it is not topic recurrence at all:
    * 1,462 facts about the player span 30 keys, so {@code user/has_trait} with topicDays=58 means
    * "some trait was mentioned on 58 days". Under that key it tracks predicate popularity, and
    * trivia *rises* with it (15% → 34%) rather than falling. Nothing should score on it until the
    * corpus is rebuilt with embedding-clustered keys.
    *
    * <p>Counting is by distinct day, from each record's {@link MemoryRecord#lastSeenAt()}. That is
    * exact for records straight out of extraction, which carry one sighting each. A record that has
    * already been folded knows its count but not which days produced it, so its own
    * {@code occurrences} is used as a floor rather than re-derived — the fold can undercount a
    * previously folded input, never overcount one.
    *
    * @return the prevailing record, or empty if every candidate was retracted
    */
   public static Optional<MemoryRecord> resolve(Collection<MemoryRecord> candidates) {
      MemoryRecord best = null;
      for (MemoryRecord r : candidates) {
         if (!r.isBelieved()) {
            continue;
         }
         if (best == null || r.serial() > best.serial()) {
            best = r;
         }
      }
      if (best == null) {
         return Optional.empty();
      }

      Set<Long> days = new HashSet<>();       // days this exact claim was restated
      Set<Long> topic = new HashSet<>();      // days the subject came up at all, any value
      Instant seen = best.lastSeenAt();
      int floor = 0;
      int topicFloor = 0;
      for (MemoryRecord r : candidates) {
         if (!r.isBelieved()) {
            continue;
         }
         topic.add(r.lastSeenAt().getEpochSecond() / 86_400);
         topicFloor = Math.max(topicFloor, r.topicDays());
         if (!best.confirms(r)) {
            continue;
         }
         days.add(r.lastSeenAt().getEpochSecond() / 86_400);
         floor = Math.max(floor, r.occurrences());
         if (r.lastSeenAt().isAfter(seen)) {
            seen = r.lastSeenAt();
         }
      }
      int total = Math.max(days.size(), floor);
      // Topic recurrence outlives any single claim: superseding "desert" with "mesa" does not
      // undo the thirty days the subject was discussed. Carried across, never recomputed down.
      int topicTotal = Math.max(Math.max(topic.size(), topicFloor), total);
      if (total <= best.occurrences() && topicTotal <= best.topicDays()
            && !seen.isAfter(best.lastSeenAt())) {
         return Optional.of(best);
      }
      return Optional.of(new MemoryRecord(best.id(), best.serial(), best.text(), best.entity(),
            best.value(), Math.max(total, best.occurrences()),
            Math.max(topicTotal, best.topicDays()), seen, best.kind(), best.validFrom(),
            best.validTo(), best.recordedAt(), best.invalidatedAt(), best.importance(),
            best.importanceSource(), best.context(), best.partition(), best.frame(),
            best.companionId(), best.provenance(),
            // Folding occurrences never changes which world a claim is about.
            best.scope(), best.worldId(), best.place(), best.vectorRow()));
   }

   /**
    * Resolves every entity present in {@code records}.
    *
    * <p>Iteration order of the result follows first appearance, so output is reproducible across
    * runs — the eval harness diffs these.
    */
   public static Map<String, MemoryRecord> resolveAll(Collection<MemoryRecord> records) {
      Map<String, List<MemoryRecord>> byEntity = new LinkedHashMap<>();
      for (MemoryRecord r : records) {
         byEntity.computeIfAbsent(r.entity(), k -> new ArrayList<>()).add(r);
      }
      Map<String, MemoryRecord> winners = new LinkedHashMap<>();
      byEntity.forEach((entity, group) -> resolve(group).ifPresent(w -> winners.put(entity, w)));
      return winners;
   }
}
