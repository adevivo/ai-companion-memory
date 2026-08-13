// The read path end to end. Port of MemoryRetriever.java.
//
//   scope filter → int8 scan → top k*mult → float32 rescore → MemoryScorer → top k
//
// Query embedding is not here and will not be. It needs a model; the point of this class is that
// scoring is a pure function over plaintext records held in memory, so it can be tested, ported,
// and reasoned about without a network.

import { MemoryScorer } from './memoryScorer.js';
import { VectorIndex } from './vectorIndex.js';

/**
 * Candidates fetched per requested result before the exact rescore.
 *
 * Measured, not guessed: over 295 queries against the real corpus, an int8 scan alone recovers
 * 95.19% of the exact top-10 and a multiplier of 2 recovers 100.00%. 3 buys 0.03% for 50% more
 * exact rescoring. See docs/retrieval-scoring.md.
 */
export const DEFAULT_CANDIDATE_MULTIPLIER = 2;

export class MemoryRetriever {
   /**
    * @param {Array} records memory records, in corpus order
    * @param {VectorIndex} vectors
    * @param {MemoryScorer} scorer
    */
   constructor(records, vectors, scorer = MemoryScorer.measured()) {
      this.records = records;
      this.vectors = vectors;
      this.scorer = scorer;

      this.rowToRecord = new Int32Array(vectors.count).fill(-1);
      for (let i = 0; i < records.length; i++) {
         const r = records[i];
         const row = r.vectorRow;
         if (row == null || row < 0) continue;
         if (row >= vectors.count) {
            throw new Error(
               `${r.id} points at vector row ${row}, index holds ${vectors.count}`);
         }
         // Two records sharing a row means the corpus and the sidecars disagree about what is in
         // them — what a rebuild without a re-embed produces. Every retrieval number from such a
         // pair would be wrong in a way nothing downstream could detect.
         if (this.rowToRecord[row] !== -1) {
            throw new Error(`vector row ${row} claimed by both `
               + `${records[this.rowToRecord[row]].id} and ${r.id}`);
         }
         this.rowToRecord[row] = i;
      }
   }

   /**
    * @param {number[]|Float32Array} query the query embedding; normalised here
    * @param {number} now epoch millis
    * @param {number} k
    * @param {RetrievalScope} scope
    * @param {number} candidateMultiplier
    */
   retrieve(query, now, k, scope, candidateMultiplier = DEFAULT_CANDIDATE_MULTIPLIER) {
      if (k <= 0) return [];
      if (candidateMultiplier < 1) throw new Error('candidateMultiplier must be >= 1');

      const unit = VectorIndex.unitised(query);
      // Scope is applied inside the scan, not after it. Filtering afterwards would let another
      // companion's fiction occupy a candidate slot and then be dropped, returning fewer results
      // than asked for and silently degrading recall.
      const rows = this.vectors.scan(
         VectorIndex.quantise(unit),
         k * candidateMultiplier,
         (row) => {
            const idx = this.rowToRecord[row];
            return idx >= 0 && scope.admits(this.records[idx]);
         });

      const candidates = new Array(rows.length);
      const cosine = new Array(rows.length);
      for (let i = 0; i < rows.length; i++) {
         candidates[i] = this.records[this.rowToRecord[rows[i]]];
         cosine[i] = this.vectors.cosine(rows[i], unit);
      }
      return this.scorer.rank(candidates, cosine, now, k);
   }
}
