/*
 * Lumos - android-app/app / The relay transport refuses a server the platform does not trust.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NFR-SEC-009, TRS-BCK-001, TRS-TST-001
 * ADRs: ADR-001
 * Threading: test-only
 * Notes: SRS-NFR-SEC-009's dynamic half (the static half is lumoslint's
 * crypto-hygiene rule: no trust-all manager, no hostname-verifier bypass). A
 * real TLS server on loopback presents a self-signed certificate made for
 * this run by the JDK's own keytool; the transport, on platform trust, must
 * fail the handshake before a single request byte - and so before the bearer
 * credential - reaches the server. The server is a bare SSLServerSocket
 * (the Android unit-test classpath has no JDK HTTP server). The control proves the server is sound: a
 * client that is told to trust exactly that certificate gets an answer, so
 * the refusal is about trust, not a broken fixture. The keystore password is
 * random per run and the keystore lives in a temporary directory.
 */

package com.lumos.app.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** UrlConnectionTransport against an untrusted TLS server. */
final class UntrustedCertificateTest {

    private Path dir;
    private KeyStore keyStore;
    private char[] password;
    private SSLServerSocket server;
    private Thread acceptor;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startServer() throws Exception {
        dir = Files.createTempDirectory("lumos-tls");
        final byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        final StringBuilder hex = new StringBuilder();
        for (final byte b : random) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        password = hex.toString().toCharArray();
        final File store = dir.resolve("server.p12").toFile();
        final String keytool = System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
        final Process gen = new ProcessBuilder(keytool, "-genkeypair", "-alias", "relay", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", store.getPath(),
                "-storepass", new String(password), "-keypass", new String(password))
                .redirectErrorStream(true).start();
        gen.getInputStream().readAllBytes();
        if (!gen.waitFor(60, TimeUnit.SECONDS) || gen.exitValue() != 0) {
            throw new IllegalStateException("keytool could not make the test certificate");
        }
        keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(store)) {
            keyStore.load(in, password);
        }
        final KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keyStore, password);
        final SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keys.getKeyManagers(), null, null);

        server = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(0, 4,
                InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::serve, "untrusted-tls");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** Answers 200 to every request that survives the handshake, counting it. */
    private void serve() {
        while (!server.isClosed()) {
            try (Socket client = server.accept()) {
                final InputStream in = client.getInputStream();
                final StringBuilder head = new StringBuilder();
                int b;
                while ((b = in.read()) != -1) {
                    head.append((char) b);
                    if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) {
                        break;
                    }
                }
                if (head.length() == 0) {
                    continue;
                }
                requests.incrementAndGet();
                final OutputStream out = client.getOutputStream();
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException handshakeOrClose) {
                // A refused handshake lands here: that is the case under test.
            }
        }
    }

    @AfterEach
    void stopServer() throws IOException {
        if (server != null) {
            server.close();
        }
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private URL base() throws IOException {
        return new URL("https://127.0.0.1:" + server.getLocalPort());
    }

    @Test
    void anUntrustedCertificateFailsBeforeAnyRequestOrCredentialIsSent() throws Exception {
        final UrlConnectionTransport transport = UrlConnectionTransport.to(base());
        final IOException refused = assertThrows(IOException.class,
                () -> transport.exchange("POST", "/v1/envelopes", "c0ffee", "{}"),
                "platform trust refuses a self-signed server");
        Throwable cause = refused;
        while (cause != null && !(cause instanceof SSLException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause, "the failure is the TLS handshake, not something else: " + refused);
        assertEquals(0, requests.get(), "not one request - and so no bearer credential - reached it");
    }

    @Test
    void theControlServerAnswersAClientThatTrustsExactlyItsCertificate() throws Exception {
        final TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keyStore);
        final SSLContext pinned = SSLContext.getInstance("TLS");
        pinned.init(null, trust.getTrustManagers(), null);
        final HttpsURLConnection c = (HttpsURLConnection) new URL(base(), "/control").openConnection();
        c.setSSLSocketFactory(pinned.getSocketFactory());
        assertEquals(200, c.getResponseCode(), "the fixture is a working TLS server");
        c.disconnect();
        assertEquals(1, requests.get());
    }
}
