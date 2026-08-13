// Ranks candidate memories for one query. Port of MemoryScorer.java.
//
// This file and its Java twin must produce identical rankings for identical input, because the
// mod and the browser chat read the same corpus and a player who is told different things by the
// same companion in two places has caught the product lying. Every deviation below from the
// obvious JS spelling is there to hold that property. They are marked ⚠️ PARITY.
//
// The scoring itself, and why each term is weighted the way it is, is documented once in
// MemoryScorer.java. Only the parity notes are repeated here.

/** Park's `0.995^hours` expressed as a half-life. Derived, not transcribed, exactly as in Java. */
export const PARK_HALF_LIFE_HOURS = Math.LN2 / -Math.log(0.995);

/** 91 days. The default, and inert while recency carries weight zero. See docs/decay.md. */
export const DEFAULT_HALF_LIFE_HOURS = 91 * 24;

/** Decimal places scores are rounded to before they are compared. */
export const SCORE_PRECISION = 6;

const SCORE_SCALE = Math.pow(10, SCORE_PRECISION);
const MILLIS_PER_HOUR = 3600000;

/** Term weights. Frozen so a shared constant cannot be mutated by one caller for everyone. */
export function weights(relevance, recency, importance, salience) {
   if (relevance < 0 || recency < 0 || importance < 0 || salience < 0) {
      throw new Error('weights must be non-negative');
   }
   if (relevance + recency + importance + salience <= 0) {
      throw new Error('at least one weight must be positive');
   }
   return Object.freeze({ relevance, recency, importance, salience });
}

/** The literature baseline: relevance + recency + importance, unweighted, no salience. */
export const PARK = weights(1, 1, 1, 0);

/** Relevance alone — what a plain vector search does. The floor any term must beat. */
export const RELEVANCE_ONLY = weights(1, 0, 0, 0);

/**
 * What this project measured, and therefore the default. Identical to RELEVANCE_ONLY, named
 * separately because it is a conclusion rather than a control — no weighting of the other three
 * terms beat plain cosine on 295 natural questions. See docs/retrieval-scoring.md.
 */
export const MEASURED = weights(1, 0, 0, 0);

/**
 * Scales values to [0,1] against the range present in this candidate set.
 * All-equal input yields all zeros, so a term that separates nothing says so in the breakdown.
 */
export function minMax(v) {
   let lo = Infinity;
   let hi = -Infinity;
   for (const x of v) {
      if (x < lo) lo = x;
      if (x > hi) hi = x;
   }
   const span = hi - lo;
   const out = new Array(v.length).fill(0);
   if (!(span > 0)) return out;
   for (let i = 0; i < v.length; i++) out[i] = (v[i] - lo) / span;
   return out;
}

export class MemoryScorer {
   /**
    * @param {object} w term weights
    * @param {number} halfLifeHours recency half-life
    */
   constructor(w = MEASURED, halfLifeHours = DEFAULT_HALF_LIFE_HOURS) {
      if (!(halfLifeHours > 0)) {
         throw new Error(`halfLifeHours must be positive, got ${halfLifeHours}`);
      }
      this.weights = w;
      this.halfLifeHours = halfLifeHours;
      Object.freeze(this);
   }

   /** Park's baseline, exactly. */
   static park() {
      return new MemoryScorer(PARK, PARK_HALF_LIFE_HOURS);
   }

   /** The configuration this project's measurements support. Use this unless experimenting. */
   static measured() {
      return new MemoryScorer(MEASURED, DEFAULT_HALF_LIFE_HOURS);
   }

   /**
    * What fraction of a memory's recency weight survives to `now`. Anchored to lastSeenAt.
    * @param {number} lastSeenAt epoch millis
    * @param {number} now epoch millis
    */
   decay(lastSeenAt, now) {
      const hours = (now - lastSeenAt) / MILLIS_PER_HOUR;
      if (hours <= 0) return 1.0;   // seen now, or a clock skew we refuse to reward
      return Math.exp((-Math.LN2 * hours) / this.halfLifeHours);
   }

