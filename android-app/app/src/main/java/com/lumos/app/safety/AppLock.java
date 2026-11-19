/*
 * Lumos - android-app/app / App Lock: the UI gate above the always-usable keys.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NFR-SEC-003, SRS-NFR-SEC-013
 * ADRs: ADR-010
 * Threading: serial-executor
 * Notes: ADR-010's first layer: the keys stay usable by the process (the
 * radio decrypts with the device locked), and the PERSON-facing protection is
 * this lock on the UI. The enabled choice persists in the version-3 app_lock
 * record - the choice and nothing else; there is no PIN here, no secret, and
 * unlocking defers entirely to the platform prompt through the DeviceAuth
 * seam, so this class can never become a second credential store. The
 * runtime machine is deliberately blunt: leaving the app locks it, only a
 * CONFIRMED prompt unlocks it, and when the device has no credential at all
 * the lock reports rather than pretends (ADR-010's residual-risk honesty).
 * Absence of the record reads as not enabled - a protection the user adds,
 * the DiscoveryVisibility default discipline in the other direction.
 */

package com.lumos.app.safety;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** The application-level lock of ADR-010. */
public final class AppLock {

    /** The single-row identifier for the choice. */
    public static final String APP_LOCK_ID = "self";

    /** Payload format version; an unknown one reads as not enabled. */
    private static final String VERSION_LINE = "lumos-app-lock/1";

    private final LocalStore store;
    private final DeviceAuth deviceAuth;

    private boolean locked;

    /**
     * Binds the lock to its store and the platform prompt.
     *
     * @param store the opened local store
     * @param deviceAuth the platform prompt seam
     */
    public AppLock(final LocalStore store, final DeviceAuth deviceAuth) {
        this.store = store;
        this.deviceAuth = deviceAuth;
    }

    /**
     * Whether the user has turned App Lock on.
     *
     * @return true only when a stored record explicitly says so
     * @throws StoreException on engine failure
     */
    public boolean isEnabled() throws StoreException {
        final Optional<byte[]> raw = store.get(RecordKind.APP_LOCK, APP_LOCK_ID);
        if (raw.isEmpty()) {
            return false;
        }
        final String[] lines = new String(raw.get(), StandardCharsets.UTF_8).split("\n", -1);
        return lines.length >= 2 && VERSION_LINE.equals(lines[0]) && "on".equals(lines[1]);
    }

    /**
     * Turns App Lock on. The app locks from the next background transition.
     *
     * @param atUtcMillis when the user chose it
     * @throws StoreException on low storage or engine failure
     */
    public void enableAfterExplicitUserRequest(final long atUtcMillis) throws StoreException {
        write("on", atUtcMillis);
    }

    /**
     * Turns App Lock off - after the platform prompt confirms the person,
     * because removing a protection deserves the same proof as passing it.
     *
     * @param atUtcMillis when the user chose it
     * @return true when the prompt confirmed and the lock is off
     * @throws StoreException on low storage or engine failure
     */
    public boolean disableAfterDeviceAuth(final long atUtcMillis) throws StoreException {
        if (deviceAuth.confirm(DeviceAuth.Purpose.APP_UNLOCK) != DeviceAuth.Result.CONFIRMED) {
            return false;
        }
        write("off", atUtcMillis);
        locked = false;
        return true;
    }

    /**
     * The app left the foreground: with the lock enabled, it is now locked.
     *
     * @throws StoreException on engine failure
     */
    public void onAppBackgrounded() throws StoreException {
        if (isEnabled()) {
            locked = true;
        }
    }

    /**
     * Whether the UI must show the unlock gate instead of content.
     *
     * @return true while locked
     */
    public boolean requiresUnlock() {
        return locked;
    }

    /**
     * Presents the platform prompt and unlocks on confirmation.
     *
     * @return the prompt's outcome; the lock opens only on CONFIRMED
     */
    public DeviceAuth.Result unlock() {
        final DeviceAuth.Result result = deviceAuth.confirm(DeviceAuth.Purpose.APP_UNLOCK);
        if (result == DeviceAuth.Result.CONFIRMED) {
            locked = false;
        }
        return result;
    }

    private void write(final String state, final long atUtcMillis) throws StoreException {
        final String payload = VERSION_LINE + "\n" + state + "\n" + atUtcMillis + "\n";
        store.put(RecordKind.APP_LOCK, APP_LOCK_ID,
                payload.getBytes(StandardCharsets.UTF_8), null);
    }
}
