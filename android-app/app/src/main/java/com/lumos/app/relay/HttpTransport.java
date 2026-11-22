/*
 * Lumos - android-app/app / The one seam between the relay client and the network.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-BCK-001, TRS-TST-001
 * ADRs: ADR-001
 * Threading: any
 * Notes: One method, so the client's every rule - status mapping, retry
 * classes, what is acknowledged when - is tested against an in-memory relay
 * with no socket, and the socket implementation is tested on its own.
 * Bodies are JSON text; none is ever logged by either side.
 */

package com.lumos.app.relay;

import java.io.IOException;

/** Sends one request and returns its response. */
public interface HttpTransport {

    /**
     * One response.
     *
     * @param status the HTTP status
     * @param body the body as text (empty when none)
     * @param retryAfterSeconds the Retry-After header in seconds, or -1 when absent
     */
    record Response(int status, String body, long retryAfterSeconds) { }

    /**
     * Sends one request.
     *
     * @param method GET, POST, PUT or DELETE
     * @param path the path and query, starting with "/"
     * @param bearer the device credential as hex, or null for the unauthenticated routes
     * @param jsonBody the request body, or null for none
     * @return the response, whatever its status
     * @throws IOException when no response arrived: offline, refused, timed out, or cut off
     */
    Response exchange(String method, String path, String bearer, String jsonBody) throws IOException;
}
