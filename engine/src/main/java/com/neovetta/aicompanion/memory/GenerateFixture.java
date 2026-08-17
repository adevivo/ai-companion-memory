package com.neovetta.aicompanion.memory;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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

/**
 * Writes the cross-language test fixture: synthetic memories, synthetic vectors, and the rankings
 * this implementation produces for a set of queries.
 *
 * <p>Retrieval ships twice — Java in the mod, JS in the browser chat — and the two must return
 * identical rankings or a player is told different things by the same companion depending on where
 * they are standing. That is a silent failure with no natural symptom, so it needs a fixture both
 * implementations are pinned to.
 *
 * <h2>Why the fixture is synthetic</h2>
 *
 * It cannot be built from the corpus. The corpus never leaves this machine and a fixture checked
 * into the product repo leaves by definition. That constraint turns out to be an improvement: a
 * test fixture should exercise the edges deliberately rather than sample the middle by accident,
 * and 40 hand-built records do that better than 40 real ones would.
 *
 * <p>Eight dimensions rather than 768, so the vectors are readable and a disagreement can be
 * traced by hand. Long-vector effects — accumulation order over 768 components, tie density in a
 * real corpus — are deliberately <em>not</em> covered here; they are covered by running both
 * implementations over the real corpus, which stays on this machine.
 *
 * <h2>Filenames</h2>
 *
 * Deliberately not {@code records.jsonl} / {@code vectors.f32} / {@code manifest.json}. Those bare
 * names appear in {@code .gitignore} as a Tier-1 privacy rule and match at any depth, so a fixture
 * using them would be silently untracked. Renaming the fixture is the correct fix; adding
 * exceptions to that rule is not.
 *
 * <pre>./gradlew -q fixture</pre>
 */
public final class GenerateFixture {

   private static final int DIM = 8;
   private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");
   private static final Instant EPOCH_START = Instant.parse("2025-09-01T00:00:00Z");

   private GenerateFixture() {
   }

   /** One case: a query, the parameters it runs under, and what this engine returned. */
   private record Case(String name, String why, float[] query, MemoryScorer.Weights weights,
         double halfLife, int k, int multiplier, RetrievalScope scope) {
   }

