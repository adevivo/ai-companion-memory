// The JS half of the memory engine. Its Java twin is engine/src/main/java/.../memory/.
//
// ⚠️ These two implementations must return identical rankings for identical input. A player who
// is told different things by the same companion in the mod and in the browser has caught the
// product lying, and nothing in normal use would surface it. The contract is enforced by
// ../fixture and checked by `npm test` here and by `./gradlew test` there.
//
// Deliberately dependency-free and free of any I/O: the engine is handed plaintext records and a
// query vector and returns a ranking. Decryption happens before it; embedding happens outside it.

export {
   MemoryScorer,
   minMax,
   weights,
   PARK,
   RELEVANCE_ONLY,
   MEASURED,
   PARK_HALF_LIFE_HOURS,
   DEFAULT_HALF_LIFE_HOURS,
   SCORE_PRECISION,
} from './memoryScorer.js';
export { VectorIndex } from './vectorIndex.js';
export { MemoryRetriever, DEFAULT_CANDIDATE_MULTIPLIER } from './memoryRetriever.js';
export { RetrievalScope, Partition, MemoryKind, MemoryFrame } from './retrievalScope.js';
