package com.neovetta.aicompanion.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the scorer over a batch of pre-embedded queries and writes the rankings.
 *
 * <p>The engine owns the scoring; the Python harness owns the embedder and the analysis. This is the
 * seam between them, and it is a file rather than a service because an eval run has to be
 * reproducible: the same queries file and the same corpus produce a byte-identical rankings file,
 * which can be diffed against the run from before a weight changed.
 *
 * <p><b>No memory text is written out.</b> Results carry record ids and the score breakdown only.
 * The corpus is real private conversation and must not end up in an analysis artifact that looks
 * safe to paste somewhere; the harness can join ids back to text locally when a human needs to read
 * them.
 *
 * <pre>
 * ./gradlew -q retrieve --args "../corpus ../corpus/queries.jsonl --k 10 --out ../corpus/rankings.jsonl"
 * </pre>
 *
 * Options:
 * <pre>
 *   --k N                 results per query (default 10)
 *   --multiplier N        candidates scanned per result before the exact rescore (default 2)
 *   --half-life H         recency half-life in hours (default 91 days; inert at weight 0)
 *   --weights r,rec,i,s   relevance,recency,importance,salience (default 1,0,0,0 — measured)
 *                         Park's baseline is --weights 1,1,1,0 --half-life 138.2825
 *   --companion ID        answer as this companion; unlocks its IN_WORLD memories
 *   --companion-from-query take the asker from each query's own companionId field
 *   --partition P         OWNER or DISTRACTOR; default is both, which is what an eval wants
 *   --kinds K,K           FACT / OBSERVATION / REFLECTION; default all
 *   --superseded          include memories whose valid time has closed
 *   --now MILLIS          query time; defaults to the wall clock
 *   --out PATH            write JSONL here instead of stdout
 * </pre>
 *
 * <p>The queries file is JSONL of {@code {"id": "...", "vector": [floats]}}; any other fields are
 * carried through to the output untouched, so the harness can label a query with what it is testing
 * and get that label back in the results.
 */
public final class Retrieve {

   private Retrieve() {
   }

