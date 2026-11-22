/*
 * Lumos - android-app/app / Lowercase hex, as the registry and relay write bytes.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-BCK-001
 * ADRs: ADR-001
 * Threading: any
 * Notes: Hand-written because java.util.HexFormat is API 34 and the app
 * runs from API 26 (lumoslint minsdk-api). Decoding refuses odd lengths,
 * non-hex characters and any length other than the one expected.
 */

package com.lumos.app.relay;

/** Lowercase hex. */
final class Hex {

    private static final String DIGITS = "0123456789abcdef";
    private static final int TEN = 10;
    private static final int NIBBLE = 4;
    private static final int LOW = 0xF;

    private Hex() { }

    static String encode(final byte[] b) {
        final StringBuilder s = new StringBuilder(b.length * 2);
        for (final byte x : b) {
            s.append(DIGITS.charAt((x >> NIBBLE) & LOW)).append(DIGITS.charAt(x & LOW));
        }
        return s.toString();
    }

    /**
     * Decodes exactly n bytes.
     *
     * @param s the hex text
     * @param n the expected byte length
     * @return the bytes
     * @throws Json.MalformedException when s is not exactly n bytes of hex
     */
    static byte[] decode(final String s, final int n) throws Json.MalformedException {
        if (s.length() != n * 2) {
            throw new Json.MalformedException("hex field has the wrong length");
        }
        final byte[] out = new byte[n];
        for (int k = 0; k < n; k++) {
            final int hi = nibble(s.charAt(2 * k));
            final int lo = nibble(s.charAt(2 * k + 1));
            if (hi < 0 || lo < 0) {
                throw new Json.MalformedException("not hex");
            }
            out[k] = (byte) ((hi << NIBBLE) | lo);
        }
        return out;
    }

    /**
     * One ASCII hex digit's value, or -1. Character.digit is not used: it
     * also accepts non-ASCII digits, which a strict decoder must refuse.
     */
    static int nibble(final char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + TEN;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + TEN;
        }
        return -1;
    }
}
