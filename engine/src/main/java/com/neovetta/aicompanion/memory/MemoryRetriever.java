package com.neovetta.aicompanion.memory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The read path end to end: filter, scan, rescore, score, cut.
 *
 * <pre>
 *   scope filter  →  int8 scan  →  top k*mult  →  float32 rescore  →  MemoryScorer  →  top k
 * </pre>
 *
 * <p>Each stage exists for a reason worth keeping straight:
 *
 * <ol>
 *   <li><b>Scope first.</b> An out-of-scope memory must not occupy a candidate slot. Filtering after
 *       the scan would let another companion's fiction crowd out a real memory and then be dropped,
 *       returning fewer results than asked for and silently degrading recall.</li>
 *   <li><b>int8 scan over everything in scope.</b> Cheap enough at this corpus size to be exhaustive,
 *       so nothing is missed for want of an index.</li>
 *   <li><b>A wider candidate set than k.</b> {@link #DEFAULT_CANDIDATE_MULTIPLIER} of them. int8
 *       ranking is approximate; taking 2k and re-ranking exactly recovers what quantisation lost.
 *       ⚠️ The "2 is enough" figure was measured on a corpus two rebuilds old and is due a
 *       re-measurement.</li>
 *   <li><b>float32 rescore, then the full score.</b> The non-relevance terms are min-max normalised
 *       across this candidate set — see {@link MemoryScorer} — so the multiplier is not a free
 *       parameter: widening it changes what recency and salience are measured relative to. Worth
 *       measuring rather than assuming.</li>
 * </ol>
 *
 * <p>Query embedding is not here and will not be. It needs a model, and the point of this class is
 * that scoring is a pure function over plaintext records held in memory — decryption happens before
 * it, the vector arrives from outside it, and it can therefore be tested, ported to JS, and reasoned
 * about without a network.
 */
public final class MemoryRetriever {

   /** Candidates fetched per requested result before the exact rescore. */
   public static final int DEFAULT_CANDIDATE_MULTIPLIER = 2;

   private final List<MemoryRecord> records;
   private final VectorIndex vectors;
   private final MemoryScorer scorer;
   private final int[] rowToRecord;

   public MemoryRetriever(List<MemoryRecord> records, VectorIndex vectors, MemoryScorer scorer) {
      this.records = List.copyOf(records);
      this.vectors = vectors;
      this.scorer = scorer;
      this.rowToRecord = new int[vectors.count()];
      Arrays.fill(rowToRecord, -1);
      for (int i = 0; i < this.records.size(); i++) {
         MemoryRecord r = this.records.get(i);
         if (!r.hasVector()) {
            continue;
         }
         int row = r.vectorRow();
         if (row < 0 || row >= vectors.count()) {
            throw new IllegalArgumentException(
                  r.id() + " points at vector row " + row + ", index holds " + vectors.count());
         }
         // Two records sharing a row means the corpus and the sidecars disagree about what is in
         // them, which is what a rebuild without a re-embed produces. Every retrieval number from
         // such a pair would be wrong in a way nothing downstream could detect.
         if (rowToRecord[row] != -1) {
            throw new IllegalArgumentException("vector row " + row + " claimed by both "
                  + this.records.get(rowToRecord[row]).id() + " and " + r.id());
         }
         rowToRecord[row] = i;
      }
   }

   /** Loads {@code records.jsonl} and the vector sidecars from a corpus directory. */
   public static MemoryRetriever load(Path corpusDir, MemoryScorer scorer) throws IOException {
      List<MemoryRecord> records = MemoryCodec.readJsonl(corpusDir.resolve("records.jsonl"));
      return new MemoryRetriever(records, VectorIndex.load(corpusDir), scorer);
   }

   public List<MemoryRecord> records() {
      return records;
   }

   public MemoryScorer scorer() {
      return scorer;
   }

   public VectorIndex vectors() {
      return vectors;
   }

   public List<ScoredMemory> retrieve(float[] query, Instant now, int k, RetrievalScope scope) {
      return retrieve(query, now, k, scope, DEFAULT_CANDIDATE_MULTIPLIER);
   }

   /**
    * @param query the query embedding; normalised here, so callers need not
    * @param candidateMultiplier how many candidates per result to rescore exactly
    */
   public List<ScoredMemory> retrieve(float[] query, Instant now, int k, RetrievalScope scope,
         int candidateMultiplier) {
      if (k <= 0) {
         return List.of();
      }
      if (candidateMultiplier < 1) {
         throw new IllegalArgumentException("candidateMultiplier must be >= 1");
      }
      float[] unit = VectorIndex.unitised(query);
      int[] rows = vectors.scan(VectorIndex.quantise(unit), k * candidateMultiplier,
            row -> {
               int idx = rowToRecord[row];
               return idx >= 0 && scope.admits(records.get(idx));
            });

      List<MemoryRecord> candidates = new ArrayList<>(rows.length);
      double[] cosine = new double[rows.length];
      for (int i = 0; i < rows.length; i++) {
         candidates.add(records.get(rowToRecord[rows[i]]));
         cosine[i] = vectors.cosine(rows[i], unit);
      }
      return scorer.rank(candidates, cosine, now, k);
   }
}
