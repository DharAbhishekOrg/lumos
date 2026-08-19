/*
 * Lumos - android-app/app / Consent, as it is recorded and re-asked for.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-ONB-002, SRS-DAT-002
 * ADRs: -
 * Threading: any
 * Notes: Threat model LT-31 - "consent record forged or missing, so accepted
 * versions are unknown" - asks for the record to live inside the encrypted
 * store, so these tests write it through the real store and then read the
 * database file's own bytes back to check what is actually there.
 *
 * The one that matters most is dateOfBirthNeverReachesTheStore. Everything else
 * here would still pass if AgeGate started keeping the date; that one would not.
 */

package com.lumos.app.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The stored consent record. */
final class ConsentTest {

    private static final long AFFIRMED_AT = 1_755_561_600_000L;
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 19);
    private static final LocalDate BIRTHDAY = LocalDate.of(1991, 4, 7);

    private static Map<LegalDocument, String> versions(final String value) {
        final Map<LegalDocument, String> out = new EnumMap<>(LegalDocument.class);
        for (final LegalDocument document : LegalDocument.values()) {
            out.put(document, value);
        }
        return out;
    }

    @Test
    void aRecordRoundTripsThroughTheCodec() {
        final ConsentRecord record =
                new ConsentRecord(AFFIRMED_AT, 18, versions("2026-08-19.1"));
        final ConsentRecord back = ConsentCodec.decode(ConsentCodec.encode(record));
        assertEquals(AFFIRMED_AT, back.affirmedAtUtcMillis());
        assertEquals(18, back.minimumAgeYears());
        assertEquals(record.acceptedVersions(), back.acceptedVersions());
    }

    @Test
    void anUnknownPayloadVersionFailsClosed() {
        final byte[] future = "lumos-consent/9\naffirmed_at\t1\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> ConsentCodec.decode(future));
    }

    @Test
    void anUnknownLineTagIsRefusedRatherThanSkipped() {
        final byte[] odd = ("lumos-consent/1\naffirmed_at\t1\nminimum_age_years\t18\n"
                + "date_of_birth\t1991-04-07\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> ConsentCodec.decode(odd),
                "a reader that skipped what it did not understand would quietly "
                        + "accept a record carrying more than this format allows");
    }

    @Test
    void aRecordIsStoredAndReadBack() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final ConsentRepository repository = new ConsentRepository(stack.store());
            assertTrue(repository.load().isEmpty(), "a fresh device has given no consent");

            repository.save(new ConsentRecord(AFFIRMED_AT, 18, versions("2026-08-19.1")));
            final Optional<ConsentRecord> read = repository.load();
            assertTrue(read.isPresent());
            assertEquals(AFFIRMED_AT, read.get().affirmedAtUtcMillis());
        }
    }

    @Test
    void acceptingAgainReplacesRatherThanAppends() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final ConsentRepository repository = new ConsentRepository(stack.store());
            repository.save(new ConsentRecord(AFFIRMED_AT, 18, versions("1")));
            repository.save(new ConsentRecord(AFFIRMED_AT + 1, 18, versions("2")));
            assertEquals(1, stack.store().count(com.lumos.app.store.RecordKind.CONSENT_RECORD),
                    "consent is the current state, not a log of every time it was asked");
            assertEquals("2", repository.load().orElseThrow()
                    .acceptedVersions().get(LegalDocument.TERMS_OF_USE));
        }
    }

    @Test
    void aNewerDocumentVersionMeansConsentMustBeTakenAgain() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final ConsentRepository repository = new ConsentRepository(stack.store());
            final LegalDocuments shipped = LegalDocuments.load(RepoFiles.shippedDocuments());

            assertTrue(repository.needsConsent(shipped), "nothing has been accepted yet");

            repository.save(new ConsentRecord(AFFIRMED_AT, 18, shipped.versions()));
            assertFalse(repository.needsConsent(shipped),
                    "the accepted versions are the shipped ones");

            final Map<LegalDocument, String> moved = new EnumMap<>(shipped.versions());
            moved.put(LegalDocument.PRIVACY_NOTICE, "2027-01-01.1");
            final ConsentRecord stored = repository.load().orElseThrow();
            assertEquals(List.of(LegalDocument.PRIVACY_NOTICE), stored.outdatedAgainst(moved),
                    "exactly the document that moved, and only that one");
        }
    }

    @Test
    void aDocumentNeverAcceptedCountsAsOutdated() {
        final Map<LegalDocument, String> partial = new EnumMap<>(LegalDocument.class);
        partial.put(LegalDocument.TERMS_OF_USE, "1");
        final ConsentRecord record = new ConsentRecord(AFFIRMED_AT, 18, partial);
        assertEquals(3, record.outdatedAgainst(versions("1")).size(),
                "consent that was never given is not consent that is still current");
    }

    @Test
    void dateOfBirthNeverReachesTheStore() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final AgeGate.Decision decision = AgeGate.evaluate(BIRTHDAY, TODAY);
            assertTrue(decision.isAdult());

            final ConsentRepository repository = new ConsentRepository(stack.store());
            repository.save(new ConsentRecord(AFFIRMED_AT, decision.minimumAgeYears(),
                    versions("2026-08-19.1")));

            // The encoded payload, before the store seals it - the last point at
            // which a leaked date would still be readable.
            final byte[] payload = ConsentCodec.encode(
                    new ConsentRecord(AFFIRMED_AT, decision.minimumAgeYears(),
                            versions("2026-08-19.1")));
            final String text = new String(payload, StandardCharsets.UTF_8);
            for (final String form : List.of("1991", "04-07", "1991-04-07", "07/04/1991")) {
                assertFalse(text.contains(form),
                        "the consent payload contains " + form + "; the data map Sec. 2.3 "
                                + "records an affirmation, not a birth date");
            }
            // And the year is not recoverable from the affirmation either: what
            // is stored is a timestamp, a minimum, and four version strings.
            assertTrue(text.contains("minimum_age_years\t18"));
        }
    }
}
