/*
 * Copyright 2026 Oliver Flasch
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.oliverflasch.fess.ds.giirii.support;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link HttpFetcher} against a loopback server.
 *
 * @author Oliver Flasch
 */
public class HttpFetcherTest {

    /** A response body. */
    private static final byte[] BODY = "body".getBytes(StandardCharsets.UTF_8);

    /** The server. */
    private FakeSite site;

    /** The waits the fetcher requested before retries. */
    private final List<Long> waits = new ArrayList<>();

    /** The fetcher under test; it records waits and does not sleep. */
    private HttpFetcher fetcher;

    /**
     * Creates a fetcher that records its waits.
     *
     * @param userAgent the User-Agent
     * @param timeout the request timeout
     * @return the fetcher
     */
    private HttpFetcher fetcher(final String userAgent, final Duration timeout) {
        return new HttpFetcher(site.baseUrl(), userAgent, timeout) {
            @Override
            protected void sleep(final long millis, final String path) {
                waits.add(millis);
            }
        };
    }

    /**
     * Starts the server and creates the fetcher.
     */
    @BeforeEach
    public void setUp() {
        site = new FakeSite();
        fetcher = fetcher("test-agent", Duration.ofSeconds(10));
    }

    /**
     * Stops the fetcher and the server.
     */
    @AfterEach
    public void tearDown() {
        fetcher.close();
        site.close();
    }

    @Test
    public void returnsBodyAndEntityTag() throws IOException {
        site.serve("/bgb/xml.zip", BODY, "\"abc-1\"");
        final HttpFetcher.Response response = fetcher.get("/bgb/xml.zip", Optional.empty(), 1024);
        assertEquals(200, response.status(), "status");
        assertEquals(Optional.of("\"abc-1\""), response.validator(), "validator");
        assertArrayEquals(BODY, response.body(), "body");
        final FakeSite.Request request = site.requests().get(0);
        assertEquals("test-agent", request.userAgent(), "User-Agent");
        assertNull(request.ifNoneMatch(), "no condition without a validator");
        assertNull(request.ifModifiedSince(), "no condition without a validator");
    }

    @Test
    public void sendsTheEntityTagAndReturnsNotModified() throws IOException {
        site.serve("/bgb/xml.zip", BODY, "\"abc-1\"");
        final HttpFetcher.Response response = fetcher.get("/bgb/xml.zip", Optional.of("\"abc-1\""), 1024);
        assertEquals(304, response.status(), "status");
        assertEquals(0, response.body().length, "no body");
        assertEquals("\"abc-1\"", site.requests().get(0).ifNoneMatch(), "If-None-Match");
    }

    @Test
    public void fallsBackToLastModifiedAsValidator() throws IOException {
        final String date = "Sat, 26 Sep 2026 19:55:12 GMT";
        site.serve("/a", BODY, Map.of("Last-Modified", date));
        assertEquals(Optional.of(date), fetcher.get("/a", Optional.empty(), 1024).validator(), "validator");
        fetcher.get("/a", Optional.of(date), 1024);
        assertEquals(date, site.requests().get(1).ifModifiedSince(), "If-Modified-Since");
        assertNull(site.requests().get(1).ifNoneMatch(), "a date is not sent as an entity tag");
    }

    @Test
    public void returnsNoValidatorWhenTheServerSendsNone() throws IOException {
        site.serve("/a", BODY);
        assertEquals(Optional.empty(), fetcher.get("/a", Optional.empty(), 1024).validator(), "validator");
    }

    @Test
    public void returnsTheStatusOfAMissingPage() throws IOException {
        final HttpFetcher.Response response = fetcher.get("/missing/xml.zip", Optional.empty(), 1024);
        assertEquals(404, response.status(), "status");
        assertTrue(waits.isEmpty(), "404 is not retried");
    }

