package com.neovetta.aicompanion.memory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntPredicate;

/**
 * The packed embedding sidecars, and the scan over them.
 *
 * <p>Brute force, deliberately. The corpus is 1k–20k memories; a full scan of 9,124 int8 vectors is
 * a millisecond, and an ANN index would add a second copy of the data to keep in step, a build step,
 * and approximate results — to save time that is not being spent. No index, no vector database.
 *
 * <h2>Two precisions, on purpose</h2>
 *
 * The scan runs in int8 and the top candidates are rescored in float32. Quantisation costs a few
 * percent of the exact ranking; taking a wider int8 candidate set and re-ranking it exactly buys
 * that back for the price of a few hundred dot products. Tuning has to happen against the quantised
 * pipeline end to end — numbers tuned on float32 alone do not transfer to what ships.
 *
 * <h2>Format</h2>
 *
 * Row-major, row {@code i} at offset {@code i*dim}, little-endian, both files. Vectors are
 * unit-normalised at write time, which is what makes cosine a plain dot product and lets int8 use
 * one global scale of 127 instead of a per-row factor — a third file for Java and JS to disagree
 * about. {@code manifest.json} carries the count, the dimension and the byte sizes, and they are
 * checked on load: a corpus rebuilt without re-embedding produces vectors that describe different
 * records, and every number computed from it would be quietly meaningless.
 *
 * <h2>Cross-language arithmetic</h2>
 *
 * The int8 scan is integer arithmetic — exact in both languages, since the accumulator stays well
 * inside the 2^31 that JS numbers represent exactly ({@code 127*127*768} is about 12.4M). The
 * float32 rescore reads float32 components and accumulates in double, which is what JS does with a
 * {@code Float32Array} and no choice in the matter; accumulating in float would make Java and JS
 * agree only by luck.
 */
public final class VectorIndex {

   private final int count;
   private final int dim;
   private final byte[] i8;
   private final float[] f32;

   VectorIndex(int count, int dim, byte[] i8, float[] f32) {
      this.count = count;
      this.dim = dim;
      this.i8 = i8;
      this.f32 = f32;
   }

