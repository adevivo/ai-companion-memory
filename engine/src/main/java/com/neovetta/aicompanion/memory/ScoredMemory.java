package com.neovetta.aicompanion.memory;

/**
 * One memory with its score and the arithmetic that produced it.
 *
 * <p>The breakdown is not debug output. Every term in the scoring function is on trial in this
 * project — importance in particular does not separate durable facts from worthless ones at any
 * level yet measured — and a total score cannot answer "would this ranking survive dropping that
 * term". Keeping the components means an eval run can ask which term put each memory where it
 * landed, and can re-weight a recorded run without re-scoring the corpus.
 *
 * <p>{@link #relevance}, {@link #recency}, {@link #importance} and {@link #salience} are the
 * <em>normalised</em> term values in [0,1] — after min-max across the candidate set, before the
 * weights are applied. {@link #cosine} is the raw similarity that {@link #relevance} was normalised
 * from, kept because it is the only term with an absolute meaning: 0.31 is a weak match whatever
 * else was in the candidate set, while a normalised 1.0 only means "the best of these".
 *
 * @param score the weighted sum; the value the ranking is on
 * @param cosine raw cosine similarity to the query, in [-1,1]
 */
public record ScoredMemory(
      MemoryRecord memory,
      double score,
      double relevance,
      double recency,
      double importance,
      double salience,
      double cosine) {

   public String id() {
      return memory.id();
   }
}
