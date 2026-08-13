package com.neovetta.aicompanion.memory;

/**
 * How a memory's importance score was arrived at.
 *
 * <p>Park et al. have the model self-rate poignancy 1–10 once at write time. Later work argues that
 * using <em>extraction</em> as the filter — deciding what becomes a memory at all, and dropping
 * conversational filler before storage — beats rating everything that comes in.
 *
 * <p>Recording the provenance of the score rather than picking one lets the eval harness compare
 * them on the same corpus. It matters commercially too: under a bring-your-own-key model an
 * LLM rating call is money out of the player's pocket for every memory stored.
 */
public enum ImportanceSource {
   /** Implied by surviving the extraction filter; no per-memory model call. */
   EXTRACTION,
   /** Explicitly rated by a model at write time, Park-style. */
   LLM_RATED,
   /**
    * Never assessed — the record carries a default. True of observations, which are written
    * without a model call. Recorded honestly rather than dressed up as a score, so nothing
    * downstream mistakes a placeholder for a judgement.
    */
   UNRATED
}
