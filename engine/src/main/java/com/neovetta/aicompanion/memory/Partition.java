package com.neovetta.aicompanion.memory;

/**
 * Whose data a memory came from, and therefore what it may be used for.
 *
 * <p>The Tier 1 corpus is drawn from two OpenWebUI instances that more than one person uses. Only
 * the owner's own history can be labelled — the value of that corpus rests on the owner being able
 * to judge "should this memory have surfaced for this query?" in seconds, and that judgement is
 * guesswork on anyone else's conversation.
 *
 * <p>Other people's messages still earn their place as unlabelled background that makes retrieval
 * realistically harder, so they are kept and marked rather than dropped. Carrying the distinction as
 * a field means the labelled set cannot quietly acquire data nobody can ground-truth, which a naming
 * convention or a filter applied at the call site would eventually let happen.
 */
public enum Partition {
   /** The owner's own messages. Eligible for the labelled eval set. */
   OWNER,
   /** Someone else's. Retrieval distractors only — never labelled, never ground truth. */
   DISTRACTOR
}
