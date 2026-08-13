/*
 * Lumos - android-app/app / The KekStore interface stays narrow, by reflection.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-ARCH-005, SRS-NFR-SEC-003
 * ADRs: ADR-003, ADR-010
 * Threading: any
 * Notes: TRS-ARCH-005 requires security-sensitive operations behind narrow
 * interfaces that never expose raw private keys. An interface can only keep
 * that promise until someone adds a convenience accessor, so the method set is
 * pinned here BY NAME: growing it means editing a test that cites the
 * requirement, in review, on purpose. This is the JVM-side half of the
 * non-exportability negative tests; the hardware half (a keystore-held key
 * refusing export on a real device) is owner-executed.
 */

package com.lumos.app.keystore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.security.Key;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Pins the narrow KEK interface of TRS-ARCH-005. */
final class KekStoreContractTest {

    @Test
    void interfaceCarriesExactlyTheNarrowOperationSet() {
        final Set<String> methods = new TreeSet<>();
        for (final Method m : KekStore.class.getDeclaredMethods()) {
            methods.add(m.getName());
        }
        final Set<String> expected = new TreeSet<>(
                Set.of("protection", "wrap", "unwrap", "destroy", "isDestroyed"));
        assertEquals(expected, methods,
                "KekStore is the TRS-ARCH-005 boundary; adding a method means editing this test in review");
    }

    @Test
    void noMethodReturnsKeyMaterialTypes() {
        for (final Method m : KekStore.class.getDeclaredMethods()) {
            assertFalse(Key.class.isAssignableFrom(m.getReturnType()),
                    m.getName() + " must not return a key object; the KEK never crosses the boundary");
        }
    }

    @Test
    void implementationsExposeNoPublicKeyAccessorEither() {
        for (final Method m : SoftwareKekStore.class.getMethods()) {
            if (m.getDeclaringClass() == Object.class) {
                continue;
            }
            assertFalse(Key.class.isAssignableFrom(m.getReturnType()),
                    m.getName() + " must not surface the KEK past the interface");
        }
    }
}
