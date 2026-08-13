package com.neovetta.aicompanion.memory;

import java.util.Objects;

/**
 * Where a memory came from, precisely enough to go back and look at it.
 *
 * <p>Labelling is the expensive half of building an eval set, and a label is only worth having if
 * the judgement behind it can be re-examined. When a query returns something surprising the first
 * question is always "what was actually said?", and without a path back to the source turn that
 * question is unanswerable.
 *
 * <p>This is also the seam where sources other than chat arrive: Minecraft logs carry a file and a
 * line rather than a chat and a message, so {@code sourceId} and {@code turnId} are deliberately
 * opaque strings rather than anything chat-shaped.
 *
 * @param origin    the producing system — {@code "openwebui:grendel"}, {@code "mclog:survival"}
 * @param sourceId  conversation, file, or session identifier within that origin
 * @param turnId    the individual message or line
 * @param sourceTs  epoch millis as recorded by the source, before any normalisation
 */
public record Provenance(String origin, String sourceId, String turnId, long sourceTs) {

   public Provenance {
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(sourceId, "sourceId");
      Objects.requireNonNull(turnId, "turnId");
   }
}