   /**
    * Ranks candidates and returns the best k. Pure: no vectors, no I/O, no clock.
    *
    * @param {Array} candidates scope-filtered memory records
    * @param {number[]} cosine similarity per candidate, parallel to `candidates`
    * @param {number} now epoch millis
    * @param {number} k how many to return
    */
   rank(candidates, cosine, now, k) {
      if (candidates.length !== cosine.length) {
         throw new Error(
            `cosine[] has ${cosine.length} entries for ${candidates.length} candidates`);
      }
      if (k < 0) throw new Error(`k must be >= 0, got ${k}`);
      const n = candidates.length;
      if (n === 0 || k === 0) return [];

      const rec = new Array(n);
      const imp = new Array(n);
      const sal = new Array(n);
      for (let i = 0; i < n; i++) {
         const r = candidates[i];
         rec[i] = this.decay(r.lastSeenAt, now);
         // ⚠️ PARITY, and this one was caught by the real-corpus run, not by the fixture.
         // MemoryRecord.importance is a `float` in Java and a double here, so 0.7 on the wire is
         // 0.699999988079071 there and 0.7 exactly here — a 1.2e-8 gap. min-max then amplifies it
         // by 1/(hi-lo): harmless at this corpus's 0.1-1.0 spread, but a candidate set whose
         // importance values span less than ~0.02 pushes it past the 5e-7 the ranking comparison
         // can see, and the two languages return different memories. Round to float32 so both
         // sides score the identical number at any spread.
         imp[i] = Math.fround(r.importance);
         // ⚠️ PARITY (unverified): log1p to correspond exactly with Java's StrictMath.log1p.
         // Note the usual argument for log1p does NOT apply — it buys accuracy for x near zero
         // and topicDays is always >= 1. This is here for correspondence, not accuracy, and the
         // fixture does not distinguish it from Math.log(1 + x). See docs/cross-language.md.
         sal[i] = Math.log1p(r.topicDays);
      }

      const nRel = minMax(cosine);
      const nRec = minMax(rec);
      const nImp = minMax(imp);
      const nSal = minMax(sal);
      const w = this.weights;

      const scored = new Array(n);
      for (let i = 0; i < n; i++) {
         // ⚠️ PARITY (unverified): spelled in the same order as Java's, because floating point
         // addition is not associative. The fixture does not catch a reordering — at 8 dimensions
         // the 6-decimal comparison absorbs it — so this is a discipline the real-corpus
         // agreement run has to check. See docs/cross-language.md.
         const score = w.relevance * nRel[i]
            + w.recency * nRec[i]
            + w.importance * nImp[i]
            + w.salience * nSal[i];
         scored[i] = {
            memory: candidates[i],
            score,
            relevance: nRel[i],
            recency: nRec[i],
            importance: nImp[i],
            salience: nSal[i],
            cosine: cosine[i],
            id: candidates[i].id,
         };
      }
      scored.sort(compareRanked);
      return scored.slice(0, k);
   }
}

/**
 * Score desc (quantised to SCORE_PRECISION), then newer serial, then id.
 *
 * ⚠️ PARITY, and the reason this comparator exists at all: Java's StrictMath.exp and this
 * engine's Math.exp may differ in the last bit. Comparing raw scores would let that swap two
 * memories. Quantising the comparison makes it impossible, and the chain is total so no ordering
 * is left to the sort's discretion — Array#sort is not required to be stable across engines for
 * all inputs, and relying on that would reintroduce exactly the problem this avoids.
 *
 * Math.round matches Java's Math.round here (both floor(x + 0.5)); scores are non-negative, so
 * the two implementations' disagreement on exact negative halves cannot arise.
 */
function compareRanked(a, b) {
   const sa = Math.round(a.score * SCORE_SCALE);
   const sb = Math.round(b.score * SCORE_SCALE);
   if (sa !== sb) return sb - sa;
   if (a.memory.serial !== b.memory.serial) return b.memory.serial - a.memory.serial;
   return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
}
