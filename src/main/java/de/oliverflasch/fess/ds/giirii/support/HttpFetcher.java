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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Sends GET requests to one site.
 * <p>
 * Every request targets the base URI given at construction; the caller supplies the path only.
 * Redirects are not followed, response bodies are read up to a limit, and throttled responses
 * are retried.
 * </p>
 *
 * @author Oliver Flasch
 */
public class HttpFetcher implements AutoCloseable {

    /** The logger. */
    private static final Logger logger = LogManager.getLogger(HttpFetcher.class);

    /** The User-Agent sent when the configured one is blank. */
    public static final String FALLBACK_USER_AGENT = "Mozilla/5.0 (compatible; Fess; +https://github.com/oflasch/fess-ds-giirii)";

    /** The time allowed for establishing a connection. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /** The default time allowed for the response headers, and again for the response body. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(120);

    /** The number of times a throttled response (429 or 503) is retried. */
    private static final int MAX_RETRIES = 3;

    /** The wait before a retry when the response carries no usable Retry-After header. */
    private static final long DEFAULT_RETRY_WAIT = 1000L;

    /** The longest Retry-After wait that is honoured. */
    private static final long MAX_RETRY_WAIT = 5L * 60L * 1000L;

    /** The largest part of an error response body that is read before the body is closed. */
    private static final int MAX_DRAIN_BYTES = 4096;

    /** The size of the buffer a response body is read through. */
    private static final int BUFFER_SIZE = 8192;

    /** The scheme and authority all requests are sent to, without a trailing slash. */
    private final String baseUrl;

    /** The User-Agent header value. */
    private final String userAgent;

    /** The time allowed for the response headers, and again for the response body. */
    private final Duration requestTimeout;

    /** The HTTP client. */
    private final HttpClient httpClient;

    /** Closes response bodies that exceed the request timeout. */
    private final ScheduledExecutorService watchdog;

    /**
     * The outcome of a request.
     *
     * @param status the HTTP status code
     * @param validator the {@code ETag} of a 200 response, or its {@code Last-Modified} when it has no {@code ETag}
     * @param body the response body of a 200 response; empty for every other status
     * @author Oliver Flasch
     */
    public record Response(int status, Optional<String> validator, byte[] body) {
    }

    /**
     * Creates a fetcher.
     *
     * @param baseUrl the scheme and authority all requests are sent to, such as {@code https://www.gesetze-im-internet.de}
     * @param userAgent the User-Agent header value; a blank value is replaced by {@link #FALLBACK_USER_AGENT}
     * @param requestTimeout the time allowed for the response headers, and again for the response body
     */
    public HttpFetcher(final String baseUrl, final String userAgent, final Duration requestTimeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.userAgent = userAgent == null || userAgent.isBlank() ? FALLBACK_USER_AGENT : userAgent.trim();
        this.requestTimeout = requestTimeout;
        httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(CONNECT_TIMEOUT).build();
        watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "giirii-http-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Requests a path of the site.
     *
     * @param path the absolute path, starting with {@code /}
     * @param validator the validator of the copy the caller holds; sent as {@code If-None-Match}
     *            when it is an {@code ETag} and as {@code If-Modified-Since} otherwise
     * @param maxBytes the maximum size of the response body in bytes
     * @return the status, and for a 200 response the validator and the body
     * @throws IOException if the request cannot be sent, the body exceeds {@code maxBytes} or the
     *             request timeout, or the thread is interrupted
     */
    public Response get(final String path, final Optional<String> validator, final long maxBytes) throws IOException {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("The path must start with a slash.");
        }
        final URI uri = URI.create(baseUrl + path);
        for (int attempt = 0;; attempt++) {
            final HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(requestTimeout).header("User-Agent", userAgent).GET();
            validator.ifPresent(value -> builder.header(isEntityTag(value) ? "If-None-Match" : "If-Modified-Since", value));
            final HttpResponse<InputStream> response;
            try {
                response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while requesting " + path, e);
            }
            final int status = response.statusCode();
            if (status == 200) {
                return new Response(status, validatorOf(response), readBody(response.body(), maxBytes, path));
            }
            drain(response);
            if ((status == 429 || status == 503) && attempt < MAX_RETRIES) {
                final long waitMillis = getRetryWait(response);
                logger.warn("HTTP {} from {}. Retrying in {} ms ({}/{}).", status, path, waitMillis, attempt + 1, MAX_RETRIES);
                sleep(waitMillis, path);
                continue;
            }
            return new Response(status, Optional.empty(), new byte[0]);
        }
    }

