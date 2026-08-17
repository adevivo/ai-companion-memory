package com.neovetta.aicompanion.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VectorIndexTest {

   /** Deliberately not unit length, so unitisation is actually exercised. */
   private static final float[] A = {3.0f, 4.0f, 0.0f};
   private static final float[] B = {0.0f, -1.0f, 1.0f};
   private static final float[] C = {0.25f, 0.25f, 0.25f};

   @Nested
   @DisplayName("of() — the production path")
   class Of {

      @Test
      @DisplayName("reports the row count and width it was given")
      void shape() {
         VectorIndex idx = VectorIndex.of(List.of(A, B, C));
         assertEquals(3, idx.count());
         assertEquals(3, idx.dim());
      }

      @Test
      @DisplayName("unit-normalises, so callers need not")
      void unitises() {
         VectorIndex idx = VectorIndex.of(List.of(A));
         // A is (3,4,0), length 5 -> (0.6, 0.8, 0). Cosine against its own unit form must be 1.
         // Tolerance is float32 epsilon, not zero: the components are stored as float32 and
         // accumulated in double, so a self-cosine lands within ~1e-7 of 1.0 rather than on it.
         float[] unit = VectorIndex.unitised(A);
         assertEquals(1.0, idx.cosine(0, unit), 1e-6);
      }

      @Test
      @DisplayName("rejects an empty index rather than producing one that scores nothing")
      void rejectsEmpty() {
         assertThrows(IllegalArgumentException.class, () -> VectorIndex.of(List.of()));
         assertThrows(IllegalArgumentException.class, () -> VectorIndex.of(null));
      }

      @Test
      @DisplayName("rejects ragged rows, which would otherwise read across a row boundary")
      void rejectsRagged() {
         IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
               () -> VectorIndex.of(List.of(new float[] {1, 0}, new float[] {1, 0, 0})));
         assertTrue(e.getMessage().contains("same"), "should name the cause: " + e.getMessage());
      }

      @Test
      @DisplayName("rejects zero-width vectors")
      void rejectsZeroDim() {
         assertThrows(IllegalArgumentException.class, () -> VectorIndex.of(List.of(new float[0])));
      }
   }

   @Test
   @DisplayName("save() writes what load() reads, round-tripping exactly")
   void saveRoundTrips(@TempDir Path dir) throws IOException {
      // The production loop: build in memory, persist, come back after a restart. If this drifts,
      // a player's memories survive a restart as vectors that no longer describe them.
      VectorIndex built = VectorIndex.of(List.of(A, B, C));
      built.save(dir);
      VectorIndex reloaded = VectorIndex.load(dir);

      assertEquals(built.count(), reloaded.count());
      assertEquals(built.dim(), reloaded.dim());
      for (float[] probe : List.of(A, B, C)) {
         float[] unit = VectorIndex.unitised(probe);
         for (int row = 0; row < built.count(); row++) {
            assertEquals(built.cosine(row, unit), reloaded.cosine(row, unit), 0.0,
                  "row " + row + " changed across save/load");
         }
      }
   }

   @Test
   @DisplayName("row() returns the stored vector, so a store can rebuild without re-embedding")
   void rowRoundTrips() {
      VectorIndex idx = VectorIndex.of(List.of(A, B, C));
      // Rebuilding an index from its own rows must produce an identical index — that is the append
      // path: read what is held, add one, repack.
      VectorIndex rebuilt = VectorIndex.of(
            List.of(idx.row(0), idx.row(1), idx.row(2)));
      for (float[] probe : List.of(A, B, C)) {
         float[] unit = VectorIndex.unitised(probe);
         for (int r = 0; r < 3; r++) {
            assertEquals(idx.cosine(r, unit), rebuilt.cosine(r, unit), 0.0, "row " + r);
         }
      }
      assertThrows(IndexOutOfBoundsException.class, () -> idx.row(3));
      assertThrows(IndexOutOfBoundsException.class, () -> idx.row(-1));
   }

   @Test
   @DisplayName("save() creates the directory it is given")
   void saveCreatesDir(@TempDir Path dir) throws IOException {
      Path nested = dir.resolve("player").resolve("memories");
      VectorIndex.of(List.of(A)).save(nested);
      assertTrue(java.nio.file.Files.exists(nested.resolve("manifest.json")));
      assertTrue(java.nio.file.Files.exists(nested.resolve("vectors.i8")));
      assertTrue(java.nio.file.Files.exists(nested.resolve("vectors.f32")));
   }

   /**
    * The claim {@code of()}'s javadoc makes, and the one that matters: an index built in memory and
    * an index loaded from the packed sidecars are the same index. If this drifts, the evaluation
    * harness stops standing in for production — every number measured through {@code load()} would
    * describe a pipeline that no longer ships.
    */
   @Test
   @DisplayName("of() and load() produce byte-identical indexes for the same vectors")
   void ofMatchesLoad(@TempDir Path dir) throws IOException {
      List<float[]> vectors = List.of(A, B, C);
      VectorIndex built = VectorIndex.of(vectors);

      // Write the sidecars the way embed.py does: unit-normalised f32, int8 quantised from that.
      int dim = 3;
      int count = vectors.size();
      byte[] i8 = new byte[count * dim];
      ByteBuffer f32 = ByteBuffer.allocate(count * dim * 4).order(ByteOrder.LITTLE_ENDIAN);
      for (int row = 0; row < count; row++) {
         float[] unit = VectorIndex.unitised(vectors.get(row));
         System.arraycopy(VectorIndex.quantise(unit), 0, i8, row * dim, dim);
         for (float v : unit) {
            f32.putFloat(v);
         }
      }
      Files.write(dir.resolve("vectors.i8"), i8);
      Files.write(dir.resolve("vectors.f32"), f32.array());
      Files.writeString(dir.resolve("manifest.json"),
            "{\"count\":" + count + ",\"dim\":" + dim + "}", StandardCharsets.UTF_8);

      VectorIndex loaded = VectorIndex.load(dir);

      assertEquals(loaded.count(), built.count());
      assertEquals(loaded.dim(), built.dim());

      // Compare through the public surface: every row's exact cosine against every row's unit
      // vector. Equal cosines across the whole matrix means equal f32 contents.
      for (float[] probe : vectors) {
         float[] unit = VectorIndex.unitised(probe);
         for (int row = 0; row < count; row++) {
            assertEquals(loaded.cosine(row, unit), built.cosine(row, unit), 0.0,
                  "float32 rescore differs at row " + row);
         }
      }

      // And the int8 scan, which is the other half of the format and uses different bytes.
      for (float[] probe : vectors) {
         byte[] q = VectorIndex.quantise(VectorIndex.unitised(probe));
         assertEquals(java.util.Arrays.toString(loaded.scan(q, count, r -> true)),
               java.util.Arrays.toString(built.scan(q, count, r -> true)),
               "int8 scan order differs");
      }
   }
}
