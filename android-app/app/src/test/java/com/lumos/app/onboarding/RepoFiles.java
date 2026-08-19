/*
 * Lumos - android-app/app / Reaching repository artefacts from a host test.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TST-001, SRS-ONB-002
 * ADRs: -
 * Threading: any
 * Notes: Two of this slice's artefacts are files rather than classes - the four
 * legal documents the APK ships from docs/legal, and the user copy in
 * res/values/strings.xml - and both must be checked on a host with no device.
 * The repository root comes from the lumos.repoRoot system property the build
 * sets, which is how :protocol-conformance reaches the golden vectors.
 *
 * A missing property is a failure, not a fallback to a guessed path. A test
 * that silently looked somewhere else would pass against the wrong file.
 */

package com.lumos.app.onboarding;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Locates repository files for host tests. */
final class RepoFiles {

    private RepoFiles() {
        throw new AssertionError("no instances");
    }

    /**
     * The repository root, from the property the build sets.
     *
     * @return the root path
     */
    static Path repoRoot() {
        final String root = System.getProperty("lumos.repoRoot");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException(
                    "lumos.repoRoot is not set; the build must pass it to the test task");
        }
        return Paths.get(root);
    }

    /**
     * A source that reads the legal documents from docs/legal.
     *
     * <p>The same bytes the Android build packages as assets, read from the
     * file that IS the shipped artefact rather than from a copy.
     *
     * @return the document source
     */
    static LegalDocuments.Source shippedDocuments() {
        return document -> open(repoRoot().resolve("docs").resolve("legal")
                .resolve(document.assetName()));
    }

    /**
     * Opens a repository file.
     *
     * @param path the file
     * @return an open stream
     * @throws IOException if it is missing
     */
    static InputStream open(final Path path) throws IOException {
        return new FileInputStream(path.toFile());
    }
}
