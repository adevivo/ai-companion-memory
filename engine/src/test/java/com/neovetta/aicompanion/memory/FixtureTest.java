package com.neovetta.aicompanion.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Pins this implementation to the cross-language fixture it generated.
 *
 * <p>Without this, the contract is only enforced from the JS side, and a change here would look
 * fine until somebody happened to run {@code npm test}. The fixture is checked in, so this test
 * makes the Java engine's own output a regression surface: change the scorer and this fails, which
 * is the reminder that the JS half has to move with it.
 *
 * <p>Regenerating the fixture ({@code ./gradlew -q fixture}) makes this pass by construction. That
 * is the point at which the JS suite becomes the real check, so regeneration should be deliberate
 * and both suites run afterwards.
 */
class FixtureTest {

   private static final Path FIXTURE = Path.of("..", "fixture");

   @TestFactory
   @DisplayName("every fixture case reproduces")
   List<DynamicTest> fixtureCases() throws IOException {
      Assumptions.assumeTrue(Files.exists(FIXTURE.resolve("cases.json")),
            "fixture not generated — run ./gradlew -q fixture");

      JsonObject root = JsonParser
            .parseString(Files.readString(FIXTURE.resolve("cases.json"), StandardCharsets.UTF_8))
            .getAsJsonObject();
      int dim = root.get("dim").getAsInt();
      int vectorCount = root.get("vectorCount").getAsInt();
      Instant now = Instant.ofEpochMilli(root.get("nowMillis").getAsLong());
      double scale = Math.pow(10, root.get("scorePrecision").getAsInt());

      List<MemoryRecord> records = MemoryCodec.readJsonl(FIXTURE.resolve("memories.jsonl"));
      byte[] i8 = Files.readAllBytes(FIXTURE.resolve("embeddings.i8"));
      byte[] raw = Files.readAllBytes(FIXTURE.resolve("embeddings.f32"));
      float[] f32 = new float[raw.length / 4];
      ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f32);
      VectorIndex index = new VectorIndex(vectorCount, dim, i8, f32);

      assertEquals(root.get("count").getAsInt(), records.size());
      assertTrue(records.size() > vectorCount,
            "the fixture should keep one un-embedded record, or it has lost a branch");

      List<DynamicTest> tests = new ArrayList<>();
      for (JsonElement el : root.getAsJsonArray("cases")) {
         JsonObject c = el.getAsJsonObject();
         tests.add(DynamicTest.dynamicTest(
               c.get("name").getAsString() + " — " + c.get("why").getAsString(),
               () -> runCase(c, records, index, dim, now, scale)));
      }
      return tests;
   }

   private void runCase(JsonObject c, List<MemoryRecord> records, VectorIndex index, int dim,
         Instant now, double scale) {
      JsonObject w = c.getAsJsonObject("weights");
      MemoryScorer scorer = new MemoryScorer(
            new MemoryScorer.Weights(w.get("relevance").getAsDouble(),
                  w.get("recency").getAsDouble(), w.get("importance").getAsDouble(),
                  w.get("salience").getAsDouble()),
            c.get("halfLifeHours").getAsDouble());

      JsonArray q = c.getAsJsonArray("query");
      float[] query = new float[dim];
      for (int i = 0; i < dim; i++) {
         query[i] = q.get(i).getAsFloat();
      }

      List<ScoredMemory> hits = new MemoryRetriever(records, index, scorer).retrieve(
            query, now, c.get("k").getAsInt(), scope(c.getAsJsonObject("scope")),
            c.get("candidateMultiplier").getAsInt());

      JsonArray expected = c.getAsJsonArray("expected");
      assertEquals(
            expected.asList().stream()
                  .map(e -> e.getAsJsonObject().get("id").getAsString()).collect(Collectors.toList()),
            hits.stream().map(ScoredMemory::id).collect(Collectors.toList()),
            "ranking changed");

      for (int i = 0; i < hits.size(); i++) {
         ScoredMemory got = hits.get(i);
         JsonObject want = expected.get(i).getAsJsonObject();
         String at = want.get("id").getAsString() + " rank " + (i + 1) + ": ";
         assertEquals(want.get("score").getAsDouble(), round(got.score(), scale), at + "score");
         assertEquals(want.get("cosine").getAsDouble(), round(got.cosine(), scale), at + "cosine");
         assertEquals(want.get("relevance").getAsDouble(), round(got.relevance(), scale),
               at + "relevance");
         assertEquals(want.get("recency").getAsDouble(), round(got.recency(), scale),
               at + "recency");
         assertEquals(want.get("importance").getAsDouble(), round(got.importance(), scale),
               at + "importance");
         assertEquals(want.get("salience").getAsDouble(), round(got.salience(), scale),
               at + "salience");
      }
   }

   private RetrievalScope scope(JsonObject s) {
      JsonElement companion = s.get("companionId");
      JsonElement world = s.get("worldId");
      JsonElement partition = s.get("partition");
      Set<MemoryKind> kinds = null;
      if (s.has("kinds")) {
         kinds = EnumSet.noneOf(MemoryKind.class);
         for (JsonElement k : s.getAsJsonArray("kinds")) {
            kinds.add(MemoryKind.valueOf(k.getAsString()));
         }
      }
      return new RetrievalScope(
            companion == null || companion.isJsonNull() ? null : companion.getAsString(),
            world == null || world.isJsonNull() ? null : world.getAsString(),
            partition == null || partition.isJsonNull() ? null
                  : Partition.valueOf(partition.getAsString()),
            s.get("includeSuperseded").getAsBoolean(),
            kinds);
   }

   private static double round(double v, double scale) {
      return Math.round(v * scale) / scale;
   }
}