    @Test
    public void retriesThrottledResponsesAndHonoursRetryAfter() throws IOException {
        site.status("/a", 429, Map.of("Retry-After", "7"));
        assertEquals(429, fetcher.get("/a", Optional.empty(), 1024).status(), "status after the retries");
        assertEquals(List.of(7000L, 7000L, 7000L), waits, "three retries with the announced wait");
        assertEquals(4, site.requests("/a").size(), "one request and three retries");
    }

    @Test
    public void capsRetryAfterAndDefaultsWithoutIt() throws IOException {
        site.status("/a", 503, Map.of("Retry-After", "86400"));
        fetcher.get("/a", Optional.empty(), 1024);
        assertEquals(List.of(300000L, 300000L, 300000L), waits, "capped at five minutes");
        waits.clear();
        site.status("/b", 503, Map.of());
        fetcher.get("/b", Optional.empty(), 1024);
        assertEquals(List.of(1000L, 1000L, 1000L), waits, "one second without the header");
    }

    @Test
    public void doesNotFollowRedirects() throws IOException {
        site.serve("/target", BODY);
        site.status("/a", 302, Map.of("Location", "https://evil.example/a"));
        assertEquals(302, fetcher.get("/a", Optional.empty(), 1024).status(), "another host");
        site.status("/b", 301, Map.of("Location", "/target"));
        assertEquals(301, fetcher.get("/b", Optional.empty(), 1024).status(), "the same host");
        assertTrue(site.requests("/target").isEmpty(), "the redirect target is not requested");
    }

    @Test
    public void rejectsABodyBeyondTheLimit() throws IOException {
        site.serve("/a", new byte[2048]);
        assertThrows(IOException.class, () -> fetcher.get("/a", Optional.empty(), 2047), "beyond the limit");
        assertEquals(2048, fetcher.get("/a", Optional.empty(), 2048).body().length, "at the limit");
    }

    @Test
    public void replacesABlankUserAgent() throws IOException {
        site.serve("/a", BODY);
        try (HttpFetcher blank = fetcher("  ", Duration.ofSeconds(10)); HttpFetcher none = fetcher(null, Duration.ofSeconds(10))) {
            blank.get("/a", Optional.empty(), 1024);
            none.get("/a", Optional.empty(), 1024);
        }
        assertEquals(HttpFetcher.FALLBACK_USER_AGENT, site.requests().get(0).userAgent(), "blank");
        assertEquals(HttpFetcher.FALLBACK_USER_AGENT, site.requests().get(1).userAgent(), "null");
    }

    @Test
    public void rejectsAPathWithoutLeadingSlash() {
        assertThrows(IllegalArgumentException.class, () -> fetcher.get("evil.example/a", Optional.empty(), 1024), "relative path");
    }

    @Test
    public void endsABodyThatStalls() throws Exception {
        // The server sends the headers and half of the body, then stalls until the test ends.
        final CountDownLatch release = new CountDownLatch(1);
        final HttpServer stalling = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stalling.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 1000);
            final OutputStream out = exchange.getResponseBody();
            out.write(new byte[500]);
            out.flush();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        stalling.start();
        try (HttpFetcher impatient =
                new HttpFetcher("http://127.0.0.1:" + stalling.getAddress().getPort(), "test-agent", Duration.ofMillis(500))) {
            final long start = System.nanoTime();
            assertThrows(IOException.class, () -> impatient.get("/a", Optional.empty(), 4096), "stalled body");
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 20, "the read ends with the request timeout");
        } finally {
            release.countDown();
            stalling.stop(0);
        }
    }

    @Test
    public void recognizesEntityTags() {
        assertTrue(HttpFetcher.isEntityTag("\"72156-65c6835fce6bb\""), "strong");
        assertTrue(HttpFetcher.isEntityTag("W/\"x\""), "weak");
        assertTrue(!HttpFetcher.isEntityTag("Sat, 26 Sep 2026 19:55:12 GMT"), "a date");
    }
}
