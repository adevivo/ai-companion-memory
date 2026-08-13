// The packed embedding sidecars, and the scan over them. Port of VectorIndex.java.
//
// Brute force, deliberately: 1k-20k memories, a full int8 scan is a millisecond, and an ANN index
// would add a second copy of the data to keep in step in exchange for time nobody is spending.
//
// Format is fixed by embed.py and read identically by both languages: row-major, row i at offset
// i*dim, little-endian, unit-normalised at write time so cosine is a plain dot product and int8
// needs one global scale of 127 rather than a per-row factor.

/**
 * ⚠️ PARITY (unverified by the fixture): accumulate in double, reading float32 components.
 *
 * Java spells this `sum += (double) f32[i] * unitQuery[i]`, promoting both float operands to
 * double before multiplying. A Float32Array read in JS is already a double holding a
 * float-representable value, so the two compute bit-identical products and sums — provided the
 * iteration order matches, which is why this is a plain ascending loop and not a reduce, a
 * pairwise sum, or anything else a clever optimiser might reorder.
 *
 * Measured honestly: the synthetic fixture does NOT catch a reversed accumulation order, even
 * with dense vectors, because 8 components cannot drift far enough to move a 6-decimal
 * comparison. Only the real corpus at 768 dimensions can. See docs/cross-language.md.
 */
export class VectorIndex {
   /**
    * @param {number} count rows
    * @param {number} dim components per row
    * @param {Int8Array} i8
    * @param {Float32Array} f32
    */
   constructor(count, dim, i8, f32) {
      if (i8.length !== count * dim) {
         throw new Error(`vectors.i8 has ${i8.length} bytes, manifest implies ${count * dim}`
            + ' — re-run embed.py');
      }
      if (f32.length !== count * dim) {
         throw new Error(`vectors.f32 has ${f32.length} floats, manifest implies ${count * dim}`
            + ' — re-run embed.py');
      }
      this.count = count;
      this.dim = dim;
      this.i8 = i8;
      this.f32 = f32;
   }

   /**
    * Builds from a manifest object and the two sidecars as ArrayBuffers.
    *
    * Kept separate from any file or network access so the same code runs in a browser (fetch), in
    * Node (fs), and in a test (a literal buffer). The engine never decides where bytes come from.
    */
   static fromBuffers(manifest, i8Buffer, f32Buffer) {
      const { count, dim } = manifest;
      const i8 = new Int8Array(i8Buffer);
      // ⚠️ PARITY: the file is little-endian by contract. Float32Array uses platform endianness,
      // which is little-endian everywhere this ships — but reading through a DataView makes the
      // contract explicit and costs nothing at load time.
      const view = new DataView(f32Buffer);
      const f32 = new Float32Array(f32Buffer.byteLength / 4);
      for (let i = 0; i < f32.length; i++) f32[i] = view.getFloat32(i * 4, true);
      return new VectorIndex(count, dim, i8, f32);
   }

   /** Scales a unit vector to int8 exactly as embed.py does. */
   static quantise(unitVector) {
      const q = new Int8Array(unitVector.length);
      for (let i = 0; i < unitVector.length; i++) {
         const v = Math.round(unitVector[i] * 127.0);
         q[i] = Math.max(-127, Math.min(127, v));
      }
      return q;
   }

   /**
    * A copy scaled to unit length. Zero vectors are returned as-is.
    *
    * ⚠️ PARITY (unverified by the fixture): Math.fround on the result. Java's `unitised` returns
    * a float[], so every component is rounded to float32 precision on the way out; without fround
    * the JS vector carries extra bits Java never had, and the two can quantise differently at
    * exact halves and accumulate differently in the exact rescore.
    *
    * The fixture does not catch its removal. That is a limit of an 8-dimension fixture, not
    * evidence the cast is unnecessary — at 768 dimensions the extra bits have somewhere to go.
    */
   static unitised(v) {
      let sum = 0;
      for (const x of v) sum += x * x;
      const n = Math.sqrt(sum);
      const out = new Float32Array(v.length);
      if (n === 0) {
         out.set(v);
         return out;
      }
      for (let i = 0; i < v.length; i++) out[i] = Math.fround(v[i] / n);
      return out;
   }

   /** Exact cosine of one row against a unit query, accumulated in double. */
   cosine(row, unitQuery) {
      if (unitQuery.length !== this.dim) {
         throw new Error(`query has ${unitQuery.length} dims, index has ${this.dim}`);
      }
      const base = row * this.dim;
      let sum = 0;
      for (let i = 0; i < this.dim; i++) sum += this.f32[base + i] * unitQuery[i];
      return sum;
   }

   /**
    * The n highest-scoring rows under the int8 scan, best first.
    *
    * Ties break toward the lower row index — arbitrary, but it has to be decided somewhere or two
    * runs on the same data can disagree.
    *
    * ⚠️ PARITY: Java uses a bounded min-heap and this uses a full sort. That is allowed, and only
    * because both select the top n under the *same total order*: score desc, then row asc. A
    * partial order would let the two implementations return different sets of equal-scoring rows.
    *
    * @param {Int8Array} query
    * @param {number} n
    * @param {(row: number) => boolean} allowed scope filter, applied during the scan
    */
   scan(query, n, allowed) {
      if (query.length !== this.dim) {
         throw new Error(`query has ${query.length} dims, index has ${this.dim}`);
      }
      if (n <= 0) return [];
      const rows = [];
      for (let row = 0; row < this.count; row++) {
         if (!allowed(row)) continue;
         const base = row * this.dim;
         let dot = 0;
         for (let i = 0; i < this.dim; i++) dot += this.i8[base + i] * query[i];
         rows.push({ row, dot });
      }
      rows.sort((a, b) => (a.dot !== b.dot ? b.dot - a.dot : a.row - b.row));
      return rows.slice(0, n).map((r) => r.row);
   }
}
