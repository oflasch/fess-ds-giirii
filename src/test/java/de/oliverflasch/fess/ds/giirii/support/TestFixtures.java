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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Loads the test fixtures and builds archives from them.
 *
 * @author Oliver Flasch
 */
public final class TestFixtures {

    /**
     * Prevents instantiation.
     */
    private TestFixtures() {
    }

    /**
     * Reads a fixture.
     *
     * @param name the file name below {@code fixtures/}
     * @return the bytes of the fixture
     */
    public static byte[] bytes(final String name) {
        try (InputStream in = TestFixtures.class.getClassLoader().getResourceAsStream("fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture named " + name);
            }
            return in.readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads a fixture as text.
     *
     * @param name the file name below {@code fixtures/}
     * @return the text of the fixture, decoded as UTF-8
     */
    public static String text(final String name) {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }

    /**
     * Builds an archive with one entry.
     *
     * @param entryName the name of the entry
     * @param content the content of the entry
     * @return the bytes of the archive
     */
    public static byte[] zip(final String entryName, final byte[] content) {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(entryName, content);
        return zip(entries);
    }

    /**
     * Builds an archive.
     *
     * @param entries the entries in the order they are written: name to content
     * @return the bytes of the archive
     */
    public static byte[] zip(final Map<String, byte[]> entries) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (final Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
