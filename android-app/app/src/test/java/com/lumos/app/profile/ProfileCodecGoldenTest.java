/*
 * Lumos - android-app/app / One profile and one policy, byte for byte, as both apps must write them.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-002, SRS-PRO-004, TRS-LOC-002
 * ADRs: ADR-003
 * Threading: test-only
 * Notes: The same two literals sit in the iOS ProfileStoreTests. If either
 * platform's codec drifts, its own test fails, so a recovery export reads
 * the same on both. The profile covers all three value states and the two
 * characters the framing escapes (a newline and a tab in the bio) plus a
 * literal percent sign.
 */

package com.lumos.app.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** ProfileCodec against the cross-platform golden payloads. */
final class ProfileCodecGoldenTest {

    static final String PROFILE = "lumos-profile/1\n"
            + "lifecycle\tACTIVE\n"
            + "field\tALIAS\tSTATED\tAsha\n"
            + "field\tAGE_BAND\tSTATED\t25–34\n"
            + "field\tPRONOUNS\tUNSET\t\n"
            + "field\tGENDER_IDENTITY\tUNSET\t\n"
            + "field\tORIENTATION\tPREFER_NOT_TO_SAY\t\n"
            + "field\tRELATIONSHIP_INTENT\tUNSET\t\n"
            + "field\tINTERESTS\tUNSET\t\n"
            + "field\tBIO\tSTATED\tline one%0Aline%09two 50%25\n"
            + "field\tMEDIA\tUNSET\t\n";

    static final String POLICY = "lumos-policy/1\n"
            + "ALIAS\tNEARBY\n"
            + "AGE_BAND\tREQUESTER\n"
            + "PRONOUNS\tMATCH\n"
            + "GENDER_IDENTITY\tMATCH\n"
            + "ORIENTATION\tMATCH\n"
            + "RELATIONSHIP_INTENT\tMATCH\n"
            + "INTERESTS\tMATCH\n"
            + "BIO\tMATCH\n"
            + "MEDIA\tMATCH\n";

    private static LocalProfile profile() {
        return LocalProfile.empty()
                .with(ProfileField.ALIAS, FieldValue.stated("Asha"))
                .with(ProfileField.AGE_BAND, FieldValue.stated("25–34"))
                .with(ProfileField.ORIENTATION, FieldValue.preferNotToSay())
                .with(ProfileField.BIO, FieldValue.stated("line one\nline\ttwo 50%"));
    }

    private static ProfileFieldPolicy policy() {
        return ProfileFieldPolicy.privateByDefault()
                .with(ProfileField.ALIAS, ProfileFieldPolicy.Audience.NEARBY)
                .with(ProfileField.AGE_BAND, ProfileFieldPolicy.Audience.REQUESTER);
    }

    @Test
    void theProfileEncodesToTheGoldenBytesAndBack() {
        assertEquals(PROFILE, new String(ProfileCodec.encodeProfile(profile()), StandardCharsets.UTF_8));
        final LocalProfile back = ProfileCodec.decodeProfile(PROFILE.getBytes(StandardCharsets.UTF_8));
        for (final ProfileField field : ProfileField.values()) {
            assertEquals(profile().value(field), back.value(field), field.name());
        }
    }

    @Test
    void thePolicyEncodesToTheGoldenBytesAndBack() {
        assertEquals(POLICY, new String(ProfileCodec.encodePolicy(policy()), StandardCharsets.UTF_8));
        final ProfileFieldPolicy back = ProfileCodec.decodePolicy(POLICY.getBytes(StandardCharsets.UTF_8));
        for (final ProfileField field : ProfileField.values()) {
            assertEquals(policy().threshold(field), back.threshold(field), field.name());
        }
    }
}
