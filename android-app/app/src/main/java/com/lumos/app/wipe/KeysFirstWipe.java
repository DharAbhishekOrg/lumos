/*
 * Lumos - android-app/app / The keys-first wipe executor.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-SAF-010, SRS-NFR-PRI-012
 * ADRs: ADR-003, ADR-010
 * Threading: serial-executor
 * Notes: The ordering is the security property, and it is normative: the owner
 * confirmed keys-first on crypto-erasure rationale (2026-08-14), superseding
 * the ADR-003 phrasing - destroying the key material IS the erasure of
 * everything it protects, and content clearing afterwards is hygiene, not the
 * mechanism. So this executor runs every key-material stage before any content
 * stage regardless of registration order, keeps going through key-stage
 * failures (destroy as much key material as possible), and reports exactly
 * what ran, in what order, with what outcome. A wipe interrupted anywhere
 * after the key phase leaves ciphertext without a key.
 *
 * Stores register their stages here as they come to exist; in slice S1 that is
 * the KEK and the wrapped envelopes. SRS-SAF-010's full set (profile,
 * conversations, cached objects, queued packets, venue tokens) registers as
 * those stores are built, and the SRS-NFR-PRI-012 remnant scan runs against
 * the report's completed set.
 */

package com.lumos.app.wipe;

import com.lumos.app.store.StoreException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Executes registered wipe stages, all key material strictly before any content. */
public final class KeysFirstWipe {

    /** One registered wipe action. */
    @FunctionalInterface
    public interface WipeAction {
        /**
         * Performs the stage's destruction.
         *
         * <p>The two checked types are named rather than widened to Exception:
         * a wipe stage destroys key material ({@link GeneralSecurityException})
         * or clears a store ({@link StoreException}), and listing them keeps
         * the executor's tolerance explicit. A RuntimeException is deliberately
         * NOT tolerated - that is a defect in the wipe itself, and swallowing
         * it would turn a broken wipe into a quiet one.
         *
         * @throws GeneralSecurityException on key-destruction failure; recorded, not fatal
         * @throws StoreException on store-clearing failure; recorded, not fatal
         */
        void run() throws GeneralSecurityException, StoreException;
    }

    /** The two phases, in their normative order. */
    public enum Phase {
        /** KEKs and anything else that IS key material. Always first. */
        KEY_MATERIAL,
        /** Everything the key material protected. Only after the key phase. */
        CONTENT
    }

    /** The outcome of one executed stage. */
    public static final class StageResult {
        private final Phase phase;
        private final String name;
        private final String failure;

        private StageResult(final Phase phase, final String name, final String failure) {
            this.phase = phase;
            this.name = name;
            this.failure = failure;
        }

        /**
         * The stage's phase.
         *
         * @return key-material or content
         */
        public Phase phase() {
            return phase;
        }

        /**
         * The stage's registered name.
         *
         * @return the name
         */
        public String name() {
            return name;
        }

        /**
         * Whether the stage completed.
         *
         * @return true on success
         */
        public boolean succeeded() {
            return failure == null;
        }

        /**
         * The failure detail, if any.
         *
         * @return the message, or null when the stage succeeded
         */
        public String failure() {
            return failure;
        }
    }

    /** The full record of one wipe execution. */
    public static final class WipeReport {
        private final List<StageResult> results;

        private WipeReport(final List<StageResult> results) {
            this.results = Collections.unmodifiableList(results);
        }

        /**
         * Every executed stage, in execution order.
         *
         * @return the ordered results
         */
        public List<StageResult> results() {
            return results;
        }

        /**
         * Whether every key-material stage completed.
         *
         * <p>When true, everything the keys protected is ciphertext without a
         * key whatever the content phase did - the crypto-erasure guarantee.
         *
         * @return true when the key phase fully succeeded
         */
        public boolean allKeyMaterialDestroyed() {
            for (final StageResult r : results) {
                if (r.phase() == Phase.KEY_MATERIAL && !r.succeeded()) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Whether every stage of both phases completed.
         *
         * @return true on a fully clean wipe
         */
        public boolean fullyClean() {
            for (final StageResult r : results) {
                if (!r.succeeded()) {
                    return false;
                }
            }
            return true;
        }
    }

    /** One registered stage awaiting execution. */
    private static final class Registered {
        private final String name;
        private final WipeAction action;

        Registered(final String name, final WipeAction action) {
            this.name = name;
            this.action = action;
        }
    }

    private final List<Registered> keyStages = new ArrayList<>();
    private final List<Registered> contentStages = new ArrayList<>();
    private boolean executed;

    /**
     * Registers a key-material stage (a KEK destroy, an envelope zeroise).
     *
     * @param name the stage's name for the report
     * @param action the destruction
     * @return this, for chaining
     */
    public KeysFirstWipe registerKeyMaterial(final String name, final WipeAction action) {
        requireNotExecuted();
        keyStages.add(new Registered(name, action));
        return this;
    }

    /**
     * Registers a content stage (a store clear, a cache purge).
     *
     * @param name the stage's name for the report
     * @param action the destruction
     * @return this, for chaining
     */
    public KeysFirstWipe registerContent(final String name, final WipeAction action) {
        requireNotExecuted();
        contentStages.add(new Registered(name, action));
        return this;
    }

    /**
     * Runs the wipe: all key-material stages, then all content stages.
     *
     * <p>A failing stage is recorded and the wipe continues - stopping early
     * would preserve exactly the data the user asked to destroy. The report
     * says what happened; it never throws.
     *
     * @return the execution report
     */
    public WipeReport execute() {
        requireNotExecuted();
        executed = true;
        final List<StageResult> results = new ArrayList<>();
        runPhase(Phase.KEY_MATERIAL, keyStages, results);
        runPhase(Phase.CONTENT, contentStages, results);
        return new WipeReport(results);
    }

    /**
     * Runs one phase's stages in registration order.
     *
     * @param phase the phase label for the report
     * @param stages the registered stages
     * @param results the accumulating report
     */
    private static void runPhase(final Phase phase, final List<Registered> stages,
            final List<StageResult> results) {
        for (final Registered stage : stages) {
            String failure = null;
            try {
                stage.action.run();
            } catch (GeneralSecurityException | StoreException e) {
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            results.add(new StageResult(phase, stage.name, failure));
        }
    }

    /** Refuses registration or re-execution after the wipe has run. */
    private void requireNotExecuted() {
        if (executed) {
            throw new IllegalStateException("wipe already executed");
        }
    }
}
