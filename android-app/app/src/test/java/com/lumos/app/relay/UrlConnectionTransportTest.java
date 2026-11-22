/*
 * Lumos - android-app/app / The HttpURLConnection transport on a real loopback socket.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-BCK-001, SRS-NFR-SEC-009, TRS-TST-001
 * ADRs: ADR-001
 * Threading: test-only
 * Notes: A minimal HTTP/1.1 responder on a ServerSocket (android.jar carries
 * java.net but not the JDK's com.sun.net.httpserver) answers one scripted
 * response per connection and records what arrived. TLS itself is the
 * platform's and is not re-tested here; what is tested is that the
 * transport refuses to be pointed anywhere but https or loopback, never
 * follows a redirect with the credential, and never reads past its cap.
 */

package com.lumos.app.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The socket transport. */
public class UrlConnectionTransportTest {

    /** One scripted HTTP server on 127.0.0.1. */
    private static final class Server implements AutoCloseable {
        private final ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        private volatile String head;
        private volatile byte[] body = new byte[0];
        private final Thread thread;

        Server() throws IOException {
            thread = new Thread(this::serve);
            thread.setDaemon(true);
            thread.start();
        }

        Server respond(final String statusAndHeaders, final byte[] responseBody) {
            head = statusAndHeaders;
            body = responseBody;
            return this;
        }

        URL base() throws IOException {
            return new URL("http://127.0.0.1:" + socket.getLocalPort());
        }

        private void serve() {
            while (!socket.isClosed()) {
                try (Socket s = socket.accept()) {
                    final BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    final StringBuilder req = new StringBuilder(in.readLine()).append('\n');
                    int length = 0;
                    for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                        req.append(line).append('\n');
                        if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                            length = Integer.parseInt(line.substring(15).trim());
                        }
                    }
                    final char[] b = new char[length];
                    int got = 0;
                    while (got < length) {
                        got += in.read(b, got, length - got);
                    }
                    requests.add(req.append('\n').append(b).toString());
                    final OutputStream out = s.getOutputStream();
                    out.write((head + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                    out.write(body);
                    out.flush();
                } catch (final IOException e) {
                    return;
                }
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (final AutoCloseable c : open) {
            c.close();
        }
    }

    private Server server() throws IOException {
        final Server s = new Server();
        open.add(s);
        return s;
    }

    @Test
    void aRequestCarriesItsMethodPathCredentialAndBody() throws Exception {
        final Server s = server().respond("HTTP/1.1 202 Accepted", new byte[0]);
        final HttpTransport.Response r = UrlConnectionTransport.to(s.base())
                .exchange("POST", "/v1/envelopes", "c0ffee", "{\"a\":\"é\"}");
        assertEquals(202, r.status());
        assertEquals("", r.body());
        assertEquals(-1, r.retryAfterSeconds());
        final String seen = s.requests.get(0);
        assertTrue(seen.startsWith("POST /v1/envelopes HTTP/1.1"), seen);
        assertTrue(seen.contains("Authorization: Bearer c0ffee"), seen);
        assertTrue(seen.contains("Content-Type: application/json"), seen);
        assertTrue(seen.endsWith(new String("{\"a\":\"é\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.ISO_8859_1)), "the body goes as UTF-8");
    }

    @Test
    void anErrorStatusStillReturnsItsBodyAndRetryAfter() throws Exception {
        final Server s = server().respond("HTTP/1.1 429 Too Many Requests\r\nRetry-After: 7",
                "{\"code\":\"ERR_RELAY_RATE_LIMITED\"}".getBytes(StandardCharsets.UTF_8));
        final HttpTransport.Response r = UrlConnectionTransport.to(s.base()).exchange("GET", "/x", null, null);
        assertEquals(429, r.status());
        assertEquals(7, r.retryAfterSeconds());
        assertEquals("{\"code\":\"ERR_RELAY_RATE_LIMITED\"}", r.body());
        assertTrue(!s.requests.get(0).contains("Authorization"), "no credential when none is given");
    }

    @Test
    void aRedirectIsReturnedNotFollowed() throws Exception {
        final Server s = server().respond("HTTP/1.1 302 Found\r\nLocation: /elsewhere", new byte[0]);
        final HttpTransport.Response r = UrlConnectionTransport.to(s.base()).exchange("GET", "/v1/x", "aa", null);
        assertEquals(302, r.status(), "the credential never follows a redirect");
        assertEquals(1, s.requests.size());
    }

    @Test
    void aResponseOverTheCapIsTreatedAsLost() throws Exception {
        final Server s = server().respond("HTTP/1.1 200 OK", new byte[UrlConnectionTransport.MAX_RESPONSE_BYTES + 1]);
        assertThrows(IOException.class, () -> UrlConnectionTransport.to(s.base()).exchange("GET", "/x", null, null));
    }

    @Test
    void noServerIsAnIoFailure() throws Exception {
        final Server s = server();
        final URL base = s.base();
        s.close();
        assertThrows(IOException.class, () -> UrlConnectionTransport.to(base).exchange("GET", "/x", null, null));
    }

    @Test
    void onlyHttpsOrALiteralLoopbackOriginIsAccepted() throws Exception {
        for (final String ok : List.of("https://relay.example", "https://relay.example/", "http://127.0.0.1:8080",
                "http://localhost:9", "http://[::1]:80")) {
            UrlConnectionTransport.to(new URL(ok));
        }
        for (final String bad : List.of("http://relay.example", "http://10.0.2.2:8080", "http://127.0.0.1.nip.io",
                "http://localhost.evil.example", "https://relay.example/v1", "https://relay.example/?x=1",
                "https://user@relay.example", "ftp://relay.example")) {
            assertThrows(IllegalArgumentException.class, () -> UrlConnectionTransport.to(new URL(bad)), bad);
        }
    }

    @Test
    void retryAfterIsReadAsSecondsOnly() {
        assertEquals(120, UrlConnectionTransport.retryAfter("120"));
        assertEquals(5, UrlConnectionTransport.retryAfter(" 5 "));
        assertEquals(-1, UrlConnectionTransport.retryAfter(null));
        assertEquals(-1, UrlConnectionTransport.retryAfter("-1"));
        assertEquals(-1, UrlConnectionTransport.retryAfter("Wed, 21 Oct 2015 07:28:00 GMT"));
        assertEquals(-1, UrlConnectionTransport.retryAfter("9999999999"));
    }
}
