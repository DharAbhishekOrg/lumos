/*
 * Lumos - android-app/app / Profile and policy payload encoding.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-001, SRS-PRO-003, TRS-LOC-002
 * ADRs: -
 * Threading: any
 * Notes: A deliberately boring line-oriented encoding, written by hand rather
 * than reached for from a library. Three reasons, in order of weight:
 *
 * 1. It is the payload that gets SEALED, so its parser runs on bytes that have
 *    already been authenticated by the record AEAD. A general-purpose
 *    deserialiser here would add attack surface behind a door that is already
 *    locked, for no benefit.
 * 2. No dependency, on a platform where the obvious choices are either an
 *    Android framework class the JVM tests cannot load, or a new library.
 * 3. It round-trips the THREE FieldValue states exactly. A JSON mapping would
 *    make "unset" and "prefer not to say" both look like an absent key unless
 *    someone remembered otherwise, and that distinction is SRS-PRO-002.
 *
 * Values are percent-escaped for the two characters that would otherwise break
 * the framing, so a bio containing a newline survives - which a naive
 * line-oriented format silently truncates.
 */

package com.lumos.app.profile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Encodes profiles and policies as sealed-record payloads. */
public final class ProfileCodec {

    /** Payload format version; an unknown one fails closed. */
    private static final String VERSION_LINE = "lumos-profile/1";

    /** Policy payload format version. */
    private static final String POLICY_VERSION_LINE = "lumos-policy/1";

    private ProfileCodec() {
        throw new AssertionError("no instances");
    }

    /**
     * Encodes a profile.
     *
     * @param profile the profile
     * @return the payload bytes
     */
    public static byte[] encodeProfile(final LocalProfile profile) {
        final StringBuilder out = new StringBuilder(VERSION_LINE).append('\n');
        out.append("lifecycle\t").append(profile.lifecycle().name()).append('\n');
        for (final ProfileField field : ProfileField.values()) {
            final FieldValue value = profile.value(field);
            out.append("field\t").append(field.name()).append('\t')
                    .append(value.kind().name()).append('\t')
                    .append(escape(value.text())).append('\n');
        }
        for (final ProfileMedia media : profile.media()) {
            out.append("media\t").append(escape(media.objectId())).append('\t')
                    .append(escape(media.contentType())).append('\t')
                    .append(media.widthPixels()).append('\t')
                    .append(media.heightPixels()).append('\t')
                    .append(media.byteCount()).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Decodes a profile.
     *
     * @param payload the payload bytes
     * @return the profile
     */
    public static LocalProfile decodeProfile(final byte[] payload) {
        final String[] lines = new String(payload, StandardCharsets.UTF_8).split("\n", -1);
        requireVersion(lines, VERSION_LINE);

        LocalProfile profile = LocalProfile.empty();
        final List<ProfileMedia> media = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            final String[] parts = lines[i].split("\t", -1);
            if (parts.length < 2) {
                continue;
            }
            switch (parts[0]) {
                case "lifecycle":
                    profile = profile.withLifecycle(LocalProfile.Lifecycle.valueOf(parts[1]));
                    break;
                case "field":
                    profile = profile.with(ProfileField.valueOf(parts[1]), valueOf(parts));
                    break;
                case "media":
                    media.add(new ProfileMedia(unescape(parts[1]), unescape(parts[2]),
                            Integer.parseInt(parts[3]), Integer.parseInt(parts[4]),
                            Integer.parseInt(parts[5])));
                    break;
                default:
                    // An unknown record type from a newer build. Skipping keeps
                    // a forward read lossy-but-working rather than fatal; the
                    // schema version gate is what refuses a real mismatch.
                    break;
            }
        }
        return profile.withMedia(media);
    }

    /**
     * Rebuilds one field's value from its encoded parts.
     *
     * @param parts the tab-split line
     * @return the value
     */
    private static FieldValue valueOf(final String[] parts) {
        final FieldValue.Kind kind = FieldValue.Kind.valueOf(parts[2]);
        switch (kind) {
            case STATED:
                return FieldValue.stated(unescape(parts[3]));
            case PREFER_NOT_TO_SAY:
                return FieldValue.preferNotToSay();
            default:
                return FieldValue.unset();
        }
    }

    /**
     * Encodes a visibility policy.
     *
     * @param policy the policy
     * @return the payload bytes
     */
    public static byte[] encodePolicy(final ProfileFieldPolicy policy) {
        final StringBuilder out = new StringBuilder(POLICY_VERSION_LINE).append('\n');
        for (final ProfileField field : ProfileField.values()) {
            out.append(field.name()).append('\t').append(policy.threshold(field).name()).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Decodes a visibility policy.
     *
     * <p>Any field the payload omits keeps the private default, so a policy
     * written by an older build cannot widen a field added since.
     *
     * @param payload the payload bytes
     * @return the policy
     */
    public static ProfileFieldPolicy decodePolicy(final byte[] payload) {
        final String[] lines = new String(payload, StandardCharsets.UTF_8).split("\n", -1);
        requireVersion(lines, POLICY_VERSION_LINE);

        ProfileFieldPolicy policy = ProfileFieldPolicy.privateByDefault();
        for (int i = 1; i < lines.length; i++) {
            final String[] parts = lines[i].split("\t", -1);
            if (parts.length < 2 || parts[0].isEmpty()) {
                continue;
            }
            policy = policy.with(ProfileField.valueOf(parts[0]),
                    ProfileFieldPolicy.Audience.valueOf(parts[1]));
        }
        return policy;
    }

    /**
     * Refuses a payload whose format line is not the expected one.
     *
     * @param lines the split payload
     * @param expected the version line
     */
    private static void requireVersion(final String[] lines, final String expected) {
        if (lines.length == 0 || !expected.equals(lines[0])) {
            throw new IllegalArgumentException("unrecognised payload format");
        }
    }

    /**
     * Escapes the two characters the framing uses.
     *
     * @param raw the value
     * @return the escaped value
     */
    private static String escape(final String raw) {
        return raw.replace("%", "%25").replace("\t", "%09").replace("\n", "%0A");
    }

    /**
     * Reverses {@link #escape}.
     *
     * @param escaped the escaped value
     * @return the original value
     */
    private static String unescape(final String escaped) {
        return escaped.replace("%0A", "\n").replace("%09", "\t").replace("%25", "%");
    }
}
