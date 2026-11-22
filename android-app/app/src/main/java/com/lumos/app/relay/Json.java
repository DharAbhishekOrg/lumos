/*
 * Lumos - android-app/app / A strict JSON reader and writer for the relay contract.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-BCK-001, SRS-RMT-009
 * ADRs: ADR-001
 * Threading: any
 * Notes: The registry and relay speak small, flat JSON: strings (hex and
 * base64), integers, booleans, one level of objects and arrays. This codec
 * reads exactly that and refuses everything else, so no new dependency is
 * needed (AGENTS.md) and nothing a hostile response sends can get further
 * than a malformed-payload refusal: depth is bounded, a key may appear once,
 * numbers must be integers that fit a long, and nothing may trail the value.
 * Unknown keys in a response are kept and ignored by the caller, so a newer
 * server can add fields without breaking an older app.
 */

package com.lumos.app.relay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A strict JSON codec for the relay contract. */
public final class Json {

    /** The deepest nesting a response may use: the contract needs two. */
    static final int MAX_DEPTH = 8;

    private static final int HEX_RADIX = 16;
    private static final int UNICODE_ESCAPE_DIGITS = 4;
    private static final char FIRST_PRINTABLE = 0x20;
    private static final String HEX_DIGITS = "0123456789abcdef";

    private Json() { }

    /** The input is not JSON this contract accepts. */
    public static final class MalformedException extends Exception {
        private static final long serialVersionUID = 1L;

        MalformedException(final String detail) {
            super(detail);
        }
    }

    /** A parsed object: keys in arrival order, values as parsed. */
    public static final class Obj {
        private final Map<String, Object> fields;

        Obj(final Map<String, Object> fields) {
            this.fields = Collections.unmodifiableMap(fields);
        }

        /**
         * Whether a key is present (null counts as present).
         *
         * @param key the key
         * @return true when present
         */
        public boolean has(final String key) {
            return fields.containsKey(key);
        }

        private Object require(final String key) throws MalformedException {
            if (!fields.containsKey(key)) {
                throw new MalformedException("missing field");
            }
            return fields.get(key);
        }

        /**
         * A required string.
         *
         * @param key the key
         * @return the value
         * @throws MalformedException when absent or not a string
         */
        public String string(final String key) throws MalformedException {
            final Object v = require(key);
            if (!(v instanceof String)) {
                throw new MalformedException("field is not a string");
            }
            return (String) v;
        }

        /**
         * A required integer.
         *
         * @param key the key
         * @return the value
         * @throws MalformedException when absent or not an integer
         */
        public long integer(final String key) throws MalformedException {
            final Object v = require(key);
            if (!(v instanceof Long)) {
                throw new MalformedException("field is not an integer");
            }
            return (Long) v;
        }

        /**
         * A required boolean.
         *
         * @param key the key
         * @return the value
         * @throws MalformedException when absent or not a boolean
         */
        public boolean bool(final String key) throws MalformedException {
            final Object v = require(key);
            if (!(v instanceof Boolean)) {
                throw new MalformedException("field is not a boolean");
            }
            return (Boolean) v;
        }

        /**
         * A required object.
         *
         * @param key the key
         * @return the value
         * @throws MalformedException when absent or not an object
         */
        public Obj object(final String key) throws MalformedException {
            final Object v = require(key);
            if (!(v instanceof Obj)) {
                throw new MalformedException("field is not an object");
            }
            return (Obj) v;
        }

        /**
         * An optional object: absent and null both read as null.
         *
         * @param key the key
         * @return the value, or null
         * @throws MalformedException when present and neither null nor an object
         */
        public Obj optObject(final String key) throws MalformedException {
            final Object v = fields.get(key);
            if (v == null || v == NULL) {
                return null;
            }
            if (!(v instanceof Obj)) {
                throw new MalformedException("field is not an object");
            }
            return (Obj) v;
        }

        /**
         * A required array of objects.
         *
         * @param key the key
         * @return the objects
         * @throws MalformedException when absent, not an array, or holding a non-object
         */
        public List<Obj> objects(final String key) throws MalformedException {
            final Object v = require(key);
            if (!(v instanceof List)) {
                throw new MalformedException("field is not an array");
            }
            final List<Obj> out = new ArrayList<>();
            for (final Object item : (List<?>) v) {
                if (!(item instanceof Obj)) {
                    throw new MalformedException("array item is not an object");
                }
                out.add((Obj) item);
            }
            return out;
        }
    }

    /** The JSON null, distinct from an absent key. */
    public static final Object NULL = new Object();

    /**
     * Parses one complete JSON value that must be an object.
     *
     * @param text the document
     * @return the object
     * @throws MalformedException on anything this contract does not accept
     */
    public static Obj parseObject(final String text) throws MalformedException {
        final Reader r = new Reader(text);
        r.skipSpace();
        final Object v = r.value(0);
        r.skipSpace();
        if (!r.atEnd()) {
            throw new MalformedException("trailing content");
        }
        if (!(v instanceof Obj)) {
            throw new MalformedException("top level is not an object");
        }
        return (Obj) v;
    }

    private static final class Reader {
        private final String s;
        private int i;

        Reader(final String s) {
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        void skipSpace() {
            while (i < s.length()) {
                final char c = s.charAt(i);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                i++;
            }
        }

        char peek() throws MalformedException {
            if (atEnd()) {
                throw new MalformedException("unexpected end");
            }
            return s.charAt(i);
        }

        void expect(final char c) throws MalformedException {
            if (peek() != c) {
                throw new MalformedException("unexpected character");
            }
            i++;
        }

        Object value(final int depth) throws MalformedException {
            if (depth > MAX_DEPTH) {
                throw new MalformedException("nested too deeply");
            }
            final char c = peek();
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    literal("true");
                    return Boolean.TRUE;
                case 'f':
                    literal("false");
                    return Boolean.FALSE;
                case 'n':
                    literal("null");
                    return NULL;
                default:
                    return number();
            }
        }