   public static void main(String[] args) throws IOException {
      Path dir = Path.of(args.length > 0 ? args[0] : "../fixture");
      Files.createDirectories(dir);

      List<MemoryRecord> records = new ArrayList<>();
      List<float[]> vectors = new ArrayList<>();
      build(records, vectors);

      // --- sidecars, byte-identical in layout to what embed.py writes -------------------
      ByteBuffer f32 = ByteBuffer.allocate(vectors.size() * DIM * 4).order(ByteOrder.LITTLE_ENDIAN);
      ByteBuffer i8 = ByteBuffer.allocate(vectors.size() * DIM).order(ByteOrder.LITTLE_ENDIAN);
      for (float[] v : vectors) {
         float[] unit = VectorIndex.unitised(v);
         for (float c : unit) {
            f32.putFloat(c);
         }
         i8.put(VectorIndex.quantise(unit));
      }
      Files.write(dir.resolve("embeddings.f32"), f32.array());
      Files.write(dir.resolve("embeddings.i8"), i8.array());
      MemoryCodec.writeJsonl(dir.resolve("memories.jsonl"), records);

      // --- run every case and record what came back ------------------------------------
      VectorIndex index = new VectorIndex(vectors.size(), DIM, i8.array(), toFloats(f32.array()));
      JsonArray cases = new JsonArray();
      for (Case c : cases()) {
         MemoryRetriever r =
               new MemoryRetriever(records, index, new MemoryScorer(c.weights(), c.halfLife()));
         List<ScoredMemory> hits = r.retrieve(c.query(), NOW, c.k(), c.scope(), c.multiplier());

         JsonObject o = new JsonObject();
         o.addProperty("name", c.name());
         o.addProperty("why", c.why());
         o.add("query", floats(c.query()));
         JsonObject w = new JsonObject();
         w.addProperty("relevance", c.weights().relevance());
         w.addProperty("recency", c.weights().recency());
         w.addProperty("importance", c.weights().importance());
         w.addProperty("salience", c.weights().salience());
         o.add("weights", w);
         o.addProperty("halfLifeHours", c.halfLife());
         o.addProperty("k", c.k());
         o.addProperty("candidateMultiplier", c.multiplier());
         JsonObject s = new JsonObject();
         s.addProperty("companionId", c.scope().companionId());
         s.addProperty("worldId", c.scope().worldId());
         s.addProperty("partition",
               c.scope().partition() == null ? null : c.scope().partition().name());
         s.addProperty("includeSuperseded", c.scope().includeSuperseded());
         if (c.scope().kinds() != null) {
            JsonArray kinds = new JsonArray();
            c.scope().kinds().stream().map(Enum::name).sorted().forEach(kinds::add);
            s.add("kinds", kinds);
         }
         o.add("scope", s);

         JsonArray expected = new JsonArray();
         for (ScoredMemory hit : hits) {
            JsonObject h = new JsonObject();
            h.addProperty("id", hit.id());
            // Scores are compared at SCORE_PRECISION, the same tolerance the ranking uses. A
            // tighter check would fail on a last-bit exp() difference that provably cannot change
            // any ordering, which is a test failing for the one reason the design already handles.
            h.addProperty("score", round(hit.score()));
            h.addProperty("cosine", round(hit.cosine()));
            h.addProperty("relevance", round(hit.relevance()));
            h.addProperty("recency", round(hit.recency()));
            h.addProperty("importance", round(hit.importance()));
            h.addProperty("salience", round(hit.salience()));
            expected.add(h);
         }
         o.add("expected", expected);
         cases.add(o);
      }

      JsonObject root = new JsonObject();
      root.addProperty("comment", "Cross-language fixture. Synthetic — contains no corpus data. "
            + "Regenerate with `./gradlew -q fixture`; both implementations must reproduce it.");
      // Two counts, not one: m80 is deliberately un-embedded, so the record file is longer than
      // the sidecars. A loader given a single count would size the vector index wrongly and fail
      // at load -- the right failure for the wrong reason.
      root.addProperty("count", records.size());
      root.addProperty("vectorCount", vectors.size());
      root.addProperty("dim", DIM);
      root.addProperty("nowMillis", NOW.toEpochMilli());
      root.addProperty("scorePrecision", MemoryScorer.SCORE_PRECISION);
      root.add("cases", cases);
      Files.writeString(dir.resolve("cases.json"),
            new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n",
            StandardCharsets.UTF_8);

      System.out.println("wrote " + dir.toAbsolutePath().normalize());
      System.out.println("  memories.jsonl  " + records.size() + " records ("
            + (records.size() - vectors.size()) + " deliberately un-embedded)");
      System.out.println("  embeddings.f32  " + vectors.size() * DIM * 4 + " bytes");
      System.out.println("  embeddings.i8   " + vectors.size() * DIM + " bytes");
      System.out.println("  cases.json      " + cases.size() + " cases");
   }

