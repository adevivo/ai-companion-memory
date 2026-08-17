package com.neovetta.aicompanion.memory;

import java.util.Objects;

/**
 * Where a memory happened, when the memory is about somewhere in particular.
 *
 * <h2>Why text is not enough</h2>
 *
 * A memory reading "the bridge across the ravine east of the base" contains no position. Given that
 * memory and asked to continue the work, a model that must emit a build command has to get
 * coordinates from somewhere — and it will take the player's current position, because that is the
 * only one available and its instructions tell it to use exactly that rather than invent numbers.
 *
 * <p>Observed 2026-08-17: the companion recalled the bridge correctly and then built one where the
 * player happened to be standing. **That is not a hallucination.** It followed its instructions with
 * a memory that could not carry the one fact that mattered.
 *
 * <h2>Integers, deliberately</h2>
 *
 * Block coordinates, not entity positions. Two reasons, and the second is the load-bearing one:
 *
 * <ol>
 *   <li>Everything a memory points at — a build, a base, a landmark — sits on a block, and the
 *       commands that act on one take block coordinates.</li>
 *   <li><b>Integers are exact in both Java and JavaScript.</b> A float-width field would need the
 *       same {@code Math.fround} treatment as {@code importance}, and would not be caught by the
 *       fixture until some case happened to exercise a narrow spread. Choosing {@code int} removes
 *       that failure mode instead of guarding it.</li>
 * </ol>
 *
 * @param dimension the dimension id, e.g. {@code minecraft:overworld}. Namespaced, because a
 *                  modded dimension is as real a place as a vanilla one, and (0,64,0) in the Nether
 *                  is nowhere near (0,64,0) in the overworld.
 * @param x block x
 * @param y block y
 * @param z block z
 */
public record Place(String dimension, int x, int y, int z) {

   public Place {
      Objects.requireNonNull(dimension, "dimension");
      if (dimension.isBlank()) {
         throw new IllegalArgumentException("dimension must not be blank");
      }
   }

   /** {@code minecraft:overworld at 218, 79, 23} — for logs and prompt text. */
   @Override
   public String toString() {
      return dimension + " at " + x + ", " + y + ", " + z;
   }
}