        void literal(final String word) throws MalformedException {
            if (!s.startsWith(word, i)) {
                throw new MalformedException("unknown literal");
            }
            i += word.length();
        }

        Obj object(final int depth) throws MalformedException {
            expect('{');
            final Map<String, Object> fields = new LinkedHashMap<>();
            skipSpace();
            if (peek() == '}') {
                i++;
                return new Obj(fields);
            }
            while (true) {
                skipSpace();
                final String key = string();
                skipSpace();
                expect(':');
                skipSpace();
                final Object v = value(depth + 1);
                if (fields.put(key, v) != null) {
                    throw new MalformedException("duplicate key");
                }
                skipSpace();
                if (peek() == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return new Obj(fields);
            }
        }

        List<Object> array(final int depth) throws MalformedException {
            expect('[');
            final List<Object> items = new ArrayList<>();
            skipSpace();
            if (peek() == ']') {
                i++;
                return items;
            }
            while (true) {
                skipSpace();
                items.add(value(depth + 1));
                skipSpace();
                if (peek() == ',') {
                    i++;
                    continue;
                }
                expect(']');
                return items;
            }
        }

        String string() throws MalformedException {
            expect('"');
            final StringBuilder b = new StringBuilder();
            while (true) {
                final char c = peek();
                i++;
                if (c == '"') {
                    return b.toString();
                }
                if (c < FIRST_PRINTABLE) {
                    throw new MalformedException("control character in string");
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                b.append(escape());
            }
        }

        char escape() throws MalformedException {
            final char e = peek();
            i++;
            switch (e) {
                case '"':
                case '\\':
                case '/':
                    return e;
                case 'b':
                    return '\b';
                case 'f':
                    return '\f';
                case 'n':
                    return '\n';
                case 'r':
                    return '\r';
                case 't':
                    return '\t';
                case 'u':
                    if (i + UNICODE_ESCAPE_DIGITS > s.length()) {
                        throw new MalformedException("short unicode escape");
                    }
                    int code = 0;
                    for (int k = 0; k < UNICODE_ESCAPE_DIGITS; k++) {
                        final int d = Hex.nibble(s.charAt(i++));
                        if (d < 0) {
                            throw new MalformedException("bad unicode escape");
                        }
                        code = code * HEX_RADIX + d;
                    }
                    return (char) code;
                default:
                    throw new MalformedException("unknown escape");
            }
        }

        Long number() throws MalformedException {
            final int start = i;
            if (!atEnd() && s.charAt(i) == '-') {
                i++;
            }
            final int digits = i;
            while (!atEnd() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
            }
            if (i == digits) {
                throw new MalformedException("not a value");
            }
            if (s.charAt(digits) == '0' && i - digits > 1) {
                throw new MalformedException("leading zero");
            }
            if (!atEnd() && (s.charAt(i) == '.' || s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                throw new MalformedException("only integers are accepted");
            }
            try {
                return Long.parseLong(s.substring(start, i));
            } catch (final NumberFormatException e) {
                throw new MalformedException("integer out of range");
            }
        }
    }

    // ---- writing ----------------------------------------------------------------------------

    /** Builds one JSON object, keys in the order given. */
    public static final class Writer {
        private final StringBuilder b = new StringBuilder("{");
        private boolean first = true;

        private Writer key(final String k) {
            if (!first) {
                b.append(',');
            }
            first = false;
            quote(b, k);
            b.append(':');
            return this;
        }

        /**
         * Adds a string field.
         *
         * @param k the key
         * @param v the value
         * @return this writer
         */
        public Writer put(final String k, final String v) {
            key(k);
            quote(b, v);
            return this;
        }

        /**
         * Adds an integer field.
         *
         * @param k the key
         * @param v the value
         * @return this writer
         */
        public Writer put(final String k, final long v) {
            key(k).b.append(v);
            return this;
        }

        /**
         * Adds a field whose value is already-written JSON (an object or array).
         *
         * @param k the key
         * @param json the value as JSON text this codec wrote
         * @return this writer
         */
        public Writer putRaw(final String k, final String json) {
            key(k).b.append(json);
            return this;
        }

        /** @return the finished object */
        public String end() {
            return b.toString() + "}";
        }
    }

    /**
     * Starts an object.
     *
     * @return a writer
     */
    public static Writer object() {
        return new Writer();
    }

    /**
     * Writes an array of already-written JSON values.
     *
     * @param items the values as JSON text
     * @return the array
     */
    public static String array(final List<String> items) {
        return "[" + String.join(",", items) + "]";
    }

    /**
     * Writes an array of strings.
     *
     * @param items the strings
     * @return the array
     */
    public static String stringArray(final List<String> items) {
        final StringBuilder b = new StringBuilder("[");
        for (int k = 0; k < items.size(); k++) {
            if (k > 0) {
                b.append(',');
            }
            quote(b, items.get(k));
        }
        return b.append(']').toString();
    }

    private static void quote(final StringBuilder b, final String v) {
        b.append('"');
        for (int k = 0; k < v.length(); k++) {
            final char c = v.charAt(k);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                default:
                    if (c < FIRST_PRINTABLE) {
                        b.append("\\u00").append(HEX_DIGITS.charAt(c >> 4)).append(HEX_DIGITS.charAt(c & 0xF));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }
}
