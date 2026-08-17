// The cross-language contract: this implementation must reproduce, exactly, what the Java engine
// recorded in ../fixture/cases.json.
//
// A failure here is not a JS bug by default — it is a *disagreement*, and either side could be
// wrong. Read the case's `why` field first; the cases were chosen so that each one fails for a
// single identifiable reason.
//
// Regenerate the expectations with `cd engine && ./gradlew -q fixture`. Doing so moves the
// goalposts for both implementations at once and should be a deliberate act, not a way to make a
// red test green.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

import { MemoryScorer, weights, SCORE_PRECISION } from '../src/memoryScorer.js';
import { VectorIndex } from '../src/vectorIndex.js';
import { MemoryRetriever } from '../src/memoryRetriever.js';
import { RetrievalScope } from '../src/retrievalScope.js';

const FIXTURE = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'fixture');
const SCALE = Math.pow(10, SCORE_PRECISION);

function loadFixture() {
   const cases = JSON.parse(readFileSync(join(FIXTURE, 'cases.json'), 'utf8'));
   const records = readFileSync(join(FIXTURE, 'memories.jsonl'), 'utf8')
      .split('\n')
      .filter((l) => l.trim())
      .map((l) => JSON.parse(l));

   // Node's Buffer is a view into a larger pooled ArrayBuffer, so handing .buffer straight to a
   // TypedArray reads whatever else is in the pool. Slice to this file's bytes only.
   const bytes = (name) => {
      const b = readFileSync(join(FIXTURE, name));
      return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength);
   };

   const index = VectorIndex.fromBuffers(
      { count: cases.vectorCount, dim: cases.dim },
      bytes('embeddings.i8'),
      bytes('embeddings.f32'));
   return { cases, records, index };
}

function scopeOf(s) {
   return new RetrievalScope(
      s.companionId ?? null,
      s.worldId ?? null,
      s.partition ?? null,
      Boolean(s.includeSuperseded),
      s.kinds ? new Set(s.kinds) : null);
}

const round = (v) => Math.round(v * SCALE) / SCALE;

const { cases, records, index } = loadFixture();

test('fixture describes the corpus it ships with', () => {
   assert.equal(records.length, cases.count);
   assert.equal(index.count, cases.vectorCount);
   assert.equal(index.dim, cases.dim);
   assert.ok(cases.cases.length > 0, 'no cases to run');
   // One record is deliberately un-embedded. If that stops being true the fixture has lost a
   // branch and nobody would notice from the passing tests.
   assert.equal(records.filter((r) => r.vectorRow < 0).length, cases.count - cases.vectorCount);
});

for (const c of cases.cases) {
   test(`${c.name} — ${c.why}`, () => {
      const scorer = new MemoryScorer(
         weights(c.weights.relevance, c.weights.recency, c.weights.importance, c.weights.salience),
         c.halfLifeHours);
      const retriever = new MemoryRetriever(records, index, scorer);
      const hits = retriever.retrieve(
         c.query, cases.nowMillis, c.k, scopeOf(c.scope), c.candidateMultiplier);

      assert.deepEqual(
         hits.map((h) => h.id),
         c.expected.map((e) => e.id),
         'ranking differs from the Java engine');

      // Order agreeing while the arithmetic differs would mean the two implementations happen to
      // sort the same way today and would diverge on the next corpus. Check the terms too.
      for (let i = 0; i < hits.length; i++) {
         const got = hits[i];
         const want = c.expected[i];
         for (const term of ['score', 'cosine', 'relevance', 'recency', 'importance', 'salience']) {
            assert.equal(round(got[term]), want[term],
               `${want.id} rank ${i + 1}: ${term} ${round(got[term])} != ${want[term]}`);
         }
      }
   });
}