   /** Reads {@code manifest.json}, {@code vectors.i8} and {@code vectors.f32} from a corpus dir. */
   public static VectorIndex load(Path corpusDir) throws IOException {
      JsonObject man = JsonParser
            .parseString(Files.readString(corpusDir.resolve("manifest.json"), StandardCharsets.UTF_8))
            .getAsJsonObject();
      int count = man.get("count").getAsInt();
      int dim = man.get("dim").getAsInt();

      byte[] i8 = Files.readAllBytes(corpusDir.resolve("vectors.i8"));
      byte[] raw = Files.readAllBytes(corpusDir.resolve("vectors.f32"));
      if (i8.length != (long) count * dim) {
         throw new IOException("vectors.i8 is " + i8.length + " bytes, manifest implies "
               + (long) count * dim + " — re-run embed.py");
      }
      if (raw.length != (long) count * dim * 4) {
         throw new IOException("vectors.f32 is " + raw.length + " bytes, manifest implies "
               + (long) count * dim * 4 + " — re-run embed.py");
      }
      float[] f32 = new float[count * dim];
      ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f32);
      return new VectorIndex(count, dim, i8, f32);
   }

   /**
    * Builds an index from vectors already in memory, in row order.
    *
    * <p><b>This is the production path, and {@link #load(Path)} is the eval one.</b> Nothing in the
    * shipped design ever reads a vector off disk in this format: the mod decrypts records into
    * memory and scores over them, so the bytes arrive as {@code float[]} from an embedder or a
    * decrypted blob, never as a packed sidecar. The sidecars exist so the evaluation harness can
    * replay a fixed corpus.
    *
    * <p>Reproduces exactly what {@code embed.py} writes and {@link #load(Path)} then reads —
    * unit-normalise, keep float32 for the rescore, quantise to int8 for the scan — so an index built
    * here and an index loaded from disk rank identically for the same inputs. That equivalence is
    * what lets the eval harness stand in for production at all, and it is pinned by a test.
    *
    * <p>The JS side has always been able to do this: its constructor is public and takes the two
    * typed arrays directly. Java's is package-private, so without this a caller outside the package
    * had to write files to score anything. Same capability, both languages.
    *
    * @param vectors one embedding per row; each is unitised here, so callers need not
    * @throws IllegalArgumentException if empty, or if the rows are not all the same width
    */
   public static VectorIndex of(java.util.List<float[]> vectors) {
      if (vectors == null || vectors.isEmpty()) {
         throw new IllegalArgumentException("Refusing to build an empty index.");
      }
      int count = vectors.size();
      int dim = vectors.get(0).length;
      if (dim == 0) {
         throw new IllegalArgumentException("Refusing to build a zero-dimension index.");
      }

      byte[] i8 = new byte[count * dim];
      float[] f32 = new float[count * dim];
      for (int row = 0; row < count; row++) {
         float[] v = vectors.get(row);
         if (v.length != dim) {
            // A ragged index would score fine for a while and then read across a row boundary.
            throw new IllegalArgumentException("Row " + row + " has " + v.length
                  + " dimensions, row 0 has " + dim + " — every vector must come from the same"
                  + " embedding model.");
         }
         float[] unit = unitised(v);
         byte[] q = quantise(unit);
         System.arraycopy(unit, 0, f32, row * dim, dim);
         System.arraycopy(q, 0, i8, row * dim, dim);
      }
      return new VectorIndex(count, dim, i8, f32);
   }

   /**
    * Writes the sidecars {@link #load(Path)} reads: {@code manifest.json}, {@code vectors.i8} and
    * {@code vectors.f32}.
    *
    * <p>The eval harness got its corpus from a Python pipeline, so nothing here ever needed to
    * write one. Production does: the mod holds a player's memories in memory, gains one, and has to
    * put the result somewhere that survives a restart. Without this, persisting a corpus means
    * re-embedding it on every load — the one cost the packed format exists to avoid.
    *
    * <p>Writes are not atomic. A crash midway leaves a torn corpus, which {@link #load(Path)} will
    * reject on the size check rather than read as valid — noisy, but not silently wrong. A caller
    * that cares should write to a temporary directory and move it into place.
    */
   public void save(Path dir) throws IOException {
      Files.createDirectories(dir);
      Files.writeString(dir.resolve("manifest.json"),
            "{\"count\":" + count + ",\"dim\":" + dim + "}", StandardCharsets.UTF_8);
      Files.write(dir.resolve("vectors.i8"), i8);

      ByteBuffer buf = ByteBuffer.allocate(f32.length * 4).order(ByteOrder.LITTLE_ENDIAN);
      // Little-endian by contract, and written through a buffer rather than a stream so the byte
      // order is stated rather than inherited from the platform. JS reads these through a DataView
      // for the same reason.
      for (float v : f32) {
         buf.putFloat(v);
      }
      Files.write(dir.resolve("vectors.f32"), buf.array());
   }

   /**
    * One row's float32 vector, unit-normalised as stored.
    *
    * <p>The index can score a row against a query but could not hand the row back, which is fine
    * for an eval harness that only ever reads a corpus. A store that <em>grows</em> one needs it:
    * appending a memory rebuilds the packed arrays, and re-deriving the existing vectors any other
    * way means re-embedding text that was already embedded.
    */
   public float[] row(int index) {
      if (index < 0 || index >= count) {
         throw new IndexOutOfBoundsException("row " + index + " of " + count);
      }
      float[] out = new float[dim];
      System.arraycopy(f32, index * dim, out, 0, dim);
      return out;
   }

   public int count() {
      return count;
   }

   public int dim() {
      return dim;
   }

   /** Scales a unit vector to int8 exactly as {@code embed.py} does. */
   public static byte[] quantise(float[] unitVector) {
      byte[] q = new byte[unitVector.length];
      for (int i = 0; i < unitVector.length; i++) {
         long v = Math.round(unitVector[i] * 127.0);
         q[i] = (byte) Math.max(-127, Math.min(127, v));
      }
      return q;
   }

   /** A copy scaled to unit length, so cosine is a dot product. Zero vectors are returned as-is. */
   public static float[] unitised(float[] v) {
      double sum = 0;
      for (float x : v) {
         sum += (double) x * x;
      }
      double n = Math.sqrt(sum);
      if (n == 0) {
         return v.clone();
      }
      float[] out = new float[v.length];
      for (int i = 0; i < v.length; i++) {
         out[i] = (float) (v[i] / n);
      }
      return out;
   }

   /** Exact cosine of one row against a unit query, accumulated in double. */
   public double cosine(int row, float[] unitQuery) {
      checkQuery(unitQuery);
      int base = row * dim;
      double sum = 0;
      for (int i = 0; i < dim; i++) {
         sum += (double) f32[base + i] * unitQuery[i];
      }
      return sum;
   }

   /**
    * The {@code n} highest-scoring rows under the int8 scan, best first.
    *
    * <p>Ties break toward the lower row index, which is the older memory — arbitrary, but it has to
    * be decided somewhere or two runs on the same data can disagree.
    *
    * @param allowed rows to consider; scope filtering happens here rather than after the scan, so a
    *     memory the caller may not see cannot displace one they can
    */
   public int[] scan(byte[] query, int n, IntPredicate allowed) {
      if (query.length != dim) {
         throw new IllegalArgumentException("query has " + query.length + " dims, index has " + dim);
      }
      if (n <= 0) {
         return new int[0];
      }
      TopRows top = new TopRows(n);
      for (int row = 0; row < count; row++) {
         if (!allowed.test(row)) {
            continue;
         }
         int base = row * dim;
         int dot = 0;
         for (int i = 0; i < dim; i++) {
            dot += i8[base + i] * query[i];
         }
         top.offer(dot, row);
      }
      return top.drainDescending();
   }

   private void checkQuery(float[] q) {
      if (q.length != dim) {
         throw new IllegalArgumentException("query has " + q.length + " dims, index has " + dim);
      }
   }

   /**
    * Bounded selection of the best {@code n} (score, row) pairs.
    *
    * <p>A min-heap rather than sorting the whole corpus: the scan is the hot loop of the read path
    * and this is the version that keeps working when the corpus is twenty thousand memories on a
    * client. "Worst" means lowest score, and among equal scores the higher row index, so eviction
    * implements the same tie rule the final ordering does.
    */
   private static final class TopRows {
      private final int[] score;
      private final int[] row;
      private final int cap;
      private int size;

      TopRows(int cap) {
         this.cap = cap;
         this.score = new int[cap];
         this.row = new int[cap];
      }

      /** True if {@code a} ranks worse than {@code b}: lower score, or equal score and later row. */
      private boolean worse(int i, int j) {
         return score[i] != score[j] ? score[i] < score[j] : row[i] > row[j];
      }

      void offer(int s, int r) {
         if (size < cap) {
            score[size] = s;
            row[size] = r;
            siftUp(size++);
            return;
         }
         if (s < score[0] || (s == score[0] && r > row[0])) {
            return;   // worse than the worst kept
         }
         score[0] = s;
         row[0] = r;
         siftDown(0);
      }

      private void siftUp(int i) {
         while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (!worse(i, parent)) {
               return;
            }
            swap(i, parent);
            i = parent;
         }
      }

      private void siftDown(int i) {
         while (true) {
            int l = 2 * i + 1;
            int r = l + 1;
            int worst = i;
            if (l < size && worse(l, worst)) {
               worst = l;
            }
            if (r < size && worse(r, worst)) {
               worst = r;
            }
            if (worst == i) {
               return;
            }
            swap(i, worst);
            i = worst;
         }
      }

      private void swap(int a, int b) {
         int s = score[a];
         score[a] = score[b];
         score[b] = s;
         int t = row[a];
         row[a] = row[b];
         row[b] = t;
      }

      int[] drainDescending() {
         int[] out = new int[size];
         for (int i = size - 1; i >= 0; i--) {
            out[i] = row[0];
            size--;
            score[0] = score[size];
            row[0] = row[size];
            siftDown(0);
         }
         return out;
      }
   }
}
