// Who is asking, and therefore which memories exist at all. Port of RetrievalScope.java.
//
// A filter, not a scoring term, and the distinction is load-bearing. A memory outside the scope
// must be unreachable, not merely unlikely: scoring an IN_WORLD memory low would still let it
// surface when nothing else matched, and the failure that produces is the worst one this system
// can make — a companion telling the player something that happened in a story as though it
// happened to them. Owner labelling put that class at 43-76% true against a 95% baseline for
// ordinary memories. See docs/frame.md.

export const Partition = Object.freeze({ OWNER: 'OWNER', DISTRACTOR: 'DISTRACTOR' });
export const MemoryKind = Object.freeze({
   OBSERVATION: 'OBSERVATION',
   FACT: 'FACT',
   REFLECTION: 'REFLECTION',
});
export const MemoryFrame = Object.freeze({ REAL: 'REAL', IN_WORLD: 'IN_WORLD' });

export class RetrievalScope {
   /**
    * @param {string|null} companionId who is speaking; null admits only agnostic memories
    * @param {string|null} partition restrict to one partition, or null for all
    * @param {boolean} includeSuperseded whether closed valid-time records are eligible
    * @param {Set<string>|null} kinds eligible kinds, or null/empty for all
    */
   constructor(companionId = null, partition = null, includeSuperseded = false, kinds = null) {
      this.companionId = companionId ?? null;
      this.partition = partition ?? null;
      this.includeSuperseded = Boolean(includeSuperseded);
      this.kinds = kinds && kinds.size > 0 ? new Set(kinds) : null;
      Object.freeze(this);
   }

   /** Everything a companion-agnostic caller may see. ⚠️ An eval affordance — see the class note. */
   static everything() {
      return new RetrievalScope(null, null, false, null);
   }

   /** What one companion may see. This is what the mod always uses: someone is always speaking. */
   static forCompanion(companionId) {
      if (!companionId) throw new Error('forCompanion needs a companionId');
      return new RetrievalScope(companionId, null, false, null);
   }

   withPartition(p) {
      return new RetrievalScope(this.companionId, p, this.includeSuperseded, this.kinds);
   }

   withSuperseded(include) {
      return new RetrievalScope(this.companionId, this.partition, include, this.kinds);
   }

   withKinds(kinds) {
      return new RetrievalScope(this.companionId, this.partition, this.includeSuperseded, kinds);
   }

   /** Whether this memory is visible to the asker at all. */
   admits(r) {
      // Retracted is gone: a record withdrawn as mistaken is not a weak candidate, it is not a
      // candidate. Superseded is merely past, and the caller may be asking about the past.
      if (r.invalidatedAt != null) return false;
      if (!this.includeSuperseded && r.validTo != null) return false;
      if (this.partition != null && r.partition !== this.partition) return false;
      if (this.kinds != null && !this.kinds.has(r.kind)) return false;
      // Covers IN_WORLD implicitly: such a memory always carries a companionId, so it can never
      // satisfy this with a null asker.
      return r.companionId == null || r.companionId === this.companionId;
   }
}
