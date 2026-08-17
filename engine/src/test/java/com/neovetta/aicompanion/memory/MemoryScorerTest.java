package com.neovetta.aicompanion.memory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MemoryScorerTest {

   private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");
   private static final Instant MARCH = Instant.parse("2026-03-01T00:00:00Z");

   private static MemoryRecord.Builder base() {
      return MemoryRecord.builder()
            .id("mem-1")
            .serial(1L)
            .text("favourite biome is desert")
            .entity("player:favourite_biome")
            .value("desert")
            .kind(MemoryKind.FACT)
            .validFrom(MARCH)
            .recordedAt(MARCH)
            .importance(0.5f)
            .importanceSource(ImportanceSource.EXTRACTION)
            .aboutPerson()
            .context(MemoryContext.CHAT)
            .partition(Partition.OWNER)
            .frame(MemoryFrame.REAL)
            .provenance(new Provenance("openwebui:grendel", "chat-a", "msg-a", MARCH.toEpochMilli()));
   }

   private static MemoryRecord mem(long serial, Instant lastSeen, float importance, int topicDays) {
      return base().id("m" + serial).serial(serial).lastSeenAt(lastSeen)
            .importance(importance).topicDays(topicDays).build();
   }

   private static List<String> ids(List<ScoredMemory> ranked) {
      return ranked.stream().map(ScoredMemory::id).toList();
   }

   @Nested
   @DisplayName("normalisation")
   class Normalisation {

      @Test
      @DisplayName("scales to [0,1] across the candidate set")
      void scales() {
         assertArrayEquals(new double[] {0.0, 0.5, 1.0},
               MemoryScorer.minMax(new double[] {2.0, 3.0, 4.0}), 1e-12);
      }

      @Test
      @DisplayName("a term with no spread contributes nothing rather than everything")
      void flatTermIsZero() {
         // 7,375 of 9,124 records in the corpus sit at importance 0.1. Normalising those to 1.0
         // would add a constant to every score and read, in a breakdown, as unanimous support.
         assertArrayEquals(new double[] {0.0, 0.0, 0.0},
               MemoryScorer.minMax(new double[] {0.1, 0.1, 0.1}), 1e-12);
      }

      @Test
      @DisplayName("a single candidate normalises to zero, not one")
      void singletonIsZero() {
         assertArrayEquals(new double[] {0.0}, MemoryScorer.minMax(new double[] {0.42}), 1e-12);
      }
   }

   @Nested
   @DisplayName("decay")
   class Decay {

      @Test
      @DisplayName("one half-life leaves half the weight")
      void halfLife() {
         MemoryScorer s = new MemoryScorer(MemoryScorer.Weights.PARK, 100.0);
         assertEquals(0.5, s.decay(NOW.minusSeconds(100 * 3600), NOW), 1e-12);
         assertEquals(0.25, s.decay(NOW.minusSeconds(200 * 3600), NOW), 1e-12);
      }

      @Test
      @DisplayName("Park's 0.995^hours is reproduced by the default half-life")
      void parkEquivalence() {
         MemoryScorer s = MemoryScorer.park();
         for (int hours : new int[] {1, 24, 138, 336}) {
            assertEquals(StrictMath.pow(0.995, hours), s.decay(NOW.minusSeconds(hours * 3600L), NOW),
                  1e-9, hours + "h should match Park's geometric decay");
         }
      }

      @Test
      @DisplayName("a memory seen in the future is not rewarded for it")
      void futureClamped() {
         assertEquals(1.0, MemoryScorer.park().decay(NOW.plusSeconds(86_400), NOW), 0.0);
      }

      @Test
      @DisplayName("recency reads lastSeenAt, never validFrom")
      void anchoredToLastSeen() {
         // Same fact, first stated in March. One was restated yesterday. Anchored to validFrom
         // they are indistinguishable, which is the bias this project measured and rejected:
         // subjects here recur over spans up to 293 days.
         MemoryRecord stale = mem(1, MARCH, 0.5f, 1);
         MemoryRecord fresh = mem(2, NOW.minusSeconds(86_400), 0.5f, 1);
         assertEquals(MARCH, stale.validFrom());
         assertEquals(MARCH, fresh.validFrom());

         MemoryScorer recencyOnly = new MemoryScorer(new MemoryScorer.Weights(0, 1, 0, 0),
               MemoryScorer.PARK_HALF_LIFE_HOURS);
         List<ScoredMemory> ranked =
               recencyOnly.rank(List.of(stale, fresh), new double[] {0.9, 0.1}, NOW, 2);
         assertEquals(List.of("m2", "m1"), ids(ranked),
               "the restated memory wins despite the worse cosine");
      }
   }

   @Nested
   @DisplayName("terms")
   class Terms {

      @Test
      @DisplayName("Park's baseline is the unweighted sum of three normalised terms")
      void parkSum() {
         MemoryRecord a = mem(1, NOW, 1.0f, 1);
         MemoryRecord b = mem(2, MARCH, 0.0f, 1);
         List<ScoredMemory> ranked =
               MemoryScorer.park().rank(List.of(a, b), new double[] {1.0, 0.0}, NOW, 2);

         ScoredMemory top = ranked.get(0);
         assertEquals("m1", top.id());
         assertEquals(3.0, top.score(), 1e-9, "best on all three terms");
         assertEquals(0.0, ranked.get(1).score(), 1e-9, "worst on all three");
      }

      @Test
      @DisplayName("salience is off in the baseline and orders by topicDays when switched on")
      void salienceIsOptIn() {
         MemoryRecord once = mem(1, NOW, 0.5f, 1);
         MemoryRecord recurring = mem(2, NOW, 0.5f, 40);
         double[] cosine = {0.5, 0.5};

         List<ScoredMemory> park = MemoryScorer.park().rank(List.of(once, recurring), cosine, NOW, 2);
         assertEquals(park.get(0).score(), park.get(1).score(), 1e-12,
               "identical but for topicDays, which Park cannot see");
         // The term is still computed and reported at weight zero. That is what lets a recorded
         // eval run be re-weighted without re-scoring the corpus.
         assertEquals(1.0, park.get(0).salience(), 1e-12);
         assertEquals(0.0, park.get(1).salience(), 1e-12);

         MemoryScorer withSalience = new MemoryScorer(
               MemoryScorer.Weights.PARK.withSalience(1.0), MemoryScorer.PARK_HALF_LIFE_HOURS);
         assertEquals(List.of("m2", "m1"),
               ids(withSalience.rank(List.of(once, recurring), cosine, NOW, 2)));
      }

      @Test
      @DisplayName("importance can be weighted out, which is the point of it being a weight")
      void importanceCanBeDisabled() {
         // The term is suspect: 0.85 vs 0.80 separated nothing at turn level. Being able to zero
         // it and re-rank is how "importance earns its place" gets answered either way.
         //
         // Three candidates, not two: with min-max any single term separates two candidates into
         // exactly 0 and 1, so a two-way comparison ties on every balanced pair and shows nothing.
         List<MemoryRecord> candidates = List.of(
               mem(1, MARCH, 1.0f, 1),    // least relevant, most important
               mem(2, MARCH, 0.0f, 1),    // barely more relevant, worthless
               mem(3, MARCH, 0.2f, 1));   // most relevant
         double[] cosine = {0.30, 0.31, 0.35};

         assertEquals(List.of("m3", "m1", "m2"),
               ids(MemoryScorer.park().rank(candidates, cosine, NOW, 3)));

         MemoryScorer noImportance = new MemoryScorer(
               MemoryScorer.Weights.PARK.withImportance(0), MemoryScorer.PARK_HALF_LIFE_HOURS);
         assertEquals(List.of("m3", "m2", "m1"), ids(noImportance.rank(candidates, cosine, NOW, 3)),
               "dropping the term reorders the two the term was carrying");
      }

      @Test
      @DisplayName("the raw cosine survives normalisation, because it is the one absolute number")
      void cosineIsKept() {
         MemoryRecord a = mem(1, NOW, 0.5f, 1);
         MemoryRecord b = mem(2, NOW, 0.5f, 1);
         List<ScoredMemory> ranked =
               MemoryScorer.park().rank(List.of(a, b), new double[] {0.21, 0.19}, NOW, 2);
         assertEquals(0.21, ranked.get(0).cosine(), 1e-12);
         assertEquals(1.0, ranked.get(0).relevance(), 1e-12);
         assertTrue(ranked.get(0).cosine() < 0.3, "normalised 1.0 still means a weak match here");
      }
   }

   @Nested
   @DisplayName("ordering is total and reproducible")
   class Ordering {

      /** Anchors the normalisation so the two memories under test land mid-range, not at 0 and 1. */
      private List<MemoryRecord> spread() {
         return List.of(mem(1, NOW, 0.5f, 1), mem(2, NOW, 0.5f, 1),
               mem(3, NOW, 0.5f, 1), mem(4, NOW, 0.5f, 1));
      }

      private final MemoryScorer relevanceOnly = new MemoryScorer(
            MemoryScorer.Weights.RELEVANCE_ONLY, MemoryScorer.PARK_HALF_LIFE_HOURS);

      @Test
      @DisplayName("scores differing below the comparison precision fall back to serial")
      void subPrecisionTiesUseSerial() {
         // A last-bit difference between Java's exp() and a JS engine's must not be able to swap
         // two memories, or the mod and the browser recall different things from one corpus. m3
         // outscores m4 by 1e-9 — below the precision the comparison sees — so serial decides and
         // the newer memory wins. Comparing raw doubles would put m3 first.
         List<ScoredMemory> ranked = relevanceOnly.rank(spread(),
               new double[] {1.0, 0.0, 0.5 + 1e-9, 0.5}, NOW, 4);
         assertEquals(List.of("m1", "m4", "m3", "m2"), ids(ranked));
      }

      @Test
      @DisplayName("a difference above the precision floor still decides")
      void realDifferencesStillOrder() {
         List<ScoredMemory> ranked = relevanceOnly.rank(spread(),
               new double[] {1.0, 0.0, 0.51, 0.5}, NOW, 4);
         assertEquals(List.of("m1", "m3", "m4", "m2"), ids(ranked),
               "a genuine gap outranks the newer memory");
      }

      @Test
      @DisplayName("k caps the result, and asking for more than exists is not an error")
      void kBounds() {
         List<MemoryRecord> two = List.of(mem(1, NOW, 0.5f, 1), mem(2, NOW, 0.5f, 1));
         double[] cosine = {0.4, 0.3};
         assertEquals(1, MemoryScorer.park().rank(two, cosine, NOW, 1).size());
         assertEquals(2, MemoryScorer.park().rank(two, cosine, NOW, 50).size());
         assertTrue(MemoryScorer.park().rank(two, cosine, NOW, 0).isEmpty());
      }

      @Test
      @DisplayName("a cosine array that does not line up with the candidates is rejected")
      void mismatchedInputsThrow() {
         List<MemoryRecord> two = List.of(mem(1, NOW, 0.5f, 1), mem(2, NOW, 0.5f, 1));
         assertThrows(IllegalArgumentException.class,
               () -> MemoryScorer.park().rank(two, new double[] {0.4}, NOW, 2));
      }
   }

   @Nested
   @DisplayName("scope decides what exists, before anything is scored")
   class Scope {

      private MemoryRecord inWorld(String companion) {
         return base().id("fic").serial(9L).frame(MemoryFrame.IN_WORLD).companionId(companion)
               .build();
      }

      @Test
      @DisplayName("a companion's fiction is invisible to everyone else, including no-one")
      void inWorldIsScoped() {
         MemoryRecord lena = inWorld("lena");
         assertTrue(RetrievalScope.forCompanion("lena").admits(lena));
         assertFalse(RetrievalScope.forCompanion("mia").admits(lena));
         assertFalse(RetrievalScope.everything().admits(lena),
               "an unscoped ask must never reach a story");
      }

      @Test
      @DisplayName("facts about the player are companion-agnostic and every companion sees them")
      void realFactsAreShared() {
         MemoryRecord real = base().build();
         assertTrue(RetrievalScope.everything().admits(real));
         assertTrue(RetrievalScope.forCompanion("lena").admits(real));
         assertTrue(RetrievalScope.forCompanion("mia").admits(real));
      }

      @Test
      @DisplayName("retracted is gone; superseded is merely past")
      void beliefAndValidity() {
         MemoryRecord retracted = base().build().retractedAt(NOW);
         MemoryRecord superseded = base().build().supersededAt(NOW);

         assertFalse(RetrievalScope.everything().admits(retracted));
         assertFalse(RetrievalScope.everything().withSuperseded(true).admits(retracted),
               "a mistake stays withdrawn however the caller asks");

         assertFalse(RetrievalScope.everything().admits(superseded));
         assertTrue(RetrievalScope.everything().withSuperseded(true).admits(superseded));
      }

      @Test
      @DisplayName("partition is unset by default so distractors stay in the pool")
      void partitionFilter() {
         MemoryRecord other = base().partition(Partition.DISTRACTOR).build();
         assertTrue(RetrievalScope.everything().admits(other),
               "an eval needs the noise or recall means nothing");
         assertFalse(RetrievalScope.everything().withPartition(Partition.OWNER).admits(other));
      }
   }
}
