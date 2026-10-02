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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A loopback HTTP server that stands in for gesetze-im-internet.de in tests.
 *
 * @author Oliver Flasch
 */
public final class FakeSite implements AutoCloseable {

    /**
     * One request the server received.
     *
     * @param path the request path
     * @param ifNoneMatch the If-None-Match header, or null
     * @param ifModifiedSince the If-Modified-Since header, or null
     * @param userAgent the User-Agent header, or null
     * @author Oliver Flasch
     */
    public record Request(String path, String ifNoneMatch, String ifModifiedSince, String userAgent) {
    }

    /**
     * What the server answers for one path.
     *
     * @param status the status code
     * @param body the response body
     * @param etag the ETag header, or null
     * @param headers further response headers
     * @author Oliver Flasch
     */
    private record Resource(int status, byte[] body, String etag, Map<String, String> headers) {
    }

    /** The server. */
    private final HttpServer server;

    /** The configured answers by path. */
    private final Map<String, Resource> resources = new ConcurrentHashMap<>();

    /** The received requests in order. */
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    /**
     * Starts the server on a free loopback port.
     */
    public FakeSite() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    /**
     * Returns the base URL of the server.
     *
     * @return the scheme and authority
     */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Serves a body with an ETag; a matching If-None-Match is answered with 304.
     *
     * @param path the path
     * @param body the body
     * @param etag the ETag, including its quotes
     */
    public void serve(final String path, final byte[] body, final String etag) {
        resources.put(path, new Resource(200, body, etag, Map.of()));
    }

    /**
     * Serves a body without validators.
     *
     * @param path the path
     * @param body the body
     */
    public void serve(final String path, final byte[] body) {
        resources.put(path, new Resource(200, body, null, Map.of()));
    }

    /**
     * Answers a path with a status and headers.
     *
     * @param path the path
     * @param status the status code
     * @param headers the response headers
     */
    public void status(final String path, final int status, final Map<String, String> headers) {
        resources.put(path, new Resource(status, new byte[0], null, headers));
    }

    /**
     * Serves a body with further headers.
     *
     * @param path the path
     * @param body the body
     * @param headers the response headers
     */
    public void serve(final String path, final byte[] body, final Map<String, String> headers) {
        resources.put(path, new Resource(200, body, null, headers));
    }

    /**
     * Stops answering a path; requests receive 404.
     *
     * @param path the path
     */
    public void remove(final String path) {
        resources.remove(path);
    }

    /**
     * Returns the received requests.
     *
     * @return the requests in order
     */
    public List<Request> requests() {
        return new ArrayList<>(requests);
    }

    /**
     * Returns the received requests for one path.
     *
     * @param path the path
     * @return the requests in order
     */
    public List<Request> requests(final String path) {
        return requests.stream().filter(request -> path.equals(request.path())).toList();
    }

    /**
     * Forgets the received requests.
     */
    public void clearRequests() {
        requests.clear();
    }

    /**
     * Answers one request.
     *
     * @param exchange the exchange
     * @throws IOException if the response cannot be written
     */
    private void handle(final HttpExchange exchange) throws IOException {
        final String path = exchange.getRequestURI().getPath();
        final String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        requests.add(new Request(path, ifNoneMatch, exchange.getRequestHeaders().getFirst("If-Modified-Since"),
                exchange.getRequestHeaders().getFirst("User-Agent")));
        final Resource resource = resources.get(path);
        try (exchange) {
            if (resource == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            resource.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            if (resource.etag() != null) {
                exchange.getResponseHeaders().add("ETag", resource.etag());
                if (resource.etag().equals(ifNoneMatch)) {
                    exchange.sendResponseHeaders(304, -1);
                    return;
                }
            }
            if (resource.body().length == 0) {
                exchange.sendResponseHeaders(resource.status(), -1);
                return;
            }
            exchange.sendResponseHeaders(resource.status(), resource.body().length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(resource.body());
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