   /**
    * The corpus. Every record exists to make some branch of the read path observable.
    *
    * <p>Text is obviously synthetic. Nothing here may resemble corpus content, and a future reader
    * should be able to tell that at a glance rather than by checking.
    */
   private static void build(List<MemoryRecord> records, List<float[]> vectors) {
      // Six directions in the plane of dims 0-1, so relevance ordering is arithmetic anyone can
      // verify: the query is due east and cosine falls off with the angle.
      add(records, vectors, b("m01", 1, "east one", 0.9f, 1, days(300)), unit(1, 0));
      add(records, vectors, b("m02", 2, "east two", 0.5f, 1, days(200)), unit(1, 0));
      add(records, vectors, b("m03", 3, "north east", 0.5f, 1, days(100)), unit(1, 1));
      add(records, vectors, b("m04", 4, "north", 0.5f, 1, days(50)), unit(0, 1));
      add(records, vectors, b("m05", 5, "west", 0.5f, 1, days(10)), unit(-1, 0));

      // m01/m02 share a direction exactly. Identical cosine, identical everything the scorer can
      // see except serial — this is the tie-break, and the only case that distinguishes a total
      // ordering from a merely-sorted one.

      // Recency spread, all equally relevant: exercises decay and the min-max that follows it.
      add(records, vectors, b("m10", 10, "recent", 0.5f, 1, hours(0)), unit(1, 0));
      add(records, vectors, b("m11", 11, "one half-life", 0.5f, 1, hours(91 * 24)), unit(1, 0));
      add(records, vectors, b("m12", 12, "two half-lives", 0.5f, 1, hours(182 * 24)), unit(1, 0));
      // ⚠️ lastSeenAt in the future. Clock skew is real on a client and must not be rewarded with
      // more than a fresh memory gets.
      add(records, vectors, b("m13", 13, "future", 0.5f, 1, hours(-48)), unit(1, 0));

      // Salience spread. topicDays >= occurrences is a record invariant, so these vary together.
      add(records, vectors, b("m20", 20, "topic once", 0.5f, 1, days(120)), unit(1, 0));
      add(records, vectors, b("m21", 21, "topic often", 0.5f, 40, days(120)), unit(1, 0));

      // Importance spread at identical everything-else.
      add(records, vectors, b("m30", 30, "low importance", 0.0f, 1, days(120)), unit(1, 0));
      add(records, vectors, b("m31", 31, "high importance", 1.0f, 1, days(120)), unit(1, 0));

      // Frame and scope. m40 is another companion's fiction and must be unreachable to everyone
      // else; m41 belongs to a different companion again.
      add(records, vectors, b("m40", 40, "lena's story", 0.5f, 1, days(120))
            .frame(MemoryFrame.IN_WORLD).companionId("lena"), unit(1, 0));
      add(records, vectors, b("m41", 41, "mia's story", 0.5f, 1, days(120))
            .frame(MemoryFrame.IN_WORLD).companionId("mia"), unit(1, 0));

      // Another person's conversation. Never returned when the scope names a partition.
      add(records, vectors, b("m50", 50, "distractor", 0.5f, 1, days(120))
            .partition(Partition.DISTRACTOR), unit(1, 0));

      // Withdrawn and closed. Retracted is gone under every scope; superseded returns only when
      // asked for. Both are built with their row already set, because retractedAt/supersededAt
      // carry vectorRow across and a row assigned afterwards would be lost.
      records.add(b("m60", 60, "retracted", 0.9f, 1, days(120))
            .vectorRow(vectors.size()).build().retractedAt(NOW));
      vectors.add(unit(1, 0));
      records.add(b("m61", 61, "superseded", 0.9f, 1, days(120))
            .vectorRow(vectors.size()).build().supersededAt(NOW));
      vectors.add(unit(1, 0));

      // A different kind, for the kind filter.
      add(records, vectors, b("m70", 70, "an observation", 0.1f, 1, days(120))
            .kind(MemoryKind.OBSERVATION), unit(1, 0));

      // Not embedded, so no row is assigned. Must be invisible to retrieval without being an
      // error — an un-embedded memory is a normal state, not a broken one.
      records.add(b("m80", 80, "no vector", 0.5f, 1, days(120)).build());

      // int8 rounding at the exact halves, where Java's and JS's Math.round have to agree.
      // 0.5/127 quantises to exactly 0.5 before rounding; the negative twin lands on -0.5, where
      // the two languages' round-half rules are most likely to diverge.
      add(records, vectors, b("m90", 90, "positive half", 0.5f, 1, days(120)),
            raw(1.0f, 0.5f / 127f, 0, 0, 0, 0, 0, 0));
      add(records, vectors, b("m91", 91, "negative half", 0.5f, 1, days(120)),
            raw(1.0f, -0.5f / 127f, 0, 0, 0, 0, 0, 0));

      // Dense vectors, every component non-zero and none of them exactly representable in binary
      // floating point. The sparse records above cannot expose a precision disagreement: unitising
      // (1,0,...) is exact and a dot product with seven zeros has nothing to accumulate. These
      // exist so that float32 rounding in `unitised` and the accumulation order in `cosine` have
      // something to actually do.
      //
      // ⚠️ Even so, eight dimensions is a weak test of arithmetic that ships over 768. The real
      // check is running both implementations over the real corpus — see docs/cross-language.md.
      add(records, vectors, b("m95", 95, "dense a", 0.5f, 7, days(120)),
            raw(0.31415927f, 0.27182818f, -0.16180339f, 0.14142136f,
                -0.57721566f, 0.69314718f, -0.43429448f, 0.20205690f));
      add(records, vectors, b("m96", 96, "dense b", 0.5f, 13, days(120)),
            raw(0.30000001f, 0.28000003f, -0.15999998f, 0.13999999f,
                -0.57000005f, 0.69000006f, -0.43000001f, 0.19999999f));
      add(records, vectors, b("m97", 97, "dense c", 0.5f, 41, days(120)),
            raw(-0.11111111f, 0.33333333f, 0.66666667f, -0.22222222f,
                0.77777778f, -0.88888889f, 0.55555556f, 0.99999999f));

      // A NARROW importance spread, which is the only condition under which the float/double
      // difference in that field can change a ranking. `importance` is a float in Java and a
      // double in JS, so 0.7 on the wire differs by ~1.2e-8 between them; min-max amplifies that
      // by 1/(hi-lo), and at a spread of 0.002 it lands at ~6e-6 — an order of magnitude past the
      // 5e-7 the ranking comparison can see.
      //
      // Found by the real-corpus agreement run, not by this fixture, and added here so the
      // product repo can catch a regression without needing a corpus it will never have.
      // Their own direction (dim 2), which nothing else uses, so a query pointing there selects
      // exactly these three. Identical vectors, so relevance normalises to all-zero and
      // importance is the only term with anything to say.
      add(records, vectors, b("ma0", 200, "narrow importance a", 0.700f, 1, days(120)),
            raw(0, 0, 1, 0, 0, 0, 0, 0));
      add(records, vectors, b("ma1", 201, "narrow importance b", 0.701f, 1, days(120)),
            raw(0, 0, 1, 0, 0, 0, 0, 0));
      add(records, vectors, b("ma2", 202, "narrow importance c", 0.702f, 1, days(120)),
            raw(0, 0, 1, 0, 0, 0, 0, 0));

      // --- world scoping -------------------------------------------------------------------
      //
      // Three memories on one shared direction (dim 3): one belonging to each of two saves, and
      // one about the player themselves. A query pointing there reaches all three, so what comes
      // back is decided purely by the asker's world -- which is the whole point. Without the
      // filter a caller in "world-a" would see world-b's memory ranked identically, and nothing
      // about the result would look wrong.
      add(records, vectors, b("mw0", 300, "world a fact", 0.5f, 1, days(120))
            .inWorld("world-a"), raw(0, 0, 0, 1, 0, 0, 0, 0));
      add(records, vectors, b("mw1", 301, "world b fact", 0.5f, 1, days(120))
            .inWorld("world-b"), raw(0, 0, 0, 1, 0, 0, 0, 0));
      add(records, vectors, b("mw2", 302, "person fact", 0.5f, 1, days(120)),
            raw(0, 0, 0, 1, 0, 0, 0, 0));
      // A located memory: same world as mw0, and carries the coordinates that text alone cannot.
      add(records, vectors, b("mw3", 303, "world a bridge", 0.5f, 1, days(120))
            .inWorld("world-a").place(new Place("minecraft:overworld", 218, 79, 23)),
            raw(0, 0, 0, 1, 0, 0, 0, 0));
   }