    /**
     * Returns whether a validator is an entity tag.
     *
     * @param validator the stored validator
     * @return true when the validator starts with a quote or with the weak prefix {@code W/}
     */
    public static boolean isEntityTag(final String validator) {
        return validator.startsWith("\"") || validator.startsWith("W/");
    }

    /**
     * Returns the validator of a response.
     *
     * @param response the response
     * @return the {@code ETag}, else the {@code Last-Modified} value, else empty
     */
    private static Optional<String> validatorOf(final HttpResponse<InputStream> response) {
        final Optional<String> entityTag = response.headers().firstValue("ETag").map(String::trim).filter(HttpFetcher::isEntityTag);
        if (entityTag.isPresent()) {
            return entityTag;
        }
        return response.headers().firstValue("Last-Modified").map(String::trim).filter(value -> !value.isEmpty());
    }

    /**
     * Reads a response body up to a limit and within the request timeout.
     *
     * @param body the response body; closed by this method
     * @param maxBytes the maximum size in bytes
     * @param path the requested path, named in exception messages
     * @return the bytes of the body
     * @throws IOException if the body exceeds {@code maxBytes}, is not complete within the request
     *             timeout, or cannot be read
     */
    private byte[] readBody(final InputStream body, final long maxBytes, final String path) throws IOException {
        // Closing the stream from the watchdog thread ends a read that blocks on a stalled server.
        final ScheduledFuture<?> timeout = watchdog.schedule(() -> closeQuietly(body), requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        try (body) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[BUFFER_SIZE];
            long total = 0;
            int read;
            while ((read = body.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("The response body of " + path + " exceeds " + maxBytes + " bytes.");
                }
                out.write(buffer, 0, read);
            }
            if (timeout.isDone()) {
                throw new IOException("The response body of " + path + " was not complete within the request timeout.");
            }
            return out.toByteArray();
        } catch (final IOException e) {
            if (timeout.isDone()) {
                throw new IOException("The response body of " + path + " was not complete within the request timeout.", e);
            }
            throw e;
        } finally {
            timeout.cancel(false);
        }
    }

    /**
     * Closes a stream and ignores a failure to do so.
     *
     * @param stream the stream to close
     */
    private static void closeQuietly(final InputStream stream) {
        try {
            stream.close();
        } catch (final IOException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to close a response body.", e);
            }
        }
    }

    /**
     * Reads and discards a small part of an error response body, then closes it.
     *
     * @param response the response whose body is discarded
     */
    private void drain(final HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            final byte[] buffer = new byte[MAX_DRAIN_BYTES];
            int total = 0;
            int read;
            while (total < MAX_DRAIN_BYTES && (read = body.read(buffer, total, MAX_DRAIN_BYTES - total)) != -1) {
                total += read;
            }
        } catch (final IOException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to drain a response body.", e);
            }
        }
    }

    /**
     * Returns how long to wait before a retry, based on the Retry-After header.
     *
     * @param response the throttled response
     * @return the wait in milliseconds, between 0 and {@link #MAX_RETRY_WAIT}
     */
    private long getRetryWait(final HttpResponse<InputStream> response) {
        return response.headers().firstValue("Retry-After").map(value -> {
            try {
                return Math.clamp(Long.parseLong(value.trim()) * 1000L, 0L, MAX_RETRY_WAIT);
            } catch (final NumberFormatException e) {
                return DEFAULT_RETRY_WAIT;
            }
        }).orElse(DEFAULT_RETRY_WAIT);
    }

    /**
     * Waits before a retry.
     *
     * @param millis the wait in milliseconds
     * @param path the requested path, named in the exception message
     * @throws IOException if the thread is interrupted while waiting
     */
    protected void sleep(final long millis, final String path) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while requesting " + path, e);
        }
    }

    /**
     * Stops the watchdog thread and releases the HTTP client.
     */
    @Override
    public void close() {
        watchdog.shutdownNow();
        httpClient.shutdownNow();
    }
}
