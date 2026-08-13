package com.neovetta.aicompanion.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks candidate memories for one query. The read path's whole decision.
 *
 * <p>Park et al.'s baseline — {@code recency + importance + relevance}, each min-max normalised
 * across the candidate set, summed — with a fourth term this project's own measurements argue for.
 * The baseline is not superseded and is reproducible here exactly: {@link Weights#PARK} with
 * {@link #PARK_HALF_LIFE_HOURS}. Everything else is an experiment run against it.
 *
 * <h2>Four terms, three of them contested</h2>
 *
 * <ul>
 *   <li><b>relevance</b> — cosine to the query. The only term nobody doubts.</li>
 *   <li><b>recency</b> — exponential decay, anchored to {@link MemoryRecord#lastSeenAt()} and
 *       <em>not</em> {@code validFrom}. Subjects in this corpus recur over spans up to 293 days; a
 *       topic revisited monthly reads as a year stale if anchored to first mention, and the bias
 *       runs one way — toward forgetting things the player is still actively talking about.</li>
 *   <li><b>importance</b> — the extractor's own 0–1 rating. <b>Suspect.</b> It failed to separate
 *       durable from worthless at turn level (0.85 vs 0.80, no signal) and did no better at fact
 *       level. It is here as a weighted term specifically so it can be weighted to zero and the
 *       difference measured; "importance earns nothing" is a real finding and this is the
 *       instrument for it.</li>
 *   <li><b>salience</b> — {@link MemoryRecord#topicDays()}, how many distinct days the memory's
 *       subject came up. Has no equivalent in Park. It is included because it is the only signal
 *       measured here that attacks <em>triviality</em> rather than falsehood: under the
 *       {@code E_text_cluster} @0.80 entity scheme, trivia falls from 34% to 8% (z=+9.0) between
 *       subjects seen on one day and subjects seen on more, while keeps rise 62% → 89%. Defaulted
 *       off in {@link Weights#PARK} so the comparison stays honest.</li>
 * </ul>
 *
 * <h2>Why min-max across the candidate set</h2>
 *
 * The four terms have incomparable units — a cosine, a decayed probability, a model's opinion, and
 * a day count. Park normalises each to [0,1] across the memories under consideration and sums them,
 * which makes the weights mean something. The consequence to keep in mind is that every term is
 * relative to what else was retrieved: a memory can score 1.0 on recency by being the least old of
 * a stale set. That is why {@link #cosine} survives into {@link ScoredMemory} — it is the one number
 * with meaning outside the candidate set.
 *
 * <p>A term with no spread across the candidates (every value identical) normalises to all zeros
 * rather than all ones. Neither changes the ranking; zero makes it visible in the breakdown that
 * the term did no work. This is not rare — most of the corpus sits at importance 0.1.
 *
 * <h2>Decay is parameterised because its value is unknown</h2>
 *
 * Park's {@code 0.995^hours} is a half-life of about 138 hours, calibrated on a two-day simulation.
 * It is effectively zero past a fortnight, and this product's horizon is months to years. The
 * constant is therefore a constructor argument rather than a literal, so the measurement that fixes
 * it lands in a field instead of forcing a rewrite. Preliminary human labelling suggests decay over
 * this horizon is far slower than Park's — treat that as a direction, not a number, until the
 * staleness batch is graded.
 *
 * <h2>Determinism</h2>
 *
 * Ranking compares scores rounded to {@link #SCORE_PRECISION} decimal places, then falls back to
 * higher {@link MemoryRecord#serial()} and finally to id. This matters for a reason specific to
 * this project: the same ranking has to come out of a Java implementation in the mod and a JS one
 * in the browser. Comparing raw doubles would let a last-place-bit difference in
 * {@link StrictMath#exp} swap two memories and silently give the two clients different memories;
 * quantising the comparison makes that impossible, and the tie-break chain is total, so no ordering
 * is ever left to the sort's discretion.
 *
 * <p>{@link StrictMath#exp} rather than {@link Math#exp} for the same reason — it is fdlibm, which
 * is reproducible across JVMs and matches what JS engines derive their implementation from.
 */
public final class MemoryScorer {

   /** Decimal places scores are rounded to before they are compared. See the class note. */
   public static final int SCORE_PRECISION = 6;

   private static final double SCORE_SCALE = Math.pow(10, SCORE_PRECISION);
   private static final double LN2 = 0.6931471805599453;

   /**
    * Park's {@code 0.995^hours} expressed as a half-life — about 5.8 days.
    *
    * <p>Derived rather than written out, so it is exactly the constant Park used instead of a
    * transcription of it, and so the arithmetic that connects the two is visible.
    */
   public static final double PARK_HALF_LIFE_HOURS = LN2 / -StrictMath.log(0.995);

   /**
    * 91 days. The default, and inert while recency carries weight zero.
    *
    * <p>Not a measured decay rate — nothing here decays fast enough to measure one. Owner
    * labelling found zero stale facts in 64 spanning 30 to 270+ days, bounding staleness under
    * ~4.7%, while Park's constant predicts effectively total decay past a fortnight. 91 days is
    * instead the point where the *ranking* stops being damaged: below it the term underflows for
    * most candidates and min-max turns the remainder into a coin flip worth a full point of
    * score. See {@code docs/decay.md}.
    */
   public static final double DEFAULT_HALF_LIFE_HOURS = 91 * 24;

   /**
    * Term weights. Park uses 1 for each of his three.
    *
    * <p>Salience is Park's missing fourth term and defaults to 0 so {@link #PARK} is the literal
    * baseline rather than a variant of it.
    */
   public record Weights(double relevance, double recency, double importance, double salience) {

      /** The literature baseline: relevance + recency + importance, unweighted, no salience. */
      public static final Weights PARK = new Weights(1, 1, 1, 0);

      /** Relevance alone — what a plain vector search does. The floor any term must beat. */
      public static final Weights RELEVANCE_ONLY = new Weights(1, 0, 0, 0);

      /**
       * What this project measured, and therefore the default.
       *
       * <p>Identical to {@link #RELEVANCE_ONLY}, named separately because it is a conclusion
       * rather than a control. On 295 natural questions against an owner-labelled corpus, no
       * weighting of the other three terms beat plain cosine: Park's baseline lost seven points
       * of recall@10 (86.1% → 79.3%) and two-thirds of rank-1 accuracy (61% → 15%), correcting
       * the half-life to 91 days recovered part of it and still lost, and relevance-dominant
       * variants converged on this from below — at 10:1 the difference was one query in 295.
       *
       * <p>Trivia sat at 14.7–16.6% of retrieved judged facts against a 13.4% base rate under
       * every configuration, so the recall the other terms cost was not buying quality either.
       * See {@code docs/retrieval-scoring.md}.
       *
       * <p>The terms remain in the scorer at weight zero rather than being deleted. They cost
       * nothing unweighted, one flag re-enables any of them, and the case they might still win —
       * choosing among several genuinely relevant memories — is one a single-gold eval cannot
       * reward and nobody has yet measured.
       */
      public static final Weights MEASURED = new Weights(1, 0, 0, 0);

      public Weights {
         if (relevance < 0 || recency < 0 || importance < 0 || salience < 0) {
            throw new IllegalArgumentException("weights must be non-negative");
         }
         if (relevance + recency + importance + salience <= 0) {
            throw new IllegalArgumentException("at least one weight must be positive");
         }
      }

      public Weights withImportance(double w) {
         return new Weights(relevance, recency, w, salience);
      }

      public Weights withSalience(double w) {
         return new Weights(relevance, recency, importance, w);
      }
   }

   private final Weights weights;
   private final double halfLifeHours;

   public MemoryScorer(Weights weights, double halfLifeHours) {
      if (halfLifeHours <= 0) {
         throw new IllegalArgumentException("halfLifeHours must be positive, got " + halfLifeHours);
      }
      this.weights = weights;
      this.halfLifeHours = halfLifeHours;
   }

   /** Park's baseline, exactly: unweighted sum of three terms, 138-hour half-life. */
   public static MemoryScorer park() {
      return new MemoryScorer(Weights.PARK, PARK_HALF_LIFE_HOURS);
   }

   /**
    * The configuration this project's measurements support. Use this unless you are running an
    * experiment.
    *
    * <p>{@link Weights#MEASURED} at {@link #DEFAULT_HALF_LIFE_HOURS}. The half-life is inert at
    * weight zero and is set to a defensible value anyway, so that enabling recency for an
    * experiment does not silently reintroduce Park's constant along with it.
    */
   public static MemoryScorer measured() {
      return new MemoryScorer(Weights.MEASURED, DEFAULT_HALF_LIFE_HOURS);
   }

   public Weights weights() {
      return weights;
   }

   public double halfLifeHours() {
      return halfLifeHours;
   }

   /**
    * Ranks candidates and returns the best {@code k}.
    *
    * <p>Pure: no vectors, no I/O, no clock. The relevance array is supplied by the caller because
    * similarity is the vector store's job and this class has to stay portable to JS, testable
    * without an embedder, and honest about the fact that it never sees an embedding.
    *
    * @param candidates the memories under consideration, already scope-filtered
    * @param cosine similarity of each candidate to the query, parallel to {@code candidates}
    * @param now the query time; decay is measured back from here
    * @param k how many to return
    */
   public List<ScoredMemory> rank(List<MemoryRecord> candidates, double[] cosine, Instant now,
         int k) {
      if (candidates.size() != cosine.length) {
         throw new IllegalArgumentException(
               "cosine[] has " + cosine.length + " entries for " + candidates.size() + " candidates");
      }
      if (k < 0) {
         throw new IllegalArgumentException("k must be >= 0, got " + k);
      }
      int n = candidates.size();
      if (n == 0 || k == 0) {
         return List.of();
      }

      double[] rec = new double[n];
      double[] imp = new double[n];
      double[] sal = new double[n];
      for (int i = 0; i < n; i++) {
         MemoryRecord r = candidates.get(i);
         rec[i] = decay(r.lastSeenAt(), now);
         imp[i] = r.importance();
         // Compressed: the gap between a subject raised once and one raised three times is the
         // informative one; between 40 days and 60 it is noise. Linear topicDays would let a
         // handful of long-running subjects flatten the term for everything else.
         sal[i] = StrictMath.log1p(r.topicDays());
      }

      double[] nRel = minMax(cosine);
      double[] nRec = minMax(rec);
      double[] nImp = minMax(imp);
      double[] nSal = minMax(sal);

      List<ScoredMemory> scored = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
         double s = weights.relevance() * nRel[i]
               + weights.recency() * nRec[i]
               + weights.importance() * nImp[i]
               + weights.salience() * nSal[i];
         scored.add(new ScoredMemory(candidates.get(i), s, nRel[i], nRec[i], nImp[i], nSal[i],
               cosine[i]));
      }
      scored.sort(RANKING);
      return scored.size() <= k ? List.copyOf(scored) : List.copyOf(scored.subList(0, k));
   }

   /**
    * The decay curve: what fraction of a memory's recency weight survives to {@code now}.
    *
    * <p>{@code 0.5 ^ (hours / halfLife)}. Anchored to {@code lastSeenAt} — when the fact was last
    * <em>restated in conversation</em> — never to when it was first recorded, and never to when it
    * was last retrieved. Retrieval reinforcement is Park's mechanism and is deliberately absent:
    * being recalled is not evidence that a fact still holds, and a memory that refreshed itself on
    * every read would never decay at all.
    *
    * <p>Underflows to 0 for anything much past 20 half-lives, which at Park's constant is under four
    * months. That is not a bug to guard — it is the reason the constant is being measured, and it is
    * visible in a scored run as an entire candidate set with recency 0.
    */
   public double decay(Instant lastSeenAt, Instant now) {
      double hours = Duration.between(lastSeenAt, now).toMillis() / 3_600_000.0;
      if (hours <= 0) {
         return 1.0;   // seen now, or a clock skew we refuse to reward
      }
      return StrictMath.exp(-LN2 * hours / halfLifeHours);
   }

   /**
    * Scales values to [0,1] against the range present in this candidate set.
    *
    * <p>All-equal input yields all zeros: the term separates nothing here, and saying so in the
    * breakdown is more useful than a column of 1.0 that looks like unanimous agreement.
    */
   static double[] minMax(double[] v) {
      double lo = Double.POSITIVE_INFINITY;
      double hi = Double.NEGATIVE_INFINITY;
      for (double x : v) {
         lo = Math.min(lo, x);
         hi = Math.max(hi, x);
      }
      double span = hi - lo;
      double[] out = new double[v.length];
      if (!(span > 0)) {
         return out;
      }
      for (int i = 0; i < v.length; i++) {
         out[i] = (v[i] - lo) / span;
      }
      return out;
   }

   /** Score desc (quantised), then newer serial, then id. Total, and stable across languages. */
   private static final Comparator<ScoredMemory> RANKING =
         Comparator.comparingLong((ScoredMemory s) -> -Math.round(s.score() * SCORE_SCALE))
               .thenComparingLong(s -> -s.memory().serial())
               .thenComparing(ScoredMemory::id);
}