   private static List<Case> cases() {
      RetrievalScope all = RetrievalScope.everything();
      Set<MemoryKind> facts = EnumSet.of(MemoryKind.FACT);
      float[] east = unit(1, 0);

      return List.of(
            new Case("relevance-only", "the shipped default: plain cosine, everything else off",
                  east, MemoryScorer.Weights.MEASURED, MemoryScorer.DEFAULT_HALF_LIFE_HOURS,
                  10, 2, all.withKinds(facts)),
            new Case("park-baseline", "the literature baseline, reproduced exactly",
                  east, MemoryScorer.Weights.PARK, MemoryScorer.PARK_HALF_LIFE_HOURS,
                  10, 2, all.withKinds(facts)),
            new Case("all-four-terms", "every term weighted, including salience",
                  east, new MemoryScorer.Weights(1, 1, 1, 1), MemoryScorer.DEFAULT_HALF_LIFE_HOURS,
                  10, 2, all.withKinds(facts)),
            new Case("tie-break-on-serial",
                  "m01 and m02 are identical to the scorer except serial; the newer must win",
                  east, MemoryScorer.Weights.RELEVANCE_ONLY, MemoryScorer.DEFAULT_HALF_LIFE_HOURS,
                  4, 1, all.withKinds(facts)),
            new Case("degenerate-minmax",
                  "k=1 leaves one candidate, so every term normalises to zero and the score is 0",
                  east, MemoryScorer.Weights.PARK, MemoryScorer.DEFAULT_HALF_LIFE_HOURS,
                  1, 1, all.withKinds(facts)),
            new Case("recency-park-halflife",
                  "at 5.8 days the term underflows for nearly everything — the pathology, pinned",
                  east, new MemoryScorer.Weights(1, 1, 0, 0), MemoryScorer.PARK_HALF_LIFE_HOURS,
                  6, 2, all.withKinds(facts)),
            new Case("recency-91-days", "the default half-life, where the term is a real curve",
                  east, new MemoryScorer.Weights(1, 1, 0, 0), MemoryScorer.DEFAULT_HALF_LIFE_HOURS,
                  6, 2, all.withKinds(facts)),
            new Case("scope-companion-lena",
                  "lena may see her own fiction and nobody else's",
                  east, MemoryScorer.Weights.MEASURED, MemoryScorer.DEFAULT_HALF_LIFE_HOURS,
                  20, 2, RetrievalScope.forCompanion("lena").withKinds(facts)),
            new Case("scope-agnostic-sees-no-fiction",
                  "an asker who is nobody must reach no IN_WORLD memory at all",
                  east, MemoryScorer.Weights.MEASURED, MemoryScorer.DEFAULT_HALF_LIFE_HOURS,
                  20, 2, all.withKinds(facts)),
            new Case("scope-owner-partition", "another person's conversations are excluded",
                  east, MemoryScorer.Weights.MEASURED, MemoryScorer.DEFAULT_HALF_LIFE_HOURS,
                  20, 2, all.withPartition(Partition.OWNER).withKinds(facts)),
            new Case("scope-superseded", "'was true until March' is returned only when asked for",
                  east, MemoryScorer.Weights.MEASURED, MemoryScorer.DEFAULT_HALF_LIFE_HOURS,
                  20, 2, all.withSuperseded(true).withKinds(facts)),
            new Case("scope-all-kinds", "no kind filter: the observation becomes reachable",
                  east, MemoryScorer.Weights.MEASURED, MemoryScorer.DEFAULT_HALF_LIFE_HOURS,
                  20, 2, all),
            new Case("multiplier-1", "a narrow candidate set: the other terms never get a vote",
                  east, MemoryScorer.Weights.PARK, MemoryScorer.DEFAULT_HALF_LIFE_HOURS,
                  3, 1, all.withKinds(facts)),
            new Case("multiplier-4", "a wide one: same k, different answer",
                  east, MemoryScorer.Weights.PARK, MemoryScorer.DEFAULT_HALF_LIFE_HOURS,
                  3, 4, all.withKinds(facts)),
            new Case("k-exceeds-candidates", "asking for more than exists is not an error",
                  east, MemoryScorer.Weights.MEASURED, MemoryScorer.DEFAULT_HALF_LIFE_HOURS,
                  500, 2, all.withKinds(facts)),
            new Case("int8-rounding-halves",
                  "components landing on exactly ±0.5 before rounding",
                  raw(1.0f, 0.5f / 127f, 0, 0, 0, 0, 0, 0), MemoryScorer.Weights.MEASURED,
                  MemoryScorer.DEFAULT_HALF_LIFE_HOURS, 5, 2, all.withKinds(facts)),
            new Case("non-unit-query", "a query that is not unit length must be normalised first",
                  raw(3.0f, 4.0f, 0, 0, 0, 0, 0, 0), MemoryScorer.Weights.MEASURED,
                  MemoryScorer.DEFAULT_HALF_LIFE_HOURS, 5, 2, all.withKinds(facts)),
            new Case("dense-vectors",
                  "every component non-zero and inexact: exercises float32 rounding in unitised "
                        + "and accumulation order in the exact rescore",
                  raw(0.30901699f, -0.25881905f, 0.70710678f, -0.86602540f,
                        0.38268343f, 0.92387953f, -0.13052619f, 0.49999999f),
                  new MemoryScorer.Weights(1, 1, 1, 1), MemoryScorer.DEFAULT_HALF_LIFE_HOURS,
                  8, 3, all.withKinds(facts)),
            new Case("dense-vectors-relevance-only",
                  "the same vectors under plain cosine, where nothing masks a precision drift",
                  raw(0.30901699f, -0.25881905f, 0.70710678f, -0.86602540f,
                        0.38268343f, 0.92387953f, -0.13052619f, 0.49999999f),
                  MemoryScorer.Weights.MEASURED, MemoryScorer.DEFAULT_HALF_LIFE_HOURS,
                  8, 3, all.withKinds(facts)),
            new Case("narrow-importance-spread",
                  "importance is float in Java and double in JS; min-max amplifies the gap by "
                        + "1/(hi-lo) and this spread is small enough for it to matter",
                  raw(0, 0, 1, 0, 0, 0, 0, 0), new MemoryScorer.Weights(1, 0, 1, 0),
                  MemoryScorer.DEFAULT_HALF_LIFE_HOURS, 3, 1,
                  all.withKinds(facts)),
            new Case("empty-result", "a scope admitting nothing returns nothing, not an error",
                  east, MemoryScorer.Weights.MEASURED, MemoryScorer.DEFAULT_HALF_LIFE_HOURS,
                  10, 2, RetrievalScope.forCompanion("nobody")
                        .withKinds(EnumSet.of(MemoryKind.REFLECTION))),
            new Case("world-scope-a",
                  "a caller in world-a sees world-a's memories and the player's, never world-b's",
                  raw(0, 0, 0, 1, 0, 0, 0, 0), MemoryScorer.Weights.MEASURED,
                  MemoryScorer.DEFAULT_HALF_LIFE_HOURS, 10, 2,
                  RetrievalScope.forCompanionInWorld("vetta", "world-a")),
            new Case("world-scope-b",
                  "the same query from world-b returns a disjoint set of world memories",
                  raw(0, 0, 0, 1, 0, 0, 0, 0), MemoryScorer.Weights.MEASURED,
                  MemoryScorer.DEFAULT_HALF_LIFE_HOURS, 10, 2,
                  RetrievalScope.forCompanionInWorld("vetta", "world-b")),
            new Case("world-scope-none",
                  "no world -- the browser -- reaches person memories only, never a save's",
                  raw(0, 0, 0, 1, 0, 0, 0, 0), MemoryScorer.Weights.MEASURED,
                  MemoryScorer.DEFAULT_HALF_LIFE_HOURS, 10, 2,
                  RetrievalScope.forCompanion("vetta")));
   }

