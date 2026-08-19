/*
 * Lumos - android-app/app / Reading the shipped documents' current versions.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-002
 * ADRs: -
 * Threading: any
 * Notes: The version lives in the document, on one machine-readable line, and
 * is read from the same bytes the user is shown. That is the point: a version
 * held in a Java constant beside the file could say 3 while the file said 2,
 * and the consent record would then attest to a version nobody displayed.
 *
 * The source is a seam because the two callers read from different places -
 * the app from the APK's assets, the unit tests from the repository's own
 * docs/legal - and because a document that fails to load must be a refusal
 * rather than a default. There is no fallback version. An unreadable document
 * means onboarding cannot ask for consent to it, and saying so is correct.
 */

package com.lumos.app.onboarding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads the shipped legal documents and the version each declares. */
public final class LegalDocuments {

    /**
     * The line every document carries, e.g. {@code Document version: 2026-08-19.1}.
     *
     * <p>Anchored to a line start so a version mentioned in prose cannot be
     * mistaken for the declaration.
     */
    private static final Pattern VERSION_LINE =
            Pattern.compile("(?m)^\\*{0,2}Document version:\\s*([A-Za-z0-9._-]+)\\*{0,2}\\s*$");

    /** Where the document bytes come from. */
    @FunctionalInterface
    public interface Source {
        /**
         * Opens one document for reading.
         *
         * @param document the document wanted
         * @return an open stream the caller closes
         * @throws IOException if the document is missing or unreadable
         */
        InputStream open(LegalDocument document) throws IOException;
    }

    /** Read-buffer size; a document is a few kilobytes of Markdown. */
    private static final int BUFFER_BYTES = 8192;

    private final Map<LegalDocument, String> versions;

    private LegalDocuments(final Map<LegalDocument, String> versions) {
        this.versions = Collections.unmodifiableMap(versions);
    }

    /**
     * Loads every document and extracts its declared version.
     *
     * @param source where the bytes come from
     * @return the version of each of the four documents
     * @throws IOException if any document is missing, unreadable, or carries no
     *     version line; a partial set is not returned, because consent to three
     *     documents out of four is not consent
     */
    public static LegalDocuments load(final Source source) throws IOException {
        final Map<LegalDocument, String> found = new EnumMap<>(LegalDocument.class);
        for (final LegalDocument document : LegalDocument.values()) {
            found.put(document, versionOf(source, document));
        }
        return new LegalDocuments(found);
    }

    /**
     * Reads one document and returns the version it declares.
     *
     * @param source where the bytes come from
     * @param document the document
     * @return the declared version
     * @throws IOException if it cannot be read or declares no version
     */
    private static String versionOf(final Source source, final LegalDocument document)
            throws IOException {
        final String text;
        try (InputStream in = source.open(document)) {
            text = new String(readFully(in), StandardCharsets.UTF_8);
        }
        final Matcher matcher = VERSION_LINE.matcher(text);
        if (!matcher.find()) {
            throw new IOException(document.assetName() + " declares no 'Document version:' line");
        }
        return matcher.group(1);
    }

    /**
     * Reads a stream to its end.
     *
     * <p>A loop rather than {@code InputStream.readAllBytes}, which is API 33
     * and this module's minSdk is 26. The emulator job found that exact call in
     * slice S4b's vector loader; Android Lint now checks test sources too, but
     * the cheapest fix is still not to write it.
     *
     * @param in the open stream
     * @return every byte it yields
     * @throws IOException on a read failure
     */
    private static byte[] readFully(final InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[BUFFER_BYTES];
        int read = in.read(buffer);
        while (read >= 0) {
            out.write(buffer, 0, read);
            read = in.read(buffer);
        }
        return out.toByteArray();
    }

    /**
     * The current version of every document.
     *
     * @return an unmodifiable map covering all four documents
     */
    public Map<LegalDocument, String> versions() {
        return versions;
    }

    /**
     * The current version of one document.
     *
     * @param document the document
     * @return its declared version
     */
    public String versionOf(final LegalDocument document) {
        return versions.get(document);
    }
}