   public static void main(String[] args) throws IOException {
      if (args.length < 2) {
         System.err.println("usage: Retrieve <corpusDir> <queries.jsonl> [options]");
         System.exit(2);
      }
      Path corpusDir = Path.of(args[0]);
      Path queriesFile = Path.of(args[1]);

      int k = 10;
      int multiplier = MemoryRetriever.DEFAULT_CANDIDATE_MULTIPLIER;
      // Defaults are the measured configuration, not Park's. Park lost on every metric here
      // and a caller who passes no flags should get the thing that won, not the thing the
      // literature started from. Park stays exactly reproducible: --weights 1,1,1,0
      // --half-life 138.2825.
      double halfLife = MemoryScorer.DEFAULT_HALF_LIFE_HOURS;
      MemoryScorer.Weights weights = MemoryScorer.Weights.MEASURED;
      String companion = null;
      boolean companionFromQuery = false;
      Partition partition = null;
      Set<MemoryKind> kinds = null;
      boolean superseded = false;
      Instant now = Instant.now();
      Path out = null;

      for (int i = 2; i < args.length; i++) {
         switch (args[i]) {
            case "--k" -> k = Integer.parseInt(args[++i]);
            case "--multiplier" -> multiplier = Integer.parseInt(args[++i]);
            case "--half-life" -> halfLife = Double.parseDouble(args[++i]);
            case "--weights" -> weights = parseWeights(args[++i]);
            case "--companion" -> companion = args[++i];
            case "--companion-from-query" -> companionFromQuery = true;
            case "--partition" -> partition = Partition.valueOf(args[++i].toUpperCase());
            case "--kinds" -> kinds = parseKinds(args[++i]);
            case "--superseded" -> superseded = true;
            case "--now" -> now = Instant.ofEpochMilli(Long.parseLong(args[++i]));
            case "--out" -> out = Path.of(args[++i]);
            default -> {
               System.err.println("unknown option: " + args[i]);
               System.exit(2);
            }
         }
      }

      MemoryScorer scorer = new MemoryScorer(weights, halfLife);
      MemoryRetriever retriever = MemoryRetriever.load(corpusDir, scorer);
      RetrievalScope scope = new RetrievalScope(companion, partition, superseded, kinds);

      List<JsonObject> queries = new ArrayList<>();
      try (BufferedReader r = Files.newBufferedReader(queriesFile, StandardCharsets.UTF_8)) {
         String line;
         while ((line = r.readLine()) != null) {
            if (!line.isBlank()) {
               queries.add(JsonParser.parseString(line).getAsJsonObject());
            }
         }
      }

      PrintStream err = System.err;
      err.printf("corpus     : %d records, %d vectors (dim %d)%n",
            retriever.records().size(), retriever.vectors().count(), retriever.vectors().dim());
      err.printf("scorer     : weights rel=%.3f rec=%.3f imp=%.3f sal=%.3f, half-life %.2fh%n",
            weights.relevance(), weights.recency(), weights.importance(), weights.salience(),
            halfLife);
      err.printf("scope      : companion=%s partition=%s superseded=%s%n",
            companionFromQuery ? "(per query)" : companion, partition, superseded);
      err.printf("queries    : %d, k=%d, multiplier=%d, now=%s%n",
            queries.size(), k, multiplier, now);

      long started = System.nanoTime();
      List<String> lines = new ArrayList<>(queries.size());
      for (JsonObject q : queries) {
         float[] vector = readVector(q, retriever.vectors().dim());
         // A retrieval always happens on behalf of someone. In the mod that is whichever
         // companion is speaking; in an eval it has to be stated per query, or every question
         // is asked by nobody and no in-world memory is reachable at all.
         RetrievalScope qScope = scope;
         if (companionFromQuery) {
            JsonElement asker = q.get("companionId");
            qScope = new RetrievalScope(
                  asker == null || asker.isJsonNull() ? null : asker.getAsString(),
                  partition, superseded, kinds);
         }
         List<ScoredMemory> hits = retriever.retrieve(vector, now, k, qScope, multiplier);

         JsonObject o = new JsonObject();
         for (String key : q.keySet()) {
            if (!key.equals("vector")) {
               o.add(key, q.get(key));
            }
         }
         JsonArray results = new JsonArray();
         int rank = 1;
         for (ScoredMemory s : hits) {
            JsonObject h = new JsonObject();
            h.addProperty("rank", rank++);
            h.addProperty("id", s.id());
            h.addProperty("serial", s.memory().serial());
            h.addProperty("entity", s.memory().entity());
            h.addProperty("kind", s.memory().kind().name());
            h.addProperty("frame", s.memory().frame().name());
            h.addProperty("partition", s.memory().partition().name());
            h.addProperty("topicDays", s.memory().topicDays());
            h.addProperty("occurrences", s.memory().occurrences());
            h.addProperty("lastSeenAt", s.memory().lastSeenAt().toEpochMilli());
            h.addProperty("rawImportance", s.memory().importance());
            h.addProperty("score", s.score());
            h.addProperty("cosine", s.cosine());
            h.addProperty("relevance", s.relevance());
            h.addProperty("recency", s.recency());
            h.addProperty("importance", s.importance());
            h.addProperty("salience", s.salience());
            results.add(h);
         }
         o.add("results", results);
         lines.add(MemoryCodec.gson().toJson(o));
      }
      double ms = (System.nanoTime() - started) / 1e6;
      err.printf("retrieved  : %d queries in %.0fms (%.1fms each)%n",
            queries.size(), ms, ms / Math.max(1, queries.size()));

      if (out == null) {
         lines.forEach(System.out::println);
      } else {
         try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (String l : lines) {
               w.write(l);
               w.newLine();
            }
         }
         err.println("wrote      : " + out);
      }
   }

   private static float[] readVector(JsonObject q, int dim) {
      if (!q.has("vector")) {
         throw new IllegalArgumentException("query " + q.get("id") + " has no vector — embed it first");
      }
      JsonArray a = q.getAsJsonArray("vector");
      if (a.size() != dim) {
         throw new IllegalArgumentException(
               "query " + q.get("id") + " has " + a.size() + " dims, corpus has " + dim);
      }
      float[] v = new float[a.size()];
      for (int i = 0; i < v.length; i++) {
         v[i] = a.get(i).getAsFloat();
      }
      return v;
   }

   private static Set<MemoryKind> parseKinds(String spec) {
      Set<MemoryKind> out = EnumSet.noneOf(MemoryKind.class);
      for (String s : spec.split(",")) {
         out.add(MemoryKind.valueOf(s.trim().toUpperCase()));
      }
      return out;
   }

   private static MemoryScorer.Weights parseWeights(String spec) {
      String[] p = spec.split(",");
      if (p.length != 4) {
         throw new IllegalArgumentException(
               "--weights wants relevance,recency,importance,salience — got " + spec);
      }
      return new MemoryScorer.Weights(Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim()),
            Double.parseDouble(p[2].trim()), Double.parseDouble(p[3].trim()));
   }
}