   // --- helpers ----------------------------------------------------------------------------

   private static MemoryRecord.Builder b(String id, long serial, String text, float importance,
         int topicDays, Instant lastSeen) {
      return MemoryRecord.builder()
            .id(id).serial(serial).text("fixture: " + text)
            .entity("fixture:" + id).value(text)
            .kind(MemoryKind.FACT)
            .validFrom(EPOCH_START).recordedAt(EPOCH_START).lastSeenAt(lastSeen)
            .occurrences(1).topicDays(topicDays)
            .importance(importance).importanceSource(ImportanceSource.EXTRACTION)
            .context(MemoryContext.CHAT).partition(Partition.OWNER).frame(MemoryFrame.REAL)
            // Person-scoped unless a case says otherwise: every pre-existing fixture record is a
            // claim about the player with no world attached, which is exactly PERSON.
            .aboutPerson()
            .provenance(new Provenance("fixture", "chat-" + id, "turn-" + id,
                  EPOCH_START.toEpochMilli()));
   }

   /** Assigns the next vector row, so a record always knows where its embedding is. */
   private static void add(List<MemoryRecord> records, List<float[]> vectors,
         MemoryRecord.Builder rec, float[] v) {
      records.add(rec.vectorRow(vectors.size()).build());
      vectors.add(v);
   }

   private static Instant days(int d) {
      return NOW.minusSeconds(d * 86_400L);
   }

   private static Instant hours(int h) {
      return NOW.minusSeconds(h * 3600L);
   }

   private static float[] unit(float x, float y) {
      return raw(x, y, 0, 0, 0, 0, 0, 0);
   }

   private static float[] raw(float... v) {
      float[] out = new float[DIM];
      System.arraycopy(v, 0, out, 0, Math.min(v.length, DIM));
      return out;
   }

   private static float[] toFloats(byte[] raw) {
      float[] out = new float[raw.length / 4];
      ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
      return out;
   }

   private static JsonArray floats(float[] v) {
      JsonArray a = new JsonArray();
      for (float f : v) {
         a.add(f);
      }
      return a;
   }

   private static double round(double v) {
      double scale = Math.pow(10, MemoryScorer.SCORE_PRECISION);
      return Math.round(v * scale) / scale;
   }
}
