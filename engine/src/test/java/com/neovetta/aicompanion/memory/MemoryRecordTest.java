package com.neovetta.aicompanion.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryRecordTest {

   private static final Instant MARCH = Instant.parse("2026-03-01T00:00:00Z");
   private static final Instant JUNE = Instant.parse("2026-06-01T00:00:00Z");
   private static final Instant AUGUST = Instant.parse("2026-08-01T00:00:00Z");

   @Nested
   @DisplayName("Scope — which world a memory belongs to")
   class Scope {

      @Test
      @DisplayName("a WORLD memory without a worldId is refused")
      void worldNeedsAnId() {
         IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
               () -> base().scope(MemoryScope.WORLD).build());
         assertTrue(e.getMessage().contains("worldId"), e.getMessage());
      }

      @Test
      @DisplayName("a PERSON memory carrying a worldId is refused")
      void personMustNotCarryAWorld() {
         // Not harmless: it is the signature of a world fact mis-classified as true everywhere,
         // which is exactly the mistake that leaks between saves without a symptom.
         IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
               () -> base().scope(MemoryScope.PERSON).worldId("world-a").build());
         assertTrue(e.getMessage().contains("worldId"), e.getMessage());
      }

      @Test
      @DisplayName("scope has no default — an unscoped record cannot be built")
      void scopeIsRequired() {
         assertThrows(NullPointerException.class,
               () -> MemoryRecord.builder()
                     .id("x").serial(1L).text("t").entity("e").value("v")
                     .kind(MemoryKind.FACT).validFrom(MARCH).recordedAt(MARCH)
                     .importance(0.5f).importanceSource(ImportanceSource.EXTRACTION)
                     .context(MemoryContext.CHAT).partition(Partition.OWNER)
                     .frame(MemoryFrame.REAL)
                     .provenance(new Provenance("s", "c", "m", MARCH.toEpochMilli()))
                     .build());
      }

      @Test
      @DisplayName("a place requires WORLD scope")
      void placeNeedsAWorld() {
         IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
               () -> base().aboutPerson().place(new Place("minecraft:overworld", 1, 2, 3)).build());
         assertTrue(e.getMessage().contains("WORLD"), e.getMessage());
      }

      @Test
      @DisplayName("inWorld sets both scope and id, and a place rides along")
      void inWorldRoundTrips() {
         MemoryRecord r = base().inWorld("world-a")
               .place(new Place("minecraft:overworld", 218, 79, 23)).build();
         assertEquals(MemoryScope.WORLD, r.scope());
         assertEquals("world-a", r.worldId());
         assertEquals(218, r.place().x());
         assertEquals("minecraft:overworld at 218, 79, 23", r.place().toString());
      }

      @Test
      @DisplayName("scope survives supersession, retraction and reinforcement")
      void scopeSurvivesTheWithers() {
         // These rebuild the record field by field, so a forgotten argument would silently move a
         // memory to another world — or to no world at all.
         MemoryRecord r = base().inWorld("world-a")
               .place(new Place("minecraft:overworld", 1, 2, 3)).build();
         for (MemoryRecord after : List.of(
               r.supersededAt(JUNE), r.retractedAt(JUNE), r.reinforcedAt(JUNE),
               r.withVectorRow(7))) {
            assertEquals(MemoryScope.WORLD, after.scope());
            assertEquals("world-a", after.worldId());
            assertEquals(r.place(), after.place());
         }
      }

      @Test
      @DisplayName("withPlace fills in a location learned later, and keeps everything else")
      void withPlaceKeepsTheRest() {
         MemoryRecord placeless = base().inWorld("world-a").build();
         assertEquals(null, placeless.place());
         MemoryRecord located = placeless.withPlace(new Place("minecraft:overworld", 219, 80, 22));
         assertEquals(219, located.place().x());
         assertEquals("world-a", located.worldId());
         assertEquals(MemoryScope.WORLD, located.scope());
         assertEquals(placeless.id(), located.id());
         assertEquals(placeless.occurrences(), located.occurrences());
      }

      @Test
      @DisplayName("withPlace refuses a person-scoped memory, like the constructor does")
      void withPlaceRejectsPerson() {
         assertThrows(IllegalArgumentException.class,
               () -> base().aboutPerson().build()
                     .withPlace(new Place("minecraft:overworld", 1, 2, 3)));
      }

      @Test
      @DisplayName("a dimension is required, because (0,64,0) is two different places")
      void dimensionRequired() {
         assertThrows(NullPointerException.class, () -> new Place(null, 0, 0, 0));
         assertThrows(IllegalArgumentException.class, () -> new Place("  ", 0, 0, 0));
      }
   }

   private static MemoryRecord.Builder base() {
      return MemoryRecord.builder()
            .id("mem-1")
            .serial(1L)
            .text("favourite biome is desert")
            .entity("player:favourite_biome")
            .value("desert")
            .kind(MemoryKind.FACT)
            .validFrom(MARCH)
            .recordedAt(MARCH)
            .importance(0.5f)
            .importanceSource(ImportanceSource.EXTRACTION)
            .aboutPerson()
            .context(MemoryContext.CHAT)
            .partition(Partition.OWNER)
            .frame(MemoryFrame.REAL)
            .provenance(new Provenance("openwebui:grendel", "chat-a", "msg-a", MARCH.toEpochMilli()));
   }

   @Nested
   @DisplayName("resolution folds confirmations")
   class Folding {

      private MemoryRecord at(long serial, String value, Instant when) {
         return base().id("m" + serial).serial(serial).value(value)
               .validFrom(MARCH).lastSeenAt(when).build();
      }

      @Test
      @DisplayName("the same claim on three days resolves to one memory seen three times")
      void confirmationsAccumulate() {
         MemoryRecord w = ContradictionResolver.resolve(List.of(
               at(1, "desert", MARCH), at(2, "desert", JUNE), at(3, "desert", AUGUST))).orElseThrow();
         assertEquals(3, w.occurrences());
         assertEquals(AUGUST, w.lastSeenAt());
         assertEquals(3L, w.serial(), "highest serial still wins");
      }

      @Test
      @DisplayName("a different value is a contradiction and contributes nothing")
      void contradictionDoesNotAccumulate() {
         MemoryRecord w = ContradictionResolver.resolve(List.of(
               at(1, "desert", MARCH), at(2, "desert", JUNE), at(3, "mesa", AUGUST))).orElseThrow();
         assertEquals("mesa", w.value());
         assertEquals(1, w.occurrences(), "the two desert sightings do not support mesa");
      }

      @Test
      @DisplayName("same-day repeats do not inflate the count")
      void sameDayNotDoubleCounted() {
         MemoryRecord w = ContradictionResolver.resolve(List.of(
               at(1, "desert", MARCH), at(2, "desert", MARCH.plusSeconds(600)))).orElseThrow();
         assertEquals(1, w.occurrences());
      }

      @Test
      @DisplayName("a retracted confirmation stops counting")
      void retractedDoesNotSupport() {
         MemoryRecord w = ContradictionResolver.resolve(List.of(
               at(1, "desert", MARCH).retractedAt(JUNE), at(2, "desert", AUGUST))).orElseThrow();
         assertEquals(1, w.occurrences());
      }

      @Test
      @DisplayName("topic recurrence survives a change of mind")
      void topicDaysOutliveSupersession() {
         // "desert" on two days, then "mesa": the claim changed, but the subject has still been
         // discussed on three separate days and that is what topicDays is for.
         MemoryRecord w = ContradictionResolver.resolve(List.of(
               at(1, "desert", MARCH), at(2, "desert", JUNE), at(3, "mesa", AUGUST))).orElseThrow();
         assertEquals("mesa", w.value());
         assertEquals(1, w.occurrences(), "mesa itself was stated once");
         assertEquals(3, w.topicDays(), "the subject came up on three days");
      }

      @Test
      @DisplayName("a carried-forward topic count is never revised downwards")
      void topicDaysAreAFloor() {
         MemoryRecord carried = base().id("c").serial(9).value("mesa")
               .lastSeenAt(AUGUST).occurrences(1).topicDays(30).build();
         assertEquals(30, ContradictionResolver.resolve(List.of(carried)).orElseThrow().topicDays());
      }

      @Test
      @DisplayName("topicDays cannot be smaller than occurrences")
      void invariantHolds() {
         assertThrows(IllegalArgumentException.class,
               () -> new MemoryRecord("x", 1L, "t", "e", "v", 5, 2, MARCH, MemoryKind.FACT,
                     MARCH, null, MARCH, null, 0.5f, ImportanceSource.EXTRACTION,
                     MemoryContext.CHAT, Partition.OWNER, MemoryFrame.REAL, null,
                     new Provenance("s", "c", "m", MARCH.toEpochMilli()),
                     MemoryScope.PERSON, null, null, MemoryRecord.NO_VECTOR));
      }

      @Test
      @DisplayName("resolveAll folds the same way as resolve")
      void batchMatchesSingle() {
         List<MemoryRecord> rs = List.of(at(1, "desert", MARCH), at(2, "desert", JUNE));
         assertEquals(ContradictionResolver.resolve(rs).orElseThrow().occurrences(),
               ContradictionResolver.resolveAll(rs).get("player:favourite_biome").occurrences());
      }
   }

   @Nested
   @DisplayName("reinforcement")
   class Reinforcement {

      @Test
      @DisplayName("a repeat on a later day is a confirmation, not a contradiction")
      void repeatCounts() {
         MemoryRecord r = base().build().reinforcedAt(JUNE);
         assertEquals(2, r.occurrences());
         assertEquals(JUNE, r.lastSeenAt());
         assertTrue(r.isCurrent(), "confirming must not close valid time");
      }

      @Test
      @DisplayName("same-day repeats move lastSeenAt but do not inflate the count")
      void sameDayDoesNotCount() {
         // Median span between identical facts in the corpus is one day: the extractor pulling
         // one statement out of adjacent turns, not the speaker returning to a subject.
         Instant later = MARCH.plusSeconds(3600);
         MemoryRecord r = base().build().reinforcedAt(later);
         assertEquals(1, r.occurrences());
         assertEquals(later, r.lastSeenAt());
      }

      @Test
      @DisplayName("lastSeenAt never moves backwards")
      void neverGoesBackwards() {
         MemoryRecord r = base().lastSeenAt(JUNE).build().reinforcedAt(MARCH);
         assertEquals(JUNE, r.lastSeenAt());
      }

      @Test
      @DisplayName("a retracted or superseded memory cannot be reinforced")
      void closedMemoriesRejectReinforcement() {
         assertThrows(IllegalStateException.class,
               () -> base().build().retractedAt(JUNE).reinforcedAt(AUGUST));
         assertThrows(IllegalStateException.class,
               () -> base().build().supersededAt(JUNE).reinforcedAt(AUGUST));
      }

      @Test
      @DisplayName("confirmation needs the same entity AND the same value")
      void confirmsComparesValue() {
         MemoryRecord desert = base().build();
         assertTrue(desert.confirms(base().id("m2").value("Desert").build()),
               "same value differing only in case is still the same claim");
         assertFalse(desert.confirms(base().id("m3").value("mesa").build()),
               "a different value is a contradiction, not a confirmation");
         assertFalse(desert.confirms(base().id("m4").entity("player:home").build()));
      }

      @Test
      @DisplayName("occurrences must be at least one, and lastSeenAt cannot precede validFrom")
      void invariants() {
         assertThrows(IllegalArgumentException.class, () -> base().occurrences(0).build());
         assertThrows(IllegalArgumentException.class,
               () -> base().validFrom(JUNE).lastSeenAt(MARCH).build());
      }

      @Test
      @DisplayName("reinforcement survives a JSON round trip")
      void roundTrips() {
         MemoryRecord r = base().build().reinforcedAt(JUNE).reinforcedAt(AUGUST);
         assertEquals(3, r.occurrences());
         assertEquals(r, MemoryCodec.fromJson(MemoryCodec.toJson(r)));
      }
   }

   @Nested
   @DisplayName("truth frame")
   class Frame {

      @Test
      @DisplayName("defaults to REAL — a plain claim about the player, told to every companion")
      void defaultsToReal() {
         assertEquals(MemoryFrame.REAL, MemoryRecord.builder()
               .id("m").serial(1L).text("t").entity("e").kind(MemoryKind.FACT)
               .validFrom(MARCH).recordedAt(MARCH).importance(0.5f)
               .context(MemoryContext.CHAT).partition(Partition.OWNER)
               .provenance(new Provenance("s", "c", "m", MARCH.toEpochMilli()))
               .aboutPerson()
               .build().frame());
      }

      @Test
      @DisplayName("an in-world memory must belong to a companion — a story needs a world")
      void inWorldRequiresCompanion() {
         IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
               () -> base().frame(MemoryFrame.IN_WORLD).companionId(null).build());
         assertTrue(e.getMessage().contains("companionId"));
      }

      @Test
      @DisplayName("in-world is fine once scoped")
      void inWorldScoped() {
         MemoryRecord r = base().frame(MemoryFrame.IN_WORLD).companionId("mia").build();
         assertEquals(MemoryFrame.IN_WORLD, r.frame());
         assertTrue(r.isCompanionScoped());
      }

      @Test
      @DisplayName("the same claim can be false as biography and true as fiction")
      void sameClaimBothFrames() {
         // "going on a date with Mia" -- discarded by the owner when filed under him, kept when
         // filed under Mia. Both records are legitimate; only the frame separates them.
         MemoryRecord asFiction = base().id("f").text("is going on a date with Adrian")
               .frame(MemoryFrame.IN_WORLD).companionId("mia").build();
         MemoryRecord asBiography = base().id("b").text("is going on a date with Mia").build();
         assertEquals(MemoryFrame.IN_WORLD, asFiction.frame());
         assertEquals(MemoryFrame.REAL, asBiography.frame());
         assertTrue(asFiction.isCompanionScoped());
         assertFalse(asBiography.isCompanionScoped());
      }

      @Test
      @DisplayName("frame survives supersede, retract and vector assignment")
      void framePreservedByCopies() {
         MemoryRecord r = base().frame(MemoryFrame.IN_WORLD).companionId("mia").build();
         assertEquals(MemoryFrame.IN_WORLD, r.supersededAt(JUNE).frame());
         assertEquals(MemoryFrame.IN_WORLD, r.retractedAt(JUNE).frame());
         assertEquals(MemoryFrame.IN_WORLD, r.withVectorRow(7).frame());
      }

      @Test
      @DisplayName("frame round-trips through JSON")
      void frameRoundTrips() {
         MemoryRecord r = base().frame(MemoryFrame.IN_WORLD).companionId("mia").build();
         String json = MemoryCodec.toJson(r);
         assertTrue(json.contains("\"frame\":\"IN_WORLD\""));
         assertEquals(r, MemoryCodec.fromJson(json));
      }
   }

   @Nested
   @DisplayName("bitemporal semantics")
   class Bitemporal {

      @Test
      @DisplayName("superseding records that the world changed, and keeps the memory believed")
      void supersede() {
         MemoryRecord was = base().build().supersededAt(JUNE);

         assertEquals(JUNE, was.validTo(), "valid time should close when the world moves on");
         assertNull(was.invalidatedAt(), "the memory was never wrong, so belief is untouched");
         assertTrue(was.isBelieved());
         assertFalse(was.isCurrent(), "it no longer describes the present");

         assertTrue(was.wasValidAt(MARCH.plusSeconds(1)), "still true during its interval");
         assertFalse(was.wasValidAt(AUGUST), "not true after the change");
         assertTrue(was.wasBelievedAt(AUGUST), "but we still believe it described March correctly");
      }

      @Test
      @DisplayName("retracting records that we were wrong, and leaves valid time alone")
      void retract() {
         MemoryRecord wrong = base().build().retractedAt(JUNE);

         assertEquals(JUNE, wrong.invalidatedAt());
         assertNull(wrong.validTo(), "the claim never held, so there is no interval to close");
         assertFalse(wrong.isBelieved());
         assertFalse(wrong.isCurrent());

         assertTrue(wrong.wasBelievedAt(MARCH.plusSeconds(1)), "we did believe it, once");
         assertFalse(wrong.wasBelievedAt(AUGUST), "and stopped");
      }

      @Test
      @DisplayName("the two outcomes are distinguishable — this is the point of the schema")
      void worldChangedIsNotSameAsWeWereWrong() {
         MemoryRecord changed = base().build().supersededAt(JUNE);
         MemoryRecord wrong = base().build().retractedAt(JUNE);

         assertNotEquals(changed, wrong);
         // A single-timeline "recency wins" model collapses these into one state. Under this
         // schema they differ in which dimension closed, and in whether belief survives.
         assertTrue(changed.isBelieved() && !wrong.isBelieved());
         assertNull(changed.invalidatedAt());
         assertNull(wrong.validTo());
      }

      @Test
      void closingTwiceIsRejected() {
         MemoryRecord r = base().build().supersededAt(JUNE);
         assertThrows(IllegalStateException.class, () -> r.supersededAt(AUGUST));

         MemoryRecord w = base().build().retractedAt(JUNE);
         assertThrows(IllegalStateException.class, () -> w.retractedAt(AUGUST));
      }
   }

   @Nested
   @DisplayName("validation")
   class Validation {

      @Test
      void importanceMustBeNormalised() {
         assertThrows(IllegalArgumentException.class, () -> base().importance(1.5f).build());
         assertThrows(IllegalArgumentException.class, () -> base().importance(-0.1f).build());
      }

      @Test
      void intervalsMustNotRunBackwards() {
         assertThrows(IllegalArgumentException.class,
               () -> base().validFrom(JUNE).validTo(MARCH).build());
         assertThrows(IllegalArgumentException.class,
               () -> base().recordedAt(JUNE).invalidatedAt(MARCH).build());
      }

      @Test
      @DisplayName("companionId is optional — player facts belong to no single character")
      void companionScopeIsOptional() {
         MemoryRecord shared = base().build();
         assertNull(shared.companionId());
         assertFalse(shared.isCompanionScoped());

         MemoryRecord lila = base().companionId("lila").build();
         assertTrue(lila.isCompanionScoped());
         assertEquals(lila, MemoryCodec.fromJson(MemoryCodec.toJson(lila)));
         assertFalse(MemoryCodec.toJson(shared).contains("companionId"),
               "an unscoped memory should not carry a null field on the wire");
      }

      @Test
      void requiredComponentsAreRequired() {
         assertThrows(NullPointerException.class, () -> base().entity(null).build());
         assertThrows(NullPointerException.class, () -> base().kind(null).build());
         assertThrows(NullPointerException.class, () -> base().provenance(null).build());
      }
   }

   @Nested
   @DisplayName("wire format")
   class Wire {

      @Test
      void roundTripsThroughJson() {
         MemoryRecord original = base().validTo(JUNE).vectorRow(42).build();
         MemoryRecord back = MemoryCodec.fromJson(MemoryCodec.toJson(original));
         assertEquals(original, back);
      }

      @Test
      @DisplayName("instants are epoch millis, so Java and JS read the same number")
      void instantsAreEpochMillis() {
         String json = MemoryCodec.toJson(base().build());
         assertTrue(json.contains("\"validFrom\":" + MARCH.toEpochMilli()),
               "expected raw epoch millis in: " + json);
         assertFalse(json.contains("2026-03-01"), "no ISO-8601 on the wire");
      }

      @Test
      void nullTemporalBoundsAreOmittedAndRestored() {
         MemoryRecord open = base().build();
         String json = MemoryCodec.toJson(open);
         assertFalse(json.contains("validTo"));
         assertFalse(json.contains("invalidatedAt"));

         MemoryRecord back = MemoryCodec.fromJson(json);
         assertNull(back.validTo());
         assertNull(back.invalidatedAt());
         assertTrue(back.isCurrent());
      }

      @Test
      void jsonlRoundTrips(@TempDir Path dir) throws IOException {
         List<MemoryRecord> records = List.of(
               base().build(),
               base().id("mem-2").serial(2L).validTo(JUNE).build(),
               base().id("mem-3").serial(3L).context(MemoryContext.GAME)
                     .kind(MemoryKind.OBSERVATION)
                     .importanceSource(ImportanceSource.UNRATED)
                     .partition(Partition.DISTRACTOR).build());

         Path file = dir.resolve("records.jsonl");
         MemoryCodec.writeJsonl(file, records);

         assertEquals(3, Files.readAllLines(file).size(), "one record per line");
         assertEquals(records, MemoryCodec.readJsonl(file));
      }

      @Test
      @DisplayName("a corrupt line fails the read rather than silently shrinking the corpus")
      void malformedLineThrows(@TempDir Path dir) throws IOException {
         Path file = dir.resolve("records.jsonl");
         MemoryCodec.writeJsonl(file, List.of(base().build()));
         Files.writeString(file, MemoryCodec.toJson(base().build()) + "\n{\"importance\":9.0}\n");

         IOException e = assertThrows(IOException.class, () -> MemoryCodec.readJsonl(file));
         assertTrue(e.getMessage().contains(":2"), "should name the offending line: " + e.getMessage());
      }
   }

   @Nested
   @DisplayName("contradiction resolution")
   class Contradictions {

      @Test
      @DisplayName("highest serial wins, not the newest timestamp")
      void maxSerialWins() {
         // Deliberately inverted: the winning record carries the OLDEST timestamp. Serial is the
         // authority, so timestamp order must not decide this.
         MemoryRecord older = base().id("a").serial(1L).recordedAt(AUGUST)
               .text("favourite biome is desert").build();
         MemoryRecord newer = base().id("b").serial(7L).recordedAt(MARCH)
               .text("favourite biome is mesa").build();

         Optional<MemoryRecord> winner = ContradictionResolver.resolve(List.of(older, newer));
         assertTrue(winner.isPresent());
         assertEquals("b", winner.get().id());
         assertEquals("favourite biome is mesa", winner.get().text());
      }

      @Test
      void orderOfCandidatesDoesNotMatter() {
         MemoryRecord a = base().id("a").serial(1L).build();
         MemoryRecord b = base().id("b").serial(9L).build();
         MemoryRecord c = base().id("c").serial(5L).build();

         assertEquals(ContradictionResolver.resolve(List.of(a, b, c)),
               ContradictionResolver.resolve(List.of(c, b, a)));
         assertEquals("b", ContradictionResolver.resolve(List.of(c, a, b)).orElseThrow().id());
      }

      @Test
      @DisplayName("a retracted memory never wins, however recent")
      void retractedIsExcluded() {
         MemoryRecord good = base().id("a").serial(1L).build();
         MemoryRecord retracted = base().id("b").serial(99L).build().retractedAt(JUNE);

         assertEquals("a", ContradictionResolver.resolve(List.of(good, retracted))
               .orElseThrow().id());
      }

      @Test
      void allRetractedYieldsNothing() {
         MemoryRecord only = base().build().retractedAt(JUNE);
         assertTrue(ContradictionResolver.resolve(List.of(only)).isEmpty());
      }

      @Test
      @DisplayName("a superseded memory still competes — 'was true until March' is a fact")
      void supersededStillCompetes() {
         MemoryRecord superseded = base().id("a").serial(5L).build().supersededAt(JUNE);
         MemoryRecord other = base().id("b").serial(2L).build();

         assertEquals("a", ContradictionResolver.resolve(List.of(superseded, other))
               .orElseThrow().id());
      }

      @Test
      void resolvesPerEntity() {
         MemoryRecord biomeOld = base().id("a").serial(1L).entity("player:biome").build();
         MemoryRecord biomeNew = base().id("b").serial(4L).entity("player:biome").build();
         MemoryRecord petName = base().id("c").serial(2L).entity("player:pet_name").build();

         Map<String, MemoryRecord> winners =
               ContradictionResolver.resolveAll(List.of(biomeOld, biomeNew, petName));

         assertEquals(2, winners.size());
         assertEquals("b", winners.get("player:biome").id());
         assertEquals("c", winners.get("player:pet_name").id());
      }
   }
}
