package com.neovetta.aicompanion.memory;

/**
 * What sort of memory this is.
 *
 * <p>Park et al.'s memory stream stores raw <em>observations</em> as they occur and later derives
 * higher-level nodes from them by reflection. An earlier version of the extractor here kept only
 * durable propositions and discarded roughly 86% of turns, which was a silent departure from that
 * baseline in two ways.
 *
 * <p>First, aggregate signal is invisible to per-turn extraction. A question is not a durable fact,
 * but a year of questions about the same subject is strong evidence of an interest, and no single
 * turn contains it. Only the accumulated stream does.
 *
 * <p>Second, and worse for a testbed: discarding the repetitive material manufactures a corpus that
 * is too clean. Near-duplicate flooding is one of the failure modes this POC exists to measure, and
 * filtering the duplicates out at write time removes the phenomenon rather than solving it.
 */
public enum MemoryKind {
   /**
    * Something that happened, recorded as it occurred. Text is the source turn itself, so these
    * cost nothing to produce and preserve repetition verbatim.
    */
   OBSERVATION,

   /** A durable proposition extracted from conversation — still true and useful months later. */
   FACT,

   /**
    * A higher-level insight derived by consolidating observations, Park's reflection. Where
    * "asks about nginx constantly" becomes "works with nginx".
    */
   REFLECTION
}
