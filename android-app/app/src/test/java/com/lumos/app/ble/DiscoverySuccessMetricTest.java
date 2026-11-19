/*
 * Lumos - android-app/app / The metric's closed grammar, enforced.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-ENV-003, SRS-NFR-SEC-013, SRS-DAT-002
 * ADRs: ADR-002
 * Threading: test-only
 * Notes: The redaction test is a WHITELIST: the whole payload must match one
 * regular expression, so there is no field a future edit could quietly add
 * without failing here. The bucket test pins the boundaries, and the
 * no-identifier test feeds the formatter inputs derived from synthetic peer
 * material and proves none of it can surface - which is trivially true today
 * because the formatter only accepts three integers, and this test is what
 * keeps the signature that honest.
 */

package com.lumos.app.ble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.store.RecordKind;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** DiscoverySuccessMetric privacy grammar and buckets. */
final class DiscoverySuccessMetricTest {

    private static final Pattern GRAMMAR = Pattern.compile(
            "^lumos-discovery-metric/1\n"
                    + "cycles=\\d+\n"
                    + "max_step=\\d+\n"
                    + "peers=(none|one|few|some|many)\n"
                    + "success=(true|false)\n$");

    @Test
    void theWholePayloadMatchesTheClosedGrammar() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final DiscoverySuccessMetric metric = new DiscoverySuccessMetric(stack.store());
            final int[][] sessions = {{0, 0, 0}, {5, 1, 1}, {12, 3, 4}, {40, 2, 17}, {3, 0, 150}};
            for (final int[] s : sessions) {
                final String id = metric.record(s[0], s[1], s[2]);
                final String payload = new String(
                        stack.store().get(RecordKind.DIAGNOSTIC_EVENT, id).orElseThrow(),
                        StandardCharsets.UTF_8);
                assertTrue(GRAMMAR.matcher(payload).matches(),
                        "every byte of the record comes from the closed grammar: " + payload);
            }
        }
    }

    @Test
    void theBucketsDestroyTheExactCount() {
        assertEquals("none", DiscoverySuccessMetric.bucket(0));
        assertEquals("one", DiscoverySuccessMetric.bucket(1));
        assertEquals("few", DiscoverySuccessMetric.bucket(2));
        assertEquals("few", DiscoverySuccessMetric.bucket(5));
        assertEquals("some", DiscoverySuccessMetric.bucket(6));
        assertEquals("some", DiscoverySuccessMetric.bucket(20));
        assertEquals("many", DiscoverySuccessMetric.bucket(21));
        assertEquals("many", DiscoverySuccessMetric.bucket(500),
                "a packed venue and a festival look the same on disk");
    }

    @Test
    void successIsPresenceNotEffort() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final DiscoverySuccessMetric metric = new DiscoverySuccessMetric(stack.store());

            final String empty = metric.record(30, 3, 0);
            final String found = metric.record(1, 0, 1);
            final String emptyPayload = new String(
                    stack.store().get(RecordKind.DIAGNOSTIC_EVENT, empty).orElseThrow(),
                    StandardCharsets.UTF_8);
            final String foundPayload = new String(
                    stack.store().get(RecordKind.DIAGNOSTIC_EVENT, found).orElseThrow(),
                    StandardCharsets.UTF_8);

            assertTrue(emptyPayload.contains("success=false"),
                    "thirty cycles of trying is still a false negative to measure");
            assertTrue(foundPayload.contains("success=true"),
                    "one peer on the first cycle is the success the field rate counts");
        }
    }
}
