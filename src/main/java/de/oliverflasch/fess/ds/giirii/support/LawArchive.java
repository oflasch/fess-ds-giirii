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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts the XML document from a law archive.
 * <p>
 * The archive is read in memory. Entry names select the entry and are never used as file system
 * paths.
 * </p>
 *
 * @author Oliver Flasch
 */
public final class LawArchive {

    /** The size of the buffer the entry is inflated through. */
    private static final int BUFFER_SIZE = 8192;

    /**
     * Prevents instantiation.
     */
    private LawArchive() {
    }

    /**
     * Returns the content of the first entry whose name ends with {@code .xml}.
     *
     * @param zip the bytes of the archive
     * @param maxXmlSize the maximum size of the inflated entry in bytes
     * @return the bytes of the XML entry
     * @throws IOException if the archive is malformed, contains no XML entry, or the entry
     *             inflates beyond {@code maxXmlSize}
     */
    public static byte[] extractXml(final byte[] zip, final long maxXmlSize) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".xml")) {
                    return inflate(in, maxXmlSize);
                }
            }
        }
        throw new IOException("The archive contains no XML entry.");
    }

    /**
     * Reads the current entry while enforcing the size limit.
     *
     * @param in the archive stream, positioned at the entry
     * @param maxXmlSize the maximum size of the inflated entry in bytes
     * @return the inflated bytes
     * @throws IOException if the entry is malformed or inflates beyond {@code maxXmlSize}
     */
    private static byte[] inflate(final ZipInputStream in, final long maxXmlSize) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxXmlSize) {
                throw new IOException("The XML entry exceeds " + maxXmlSize + " bytes.");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
