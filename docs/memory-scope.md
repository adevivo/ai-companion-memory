# Scope: which world does a memory belong to?

Decided 2026-08-17, after the first play session with recall enabled. This is a schema change and it
blocks storage — a stored corpus with no world column is a migration rather than an edit.

## The problem

`MemoryRecord` carries where a fact came from (`context`: GAME/CHAT) and two time dimensions, but
nothing saying **which save it belongs to**.

That is an assumption inherited from the POC corpus, which was text chat about a person. People do
not fork into parallel universes; Minecraft players do. A single player here had seven worlds.

Two failures follow, and both were observed on the first day:

**Cross-world leakage would be invisible.** A memory from another save reads exactly like a valid
one. There is no symptom, no error, and nothing in normal play that would surface it — the same
failure shape this project keeps finding.

**A memory about a place, with no place in it, makes the model invent one.** Asked *"what about the
bridge we were building"*, the companion recalled the right fact and then issued
`build_structure ... at position (218, 79, 23)` — where the player happened to be standing. That is
not a hallucination: the prompt instructs it to take coordinates from `agentStatus` and never invent
them, the memory carried none, so it obeyed. **Text cannot carry a memory about a place.**

## Three classes, two fields

The three classes are real, but encoding them as a three-valued enum would be wrong, because
"located" is not exclusive with "world state" — every located fact *is* a world fact that also has
coordinates. Asking whether "the bridge over the ravine east of base" is world-state *or* located
is a false choice.

So: **two orthogonal fields.**

```
scope : PERSON | WORLD          required
place : (dimension, x, y, z)    optional; only legal when scope = WORLD
```

| class | example | encoding |
|---|---|---|
| **conceptual / preference / trait** | "prefers cobblestone for anything left out in the weather" | `PERSON`, no place |
| **world state** | "has a pet wolf named Itchy"<br>"is hunting diamonds, wants full diamond armour" | `WORLD`, no place |
| **build detail / location** | "the bridge across the ravine east of base" | `WORLD` + place |

**Goals need no new machinery.** "Hunting diamonds, wants full diamond armour" is world-scoped and
completes; the bitemporal model already expresses that as `validTo` when it is achieved. Supersession
is the existing mechanism and it fits.

### This split already had evidence

The decay work found ~70% of the labelled sample was trait-like predicates, which cannot go stale —
and traits are precisely the person-scoped class. Trait versus state was already visible in the data.
It simply was not load-bearing until worlds existed.

## World identity: mint a UUID

**Vanilla Minecraft 1.20.1 has no stable world identity.** Verified against seven real saves: the
`UUID` tag in `level.dat` is the *player's*, sitting between `DeathTime` and `XpTotal`.

What is actually available:

| candidate | problem |
|---|---|
| `getLevelName()` / save folder | renameable, and a rename silently orphans every memory |
| world seed | not unique; two worlds can share one |
| **a UUID we mint and store in the save** | ✅ survives renames; copying a world duplicates the id |

The decisive case is the headless server: `level-name` in `server.properties` defaults to literally
`world`, so name-based identity would collide across nearly every dedicated server in existence.

**Store the level name alongside the UUID as a human label**, so the web UI can show "Diamond Quest"
rather than a GUID. The label is decoration; the UUID is identity.

⚠️ There is no existing mechanism in the mod to reuse. `UnfinishedBuild` writes to
`FabricLoader.getConfigDir()` — the *global* config dir — keyed by companion UUID, and is safe only
because companion entity UUIDs happen to differ per world. That is an accident, not a design.

## Enforcement: a hard filter, like frame

A wrong-world memory must be **unreachable, not merely ranked low.** Ranking it low leaves a silent
failure that no amount of tuning removes, and there is exact precedent: `MemoryFrame.IN_WORLD` is
enforced in `RetrievalScope.admits()` *before* ranking, not weighted.

Mirror it on both sides:

- `RetrievalScope` gains a world id. `admits()` rejects any `WORLD`-scoped record whose world id
  differs. `PERSON`-scoped records always admit.
- The record constructor throws when a `WORLD`-scoped memory has no world id — exactly as it already
  throws for *"IN_WORLD memory needs a companionId"*.

There is no permissive default. An unscoped record is a bug, and it should fail loudly at
construction rather than quietly at retrieval.

## Who decides trait vs world at write time

**The extractor emits a predicate; code maps predicate → scope.** Not "ask the model whether this is
a trait".

This is the method that has worked throughout the project and the one that has failed every time it
was ignored: *ask models to describe, never to adjudicate.* Every "let the model judge" attempt
failed; every "let the model emit a field and let code decide" attempt worked. Predicates already
exist in the POC extraction pipeline.

## Open

- **Reclassification.** If a predicate's scope mapping changes, existing records are mis-scoped.
  Supersession can express it, but nothing triggers it.
- **World-to-world transfer.** A player who rebuilds a base in a new world arguably wants their
  traits and none of their world state, which is what this design gives — but "I always build my
  base near a village" is a *trait* extracted from repeated world state, and nothing produces it.
  That is `REFLECTION`, still declared and never generated.
- **Ephemeral references are not memories.** "Come back *here*" is conversational state with a
  lifetime of one task. Writing it to long-term memory would be wrong, and the write path needs a
  way to refuse it rather than storing every deictic reference forever.
