package com.neovetta.aicompanion.memory;

/**
 * Whether a memory belongs to the person or to one of their worlds.
 *
 * <p>The distinction exists because a player has several saves and a person has one life. "Prefers
 * cobblestone for anything left out in the weather" is true wherever they are playing; "has a pet
 * wolf named Itchy" is true in exactly one world and false in the rest.
 *
 * <p>Without it, memories leak between saves — and the leak is <b>invisible</b>, because a memory
 * from the wrong world reads exactly like a valid one. There is no error, no symptom, and nothing in
 * normal play that would surface it.
 *
 * <p>⚠️ <b>There is no safe default, which is why this field is required.</b> Defaulting to
 * {@link #PERSON} makes a mis-classified world fact visible in every save; defaulting to
 * {@link #WORLD} needs a world id the caller also would not have. A record that does not know its
 * own scope is a bug, and {@link MemoryRecord} refuses to construct one.
 *
 * <p>The split was already visible in the data before it was needed: the decay measurements found
 * roughly 70% of labelled facts were trait-like predicates that cannot go stale, and traits are
 * precisely the person-scoped class.
 *
 * @see <a href="../../../../../../../docs/memory-scope.md">docs/memory-scope.md</a>
 */
public enum MemoryScope {
   /**
    * True of the player, in every world and every session. Preferences, traits, habits, how they
    * like to be spoken to. Carries no world id and no {@link Place}.
    */
   PERSON,

   /**
    * True in one save only. Pets, goals, what has been built, where things are.
    *
    * <p>Requires a world id. May additionally carry a {@link Place} when the memory is about
    * somewhere in particular — see {@link MemoryRecord#place()} for why text alone is not enough.
    */
   WORLD
}
