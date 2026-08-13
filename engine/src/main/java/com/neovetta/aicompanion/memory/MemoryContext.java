package com.neovetta.aicompanion.memory;

/**
 * Which side of the product a memory came from.
 *
 * <p>The companion is reachable both in-game and through the out-of-game web chat, and the two
 * produce very differently shaped memories: in-game is terse, embodied, entity- and
 * coordinate-heavy; chat is discursive and social. Retrieval has to serve a query from one context
 * against a corpus containing both — "what is relevant to standing in a mine at night when half the
 * corpus is a conversation about someone's school day". Tagging at write time is what makes that
 * measurable instead of guesswork.
 */
public enum MemoryContext {
   /** Observed in-game: world events, deaths, advancements, situational chatter. */
   GAME,
   /** From the out-of-game web chat. */
   CHAT
}
