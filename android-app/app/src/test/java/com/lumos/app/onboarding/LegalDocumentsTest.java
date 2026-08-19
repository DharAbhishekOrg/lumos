/*
 * Lumos - android-app/app / The shipped documents, and their versions.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-002
 * ADRs: -
 * Threading: any
 * Notes: SRS-ONB-002 has two halves - display the CURRENT documents, and record
 * the accepted versions locally - and the first half is the one a test can lose
 * sight of. These read the real files out of docs/legal, so a document that
 * stops existing, loses its version line, or is added without being named in
 * the enum fails here rather than at onboarding on a device.
 */

package com.lumos.app.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The four documents onboarding must show. */
final class LegalDocumentsTest {

    @Test
    void srsOnb002NamesFourDocumentsAndThereAreFour() {
        // Terms of Use, Privacy Notice, Community Rules, local-radio disclosure.
        assertEquals(4, LegalDocument.values().length);
    }

    @Test
    void everyNamedDocumentIsActuallyShipped() throws IOException {
        for (final LegalDocument document : LegalDocument.values()) {
            final Path file = RepoFiles.repoRoot().resolve("docs").resolve("legal")
                    .resolve(document.assetName());
            assertTrue(Files.isRegularFile(file),
                    document + " is named in the enum but " + file + " does not exist; "
                            + "onboarding would fail to load it on a device");
        }
    }

    @Test
    void noShippedDocumentIsMissingFromTheEnum() throws IOException {
        final List<String> named = new ArrayList<>();
        for (final LegalDocument document : LegalDocument.values()) {
            named.add(document.assetName());
        }
        // third-party-notices.md ships from the same directory but is not a
        // consent document: it is the licence text ADR-001's dependencies
        // require, shown in settings rather than accepted at onboarding.
        named.add("third-party-notices.md");

        final List<String> shipped = new ArrayList<>();
        try (Stream<Path> files = Files.list(RepoFiles.repoRoot().resolve("docs")
                .resolve("legal"))) {
            files.filter(Files::isRegularFile)
                    .forEach(path -> shipped.add(path.getFileName().toString()));
        }
        for (final String file : shipped) {
            assertTrue(named.contains(file),
                    file + " ships in the APK from docs/legal but no LegalDocument names it; "
                            + "if it needs consent it must be in the enum, and if it does not "
                            + "it must be listed as an exception here");
        }
    }

    @Test
    void everyDocumentDeclaresAVersionAndTheyLoadTogether() throws IOException {
        final LegalDocuments documents = LegalDocuments.load(RepoFiles.shippedDocuments());
        assertEquals(LegalDocument.values().length, documents.versions().size());
        for (final LegalDocument document : LegalDocument.values()) {
            final String version = documents.versionOf(document);
            assertNotNull(version, document + " declares no version");
            assertFalse(version.isBlank(), document + " declares a blank version");
        }
    }

    @Test
    void aDocumentWithNoVersionLineIsARefusalNotADefault() {
        final LegalDocuments.Source broken = document ->
                new ByteArrayInputStream("# Terms\n\nNo version here.\n"
                        .getBytes(StandardCharsets.UTF_8));
        final IOException failure =
                assertThrows(IOException.class, () -> LegalDocuments.load(broken));
        assertTrue(failure.getMessage().contains("Document version"),
                "the failure must name what is missing: " + failure.getMessage());
    }

    @Test
    void aMissingDocumentIsARefusalRatherThanAThreeDocumentSet() {
        final LegalDocuments.Source partial = document -> {
            if (document == LegalDocument.COMMUNITY_RULES) {
                throw new IOException("gone");
            }
            return new ByteArrayInputStream("Document version: 1\n"
                    .getBytes(StandardCharsets.UTF_8));
        };
        assertThrows(IOException.class, () -> LegalDocuments.load(partial),
                "consent to three documents out of four is not consent");
    }

    @Test
    void theVersionIsReadFromTheDeclarationRatherThanFromProse() throws IOException {
        final LegalDocuments.Source prose = document -> new ByteArrayInputStream(
                ("# Terms\n\nWe used to have a Document version: 0.9 but not any more.\n\n"
                        + "**Document version: 7.3**\n").getBytes(StandardCharsets.UTF_8));
        assertEquals("7.3", LegalDocuments.load(prose).versionOf(LegalDocument.TERMS_OF_USE),
                "only a line that IS the declaration counts");
    }
}
