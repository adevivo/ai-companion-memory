package com.neovetta.aicompanion.memory;

/**
 * Which reality a memory is a claim about.
 *
 * <p>Bitemporality answers <em>when</em> a fact held and <em>when</em> we believed it. Neither
 * answers <em>in which world</em> it is true, and in this corpus that is not a philosophical
 * nicety — it is the largest single source of false memory.
 *
 * <h2>What the owner review measured</h2>
 *
 * A companion's conversation history mixes life and fiction, and the owner writes himself into the
 * stories. Asked to curate 1,644 extracted facts, the owner discarded:
 *
 * <ul>
 *   <li><b>5%</b> of facts about a <em>character</em> — Mia owns a Camry is correct inside Mia's
 *       story, and the owner kept it on exactly that basis;</li>
 *   <li><b>14%</b> of facts about <em>himself</em> drawn from conversations with no fictional cast;</li>
 *   <li><b>49%</b> of facts about <em>himself</em> drawn from conversations that had one.</li>
 * </ul>
 *
 * The owner's own formulation: "Mia is going on a date with Adrian" is worth keeping, and "Adrian is
 * going on a date with Mia" is not. Same event, opposite verdicts, decided by which side the
 * extractor happened to file it under. The claim is identical; the world it belongs to is not.
 *
 * <h2>Why a field and not an inference</h2>
 *
 * Without this, a fiction-derived claim about the player is stored as ordinary biography with
 * {@code companionId == null} — which is the <em>most</em> dangerous placement available, because
 * null means companion-agnostic and every companion is told it. Half of those claims are false.
 *
 * <p>It cannot be recovered later either: it depends on whether the source conversation had a cast,
 * which is a property of the extraction run and not of the record. Same argument as the bitemporal
 * split — cheap to carry now, miserable to retrofit once the corpus exists.
 *
 * <p>Both signals that determine it are deterministic and computed in code from fields the extractor
 * already emits. Nothing asks a model to judge whether it was telling the truth.
 *
 * @see MemoryRecord#companionId()
 */
public enum MemoryFrame {

   /**
    * A claim about the actual player and the actual world. What a companion may state as fact.
    */
   REAL,

   /**
    * True inside one companion's established fiction, and not otherwise.
    *
    * <p>Requires a {@link MemoryRecord#companionId()}: a story has to belong to someone. That
    * constraint settles an open question the corpus raised — 517 extracted persona facts could not
    * be attributed to any named character, and an in-world claim with no world cannot be retrieved
    * safely by anyone. They are excluded rather than made companion-agnostic.
    *
    * <p>Retrieval should surface these only while the companion they belong to is speaking, and
    * never in answer to a question about the player's life.
    */
   IN_WORLD
}
