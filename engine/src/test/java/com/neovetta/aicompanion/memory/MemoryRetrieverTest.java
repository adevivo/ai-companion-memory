package com.neovetta.aicompanion.memory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryRetrieverTest {

   private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");
   private static final Instant MARCH = Instant.parse("2026-03-01T00:00:00Z");

   /** Unit vectors in the plane, so every expected cosine can be read off by hand. */
   private static final float[] EAST = {1.0f, 0.0f};
   private static final float[] NORTH = {0.0f, 1.0f};
   private static final float[] NORTHEAST = {0.70710678f, 0.70710678f};
   private static final float[] WEST = {-1.0f, 0.0f};

   private static VectorIndex indexOf(float[]... rows) {
      int dim = rows[0].length;
      byte[] i8 = new byte[rows.length * dim];
      float[] f32 = new float[rows.length * dim];
      for (int r = 0; r < rows.length; r++) {
         byte[] q = VectorIndex.quantise(rows[r]);
         System.arraycopy(q, 0, i8, r * dim, dim);
         System.arraycopy(rows[r], 0, f32, r * dim, dim);
      }
      return new VectorIndex(rows.length, dim, i8, f32);
   }

   private static MemoryRecord.Builder base() {
      return MemoryRecord.builder()
            .id("mem")
            .serial(1L)
            .text("favourite biome is desert")
            .entity("player:favourite_biome")
            .value("desert")
            .kind(MemoryKind.FACT)
            .validFrom(MARCH)
            .recordedAt(MARCH)
            .importance(0.5f)
            .importanceSource(ImportanceSource.EXTRACTION)
            .aboutPerson()
            .context(MemoryContext.CHAT)
            .partition(Partition.OWNER)
            .frame(MemoryFrame.REAL)
            .provenance(new Provenance("openwebui:grendel", "chat-a", "msg-a", MARCH.toEpochMilli()));
   }

   private static MemoryRecord at(int row) {
      return base().id("m" + row).serial(row + 1).vectorRow(row).build();
   }

   @Nested
   @DisplayName("the packed sidecars")
   class Index {

      @Test
      @DisplayName("quantisation is the scale embed.py used")
      void quantisation() {
         assertArrayEquals(new byte[] {127, 0}, VectorIndex.quantise(EAST));
         assertArrayEquals(new byte[] {-127, 0}, VectorIndex.quantise(WEST));
         assertArrayEquals(new byte[] {90, 90}, VectorIndex.quantise(NORTHEAST));
      }

      @Test
      @DisplayName("a non-unit query is normalised, so cosine stays a dot product")
      void normalisation() {
         float[] unit = VectorIndex.unitised(new float[] {3.0f, 4.0f});
         assertEquals(0.6, unit[0], 1e-6);
         assertEquals(0.8, unit[1], 1e-6);
         assertEquals(1.0, Math.hypot(unit[0], unit[1]), 1e-6);
      }

      @Test
      @DisplayName("the scan ranks by similarity and returns the best first")
      void scanOrders() {
         VectorIndex idx = indexOf(NORTH, NORTHEAST, EAST, WEST);
         int[] rows = idx.scan(VectorIndex.quantise(EAST), 4, r -> true);
         assertArrayEquals(new int[] {2, 1, 0, 3}, rows);
      }

      @Test
      @DisplayName("ties break toward the lower row, so two runs cannot disagree")
      void tiesAreDeterministic() {
         VectorIndex idx = indexOf(EAST, EAST, EAST);
         assertArrayEquals(new int[] {0, 1}, idx.scan(VectorIndex.quantise(EAST), 2, r -> true));
      }

      @Test
      @DisplayName("n larger than the corpus returns everything, once")
      void scanBounded() {
         VectorIndex idx = indexOf(EAST, NORTH);
         assertEquals(2, idx.scan(VectorIndex.quantise(EAST), 99, r -> true).length);
         assertEquals(0, idx.scan(VectorIndex.quantise(EAST), 0, r -> true).length);
      }

      @Test
      @DisplayName("excluded rows never enter the candidate set")
      void filterApplies() {
         VectorIndex idx = indexOf(EAST, NORTH, WEST);
         assertArrayEquals(new int[] {1, 2}, idx.scan(VectorIndex.quantise(EAST), 3, r -> r != 0));
      }

      @Test
      @DisplayName("the float32 rescore is exact")
      void cosineIsExact() {
         VectorIndex idx = indexOf(EAST, NORTH, NORTHEAST);
         assertEquals(1.0, idx.cosine(0, EAST), 1e-7);
         assertEquals(0.0, idx.cosine(1, EAST), 1e-7);
         assertEquals(0.70710678, idx.cosine(2, EAST), 1e-7);
      }

      @Test
      @DisplayName("a query of the wrong dimension is an error, not a silent zero")
      void dimensionMismatch() {
         VectorIndex idx = indexOf(EAST, NORTH);
         assertThrows(IllegalArgumentException.class,
               () -> idx.scan(new byte[] {1, 2, 3}, 1, r -> true));
      }

      @Test
      @DisplayName("sidecars that do not match the manifest are refused on load")
      void loadChecksSizes(@TempDir Path dir) throws IOException {
         writeManifest(dir, 2, 2);
         Files.write(dir.resolve("vectors.i8"), new byte[] {127, 0, 0, 127});
         Files.write(dir.resolve("vectors.f32"), floats(1f, 0f, 0f, 1f));
         VectorIndex ok = VectorIndex.load(dir);
         assertEquals(2, ok.count());
         assertEquals(1.0, ok.cosine(0, EAST), 1e-7);

         // What a build_corpus.py run without a following embed.py leaves behind.
         writeManifest(dir, 3, 2);
         IOException e = assertThrows(IOException.class, () -> VectorIndex.load(dir));
         assertTrue(e.getMessage().contains("embed.py"), e.getMessage());
      }

      private void writeManifest(Path dir, int count, int dim) throws IOException {
         Files.writeString(dir.resolve("manifest.json"),
               "{\"count\":" + count + ",\"dim\":" + dim + "}", StandardCharsets.UTF_8);
      }

      private byte[] floats(float... v) {
         ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
         for (float f : v) {
            b.putFloat(f);
         }
         return b.array();
      }
   }

   @Nested
   @DisplayName("end to end")
   class EndToEnd {

      @Test
      @DisplayName("scope is applied before candidates are chosen, not after")
      void scopeDoesNotEatCandidateSlots() {
         // Row 0 is the perfect match and belongs to another companion's story. Filtering after
         // the scan would spend the single candidate slot on it and return nothing at all.
         List<MemoryRecord> records = List.of(
               base().id("fiction").serial(1).vectorRow(0)
                     .frame(MemoryFrame.IN_WORLD).companionId("lena").build(),
               base().id("real").serial(2).vectorRow(1).build());
         MemoryRetriever r = new MemoryRetriever(records, indexOf(EAST, NORTHEAST),
               MemoryScorer.park());

         List<ScoredMemory> hits = r.retrieve(EAST, NOW, 1, RetrievalScope.everything(), 1);
         assertEquals(List.of("real"), hits.stream().map(ScoredMemory::id).toList());

         List<ScoredMemory> asLena = r.retrieve(EAST, NOW, 1, RetrievalScope.forCompanion("lena"), 1);
         assertEquals(List.of("fiction"), asLena.stream().map(ScoredMemory::id).toList());
      }

      @Test
      @DisplayName("a wider candidate set can change the answer, so the multiplier is a parameter")
      void multiplierMatters() {
         // "near" is the closer match; "far" is more important and was restated this morning. At
         // multiplier 1 only one candidate is ever scored, so the other two terms never get a vote
         // and relevance decides alone — which is a plain vector search wearing a scorer's coat.
         List<MemoryRecord> records = List.of(
               base().id("near").serial(1).vectorRow(0).importance(0.0f).lastSeenAt(MARCH).build(),
               base().id("far").serial(2).vectorRow(1).importance(1.0f).lastSeenAt(NOW).build());
         MemoryRetriever r = new MemoryRetriever(records, indexOf(EAST, NORTHEAST),
               MemoryScorer.park());

         assertEquals(List.of("near"),
               r.retrieve(EAST, NOW, 1, RetrievalScope.everything(), 1).stream()
                     .map(ScoredMemory::id).toList());
         assertEquals(List.of("far"),
               r.retrieve(EAST, NOW, 1, RetrievalScope.everything(), 2).stream()
                     .map(ScoredMemory::id).toList());
      }

      @Test
      @DisplayName("un-embedded records are simply not retrievable")
      void noVectorNoRetrieval() {
         List<MemoryRecord> records = List.of(base().id("blind").serial(9).build(), at(0));
         MemoryRetriever r = new MemoryRetriever(records, indexOf(EAST), MemoryScorer.park());
         List<ScoredMemory> hits = r.retrieve(EAST, NOW, 10, RetrievalScope.everything());
         assertEquals(List.of("m0"), hits.stream().map(ScoredMemory::id).toList());
      }

      @Test
      @DisplayName("two records claiming one vector row is a stale-corpus error, caught on load")
      void duplicateRowsRejected() {
         List<MemoryRecord> clash = List.of(
               base().id("a").serial(1).vectorRow(0).build(),
               base().id("b").serial(2).vectorRow(0).build());
         IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
               () -> new MemoryRetriever(clash, indexOf(EAST, NORTH), MemoryScorer.park()));
         assertTrue(e.getMessage().contains("claimed by both"), e.getMessage());
      }

      @Test
      @DisplayName("a row index past the end of the sidecar is an error")
      void rowOutOfRange() {
         List<MemoryRecord> records = List.of(base().id("a").serial(1).vectorRow(7).build());
         assertThrows(IllegalArgumentException.class,
               () -> new MemoryRetriever(records, indexOf(EAST), MemoryScorer.park()));
      }
   }
}
