/*
 * Lumos - android-app/app / The relay transport over the platform's HttpURLConnection.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-BCK-001, SRS-NFR-SEC-009, TRS-BCK-002
 * ADRs: ADR-001
 * Threading: any
 * Notes: The platform's own TLS and certificate validation, untouched: no
 * custom trust manager, no hostname verifier, no socket factory, so there is
 * nothing here that could be a trust-all bypass (SRS-NFR-SEC-009). TLS 1.3 is
 * what the platform negotiates on Android 10 and later; the TLS floor itself
 * (SRS-NFR-SEC-008) is the backend's policy and the owner-run TLS scan's to
 * prove, not this client's. The
 * base must be https; plain http is accepted only for a loopback host, which
 * is what tests and a local docker-compose backend use. Redirects are never
 * followed, so the bearer credential only ever goes to the configured host.
 * A response body is read up to a fixed cap; beyond it the response is
 * treated as lost. Nothing here logs a header or a body (TRS-BCK-002).
 */

package com.lumos.app.relay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/** An HttpTransport over HttpURLConnection. */
public final class UrlConnectionTransport implements HttpTransport {

    /** The largest response body read: a full fetch batch fits with room (see RelayClient). */
    static final int MAX_RESPONSE_BYTES = 1 << 20;
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;
    private static final int CHUNK = 8192;
    private static final int FIRST_ERROR_STATUS = 400;

    private final URL base;

    private UrlConnectionTransport(final URL base) {
        this.base = base;
    }

    /**
     * A transport to one backend.
     *
     * @param base the backend's origin, e.g. https://relay.example
     * @return the transport
     * @throws IllegalArgumentException when the base is not https (or http on loopback), or carries a path
     */
    public static UrlConnectionTransport to(final URL base) {
        final String path = base.getPath();
        if (!(path.isEmpty() || "/".equals(path)) || base.getQuery() != null || base.getUserInfo() != null) {
            throw new IllegalArgumentException("the backend base is an origin only");
        }
        final boolean https = "https".equals(base.getProtocol());
        if (!https && !("http".equals(base.getProtocol()) && isLoopback(base.getHost()))) {
            throw new IllegalArgumentException("the backend must be https");
        }
        return new UrlConnectionTransport(base);
    }

    private static boolean isLoopback(final String host) {
        if ("localhost".equals(host)) {
            return true;
        }
        // Only literal addresses: a name is never resolved here, so this check
        // cannot be steered by DNS.
        if (!host.matches("[0-9.]+|\\[[0-9a-fA-F:]+\\]")) {
            return false;
        }
        try {
            return InetAddress.getByName(host.replace("[", "").replace("]", "")).isLoopbackAddress();
        } catch (final UnknownHostException e) {
            return false;
        }
    }

    @Override
    public Response exchange(final String method, final String path, final String bearer, final String jsonBody)
            throws IOException {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("a path starts with /");
        }
        final HttpURLConnection c = (HttpURLConnection) new URL(base, path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            c.setReadTimeout(READ_TIMEOUT_MILLIS);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            if (bearer != null) {
                c.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            if (jsonBody != null) {
                final byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body);
                }
            }
            final int status = c.getResponseCode();
            final InputStream in = status >= FIRST_ERROR_STATUS ? c.getErrorStream() : c.getInputStream();
            final String text = in == null ? "" : readCapped(in);
            return new Response(status, text, retryAfter(c.getHeaderField("Retry-After")));
        } finally {
            c.disconnect();
        }
    }

    private static String readCapped(final InputStream in) throws IOException {
        try (InputStream s = in) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] chunk = new byte[CHUNK];
            int n;
            while ((n = s.read(chunk)) != -1) {
                if (out.size() + n > MAX_RESPONSE_BYTES) {
                    throw new IOException("response larger than the cap");
                }
                out.write(chunk, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Reads Retry-After as delay seconds (RFC 9110); the date form and
     * anything unreadable count as absent.
     */
    static long retryAfter(final String header) {
        if (header == null || !header.trim().matches("[0-9]{1,9}")) {
            return -1;
        }
        return Long.parseLong(header.trim());
    }
}
