package com.neovetta.aicompanion.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reading and writing the corpus record file.
 *
 * <p>One JSON object per line. JSONL rather than a single array so the file streams, appends, and
 * survives a truncated write with only the last record lost — the corpus is rebuilt by a pipeline
 * that may be interrupted.
 *
 * <p>Instants go on the wire as epoch <b>milliseconds</b>. The same corpus has to be read by the
 * Java engine and a JS implementation that must agree with it exactly; an integer needs no parsing,
 * carries no timezone, has no format to disagree about, and compares identically in both. ISO-8601
 * would be friendlier to read and is the wrong trade here.
 *
 * <p>Vectors are not part of this file. They live in packed binary sidecars addressed by
 * {@link MemoryRecord#vectorRow()}.
 */
public final class MemoryCodec {

   private static final Gson GSON = new GsonBuilder()
         .registerTypeAdapter(Instant.class, new EpochMillisInstantAdapter().nullSafe())
         .create();

   private MemoryCodec() {
   }

   /** Configured Gson. Shared so every path uses the same Instant representation. */
   public static Gson gson() {
      return GSON;
   }

   public static String toJson(MemoryRecord record) {
      return GSON.toJson(record);
   }

   public static MemoryRecord fromJson(String json) {
      return GSON.fromJson(json, MemoryRecord.class);
   }

   public static void writeJsonl(Path file, List<MemoryRecord> records) throws IOException {
      Path parent = file.getParent();
      if (parent != null) {
         Files.createDirectories(parent);
      }
      try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
         for (MemoryRecord r : records) {
            w.write(GSON.toJson(r));
            w.newLine();
         }
      }
   }

   /**
    * Reads a corpus file.
    *
    * <p>A malformed line fails the whole read rather than being skipped. A corpus that silently
    * lost records would produce eval numbers that look fine and mean nothing, and the loss would
    * not show up until some later result refused to reproduce.
    */
   public static List<MemoryRecord> readJsonl(Path file) throws IOException {
      List<MemoryRecord> out = new ArrayList<>();
      try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
         String line;
         int lineNo = 0;
         while ((line = r.readLine()) != null) {
            lineNo++;
            if (line.isBlank()) {
               continue;
            }
            try {
               out.add(GSON.fromJson(line, MemoryRecord.class));
            } catch (RuntimeException e) {
               throw new IOException(file + ":" + lineNo + " — " + e.getMessage(), e);
            }
         }
      }
      return out;
   }

   /** Epoch millis on the wire; {@link Instant} in memory. */
   private static final class EpochMillisInstantAdapter extends TypeAdapter<Instant> {
      @Override
      public void write(JsonWriter out, Instant value) throws IOException {
         out.value(value.toEpochMilli());
      }

      @Override
      public Instant read(JsonReader in) throws IOException {
         return Instant.ofEpochMilli(in.nextLong());
      }
   }
}
