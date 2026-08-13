package com.neovetta.aicompanion.memory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads a corpus file through the real schema and reports what is in it.
 *
 * <p>The corpus is produced by a Python harness while entity schemes are still being compared, but
 * {@link MemoryRecord} is the authority on what a memory is. Anything the harness emits has to
 * survive being read back through the canonical constructor, which is where validation lives — so
 * running this is what makes "the extractor produced a valid corpus" a checked claim rather than an
 * assumption.
 *
 * <p>Exits non-zero on a failed invariant so it can gate a pipeline run.
 *
 * <pre>./gradlew -q run --args /path/to/records.jsonl</pre>
 */
public final class CorpusValidator {

   private CorpusValidator() {
   }

   public static void main(String[] args) throws IOException {
      if (args.length < 1) {
         System.err.println("usage: CorpusValidator <records.jsonl>");
         System.exit(2);
      }
      Path file = Path.of(args[0]);
      List<MemoryRecord> records = MemoryCodec.readJsonl(file);
      if (records.isEmpty()) {
         System.err.println("FAIL: corpus is empty");
         System.exit(1);
      }

      List<String> problems = new ArrayList<>();

      Set<String> ids = new HashSet<>();
      Set<Long> serials = new HashSet<>();
      Map<String, Integer> byEntity = new TreeMap<>();
      Map<String, Integer> byPartition = new TreeMap<>();
      Map<String, Integer> byContext = new TreeMap<>();
      Map<String, Integer> byKind = new TreeMap<>();
      Instant earliest = null;
      Instant latest = null;
      int believed = 0;
      int current = 0;
      int embedded = 0;
      double importanceSum = 0;

      for (MemoryRecord r : records) {
         if (!ids.add(r.id())) {
            problems.add("duplicate id: " + r.id());
         }
         if (!serials.add(r.serial())) {
            problems.add("duplicate serial: " + r.serial());
         }
         byEntity.merge(r.entity(), 1, Integer::sum);
         byPartition.merge(r.partition().name(), 1, Integer::sum);
         byContext.merge(r.context().name(), 1, Integer::sum);
         byKind.merge(r.kind().name(), 1, Integer::sum);

         if (earliest == null || r.recordedAt().isBefore(earliest)) {
            earliest = r.recordedAt();
         }
         if (latest == null || r.recordedAt().isAfter(latest)) {
            latest = r.recordedAt();
         }
         if (r.isBelieved()) {
            believed++;
         }
         if (r.isCurrent()) {
            current++;
         }
         if (r.hasVector()) {
            embedded++;
         }
         importanceSum += r.importance();
      }

      long multi = byEntity.values().stream().filter(n -> n > 1).count();
      int inMulti = byEntity.values().stream().filter(n -> n > 1).mapToInt(Integer::intValue).sum();

      System.out.println("corpus     : " + file);
      System.out.println("records    : " + records.size());
      System.out.println("entities   : " + byEntity.size()
            + "  (" + multi + " with >1 record, covering " + inMulti + " records)");
      System.out.println("partitions : " + byPartition);
      System.out.println("contexts   : " + byContext);
      System.out.println("kinds      : " + byKind);
      System.out.println("believed   : " + believed + "   current: " + current);
      System.out.println("embedded   : " + embedded + "/" + records.size());
      System.out.printf("importance : mean %.3f%n", importanceSum / records.size());
      System.out.println("recordedAt : " + earliest + "  ->  " + latest);

      // Contradiction resolution has to be able to run over whatever the extractor produced.
      Map<String, MemoryRecord> winners = ContradictionResolver.resolveAll(records);
      System.out.println("resolved   : " + winners.size() + " prevailing memories");

      if (problems.isEmpty()) {
         System.out.println("\nOK — every record parsed and validated through MemoryRecord");
      } else {
         System.out.println("\nFAIL — " + problems.size() + " problem(s):");
         problems.stream().limit(20).forEach(p -> System.out.println("  " + p));
         System.exit(1);
      }
   }
}
