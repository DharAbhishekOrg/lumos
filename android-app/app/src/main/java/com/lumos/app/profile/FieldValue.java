/*
 * Lumos - android-app/app / One profile field's value, inclusively modelled.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-002, SRS-NFR-PRI-001
 * ADRs: -
 * Threading: any
 * Notes: SRS-PRO-002 requires identity, gender, pronoun and orientation fields
 * to support inclusive values, SELF-DESCRIPTION and PREFER-NOT-TO-SAY without
 * assuming binary gender or heterosexual matching. Those are three different
 * states and a plain String cannot hold them: "" is ambiguous between "not
 * answered" and "declined to answer", and an enum of fixed options cannot carry
 * a self-description at all.
 *
 * So a value is one of three things, and the distinction is load-bearing rather
 * than tidy. UNSET means the user has not reached the field; PREFER_NOT_TO_SAY
 * means they reached it and declined - a deliberate, respectable answer that
 * must survive round-tripping and must never be rendered as blank; STATED
 * carries free text, which is what makes self-description possible without the
 * product enumerating identities on the user's behalf.
 */

package com.lumos.app.profile;

import java.util.Objects;

/** A profile field's value: unset, declined, or stated. */
public final class FieldValue {

    /** Which of the three states this value is in. */
    public enum Kind {
        /** Never answered. */
        UNSET,
        /** Answered with an explicit decline (SRS-PRO-002). */
        PREFER_NOT_TO_SAY,
        /** Answered with free text. */
        STATED
    }

    private static final FieldValue UNSET_VALUE = new FieldValue(Kind.UNSET, "");
    private static final FieldValue DECLINED_VALUE = new FieldValue(Kind.PREFER_NOT_TO_SAY, "");

    private final Kind kind;
    private final String text;

    private FieldValue(final Kind kind, final String text) {
        this.kind = kind;
        this.text = text;
    }

    /**
     * The never-answered value.
     *
     * @return the shared unset value
     */
    public static FieldValue unset() {
        return UNSET_VALUE;
    }

    /**
     * The explicit decline (SRS-PRO-002).
     *
     * @return the shared prefer-not-to-say value
     */
    public static FieldValue preferNotToSay() {
        return DECLINED_VALUE;
    }

    /**
     * A stated value.
     *
     * <p>Blank text collapses to {@link #unset()}: a field the user cleared is
     * unanswered, not an empty statement.
     *
     * @param text the user's words, free-form
     * @return the value
     */
    public static FieldValue stated(final String text) {
        final String trimmed = text == null ? "" : text.trim();
        return trimmed.isEmpty() ? UNSET_VALUE : new FieldValue(Kind.STATED, trimmed);
    }

    /**
     * This value's state.
     *
     * @return the kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * The stated text, empty for the other two kinds.
     *
     * @return the text
     */
    public String text() {
        return text;
    }

    /**
     * Whether the user has answered this field at all.
     *
     * <p>An explicit decline COUNTS as answered. That is what lets
     * completeness validation accept a profile whose sensitive fields are all
     * declined, which SRS-PRO-002 requires and SRS-PRO-010 must not undo.
     *
     * @return true for a decline or a statement
     */
    public boolean isAnswered() {
        return kind != Kind.UNSET;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FieldValue)) {
            return false;
        }
        final FieldValue that = (FieldValue) other;
        return kind == that.kind && text.equals(that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, text);
    }

    @Override
    public String toString() {
        return kind == Kind.STATED ? kind + "(" + text.length() + " chars)" : kind.toString();
    }
}
