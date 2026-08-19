/*
 * Lumos - android-app/app / The content a RecoveryRecord seals.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-007, SRS-DAT-004
 * ADRs: ADR-003
 * Threading: any
 * Notes: The plaintext that goes inside the S4b record, and the manifest that
 * describes it. The record format was proven three ways in S4b; this is what it
 * carries, and the two are separate on purpose - the format knows nothing about
 * profiles and this knows nothing about Argon2id.
 *
 * The manifest lists EVERY section, including the ones this build cannot fill,
 * with the reason. A user restoring on a later build must be able to tell "your
 * conversations were not in this file because the app could not save them yet"
 * from "your conversations were lost", and only a manifest written at export
 * time can answer that.
 *
 * Row identifiers are carried. They are opaque UUIDs (TRS-LOC-010) that reveal
 * nothing, and keeping them means a restore reproduces the store rather than
 * rebuilding an approximation of it - which matters most for the block list,
 * where a lost row is a person who can contact you again.
 */

package com.lumos.app.recovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The assembled, unsealed content of a recovery export. */
public final class RecoveryBundle {

    /** One exported row. */
    public static final class Item {

        private final String id;
        private final byte[] content;

        /**
         * Wraps one row.
         *
         * @param id the opaque row identifier
         * @param content the row's opened content
         */
        public Item(final String id, final byte[] content) {
            this.id = id;
            this.content = content.clone();
        }

        /**
         * The row identifier.
         *
         * @return the opaque id
         */
        public String id() {
            return id;
        }

        /**
         * The row content.
         *
         * @return a copy
         */
        public byte[] content() {
            return content.clone();
        }
    }

    private final long exportedAtUtcMillis;
    private final Map<RecoverySection, List<Item>> sections;

    /**
     * Assembles a bundle.
     *
     * @param exportedAtUtcMillis when the export was made
     * @param sections the rows of each section that could be filled
     */
    public RecoveryBundle(final long exportedAtUtcMillis,
            final Map<RecoverySection, List<Item>> sections) {
        this.exportedAtUtcMillis = exportedAtUtcMillis;
        final Map<RecoverySection, List<Item>> copy = new EnumMap<>(RecoverySection.class);
        for (final Map.Entry<RecoverySection, List<Item>> entry : sections.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        this.sections = Collections.unmodifiableMap(copy);
    }

    /**
     * When the export was made.
     *
     * @return UTC milliseconds since the epoch
     */
    public long exportedAtUtcMillis() {
        return exportedAtUtcMillis;
    }

    /**
     * The rows of one section.
     *
     * @param section the section
     * @return its rows, empty when the section carried none
     */
    public List<Item> items(final RecoverySection section) {
        return sections.getOrDefault(section, List.of());
    }

    /**
     * The sections this bundle actually carries rows for.
     *
     * @return the filled sections, in enum order
     */
    public List<RecoverySection> filledSections() {
        final List<RecoverySection> out = new ArrayList<>();
        for (final RecoverySection section : RecoverySection.values()) {
            if (!items(section).isEmpty()) {
                out.add(section);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * The sections no build could fill at export time (SRS-ONB-007 honesty).
     *
     * @return the sections whose feature does not exist yet
     */
    public List<RecoverySection> unavailableSections() {
        final List<RecoverySection> out = new ArrayList<>();
        for (final RecoverySection section : RecoverySection.values()) {
            if (!section.isAvailable()) {
                out.add(section);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * The total number of rows carried.
     *
     * @return the row count across every section
     */
    public int rowCount() {
        int total = 0;
        for (final RecoverySection section : RecoverySection.values()) {
            total += items(section).size();
        }
        return total;
    }
}
