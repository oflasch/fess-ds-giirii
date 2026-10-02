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
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the table of contents of gesetze-im-internet.de.
 *
 * @author Oliver Flasch
 */
public final class TocParser {

    /** The pattern every archive link of the table of contents must match; group 1 is the slug. */
    private static final Pattern LINK_PATTERN = Pattern.compile("^https?://www\\.gesetze-im-internet\\.de/([a-z0-9_-]{1,100})/xml\\.zip$");

    /** The pattern a slug must match. */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9_-]{1,100}$");

    /**
     * Prevents instantiation.
     */
    private TocParser() {
    }

    /**
     * Returns whether the value is a valid slug.
     *
     * @param value the value to check
     * @return true when the value consists of 1 to 100 characters of {@code a-z}, {@code 0-9}, {@code _} and {@code -}
     */
    public static boolean isSlug(final String value) {
        return value != null && SLUG_PATTERN.matcher(value).matches();
    }

    /**
     * Parses the table of contents.
     * <p>
     * A slug that occurs more than once is returned once, at its first position.
     * </p>
     *
     * @param xml the bytes of the table of contents
     * @return the listed laws in document order; never empty
     * @throws IOException if the document is not well-formed, contains an item whose link does not
     *             match the archive URL pattern, or lists no law
     */
    public static List<LawRef> parse(final byte[] xml) throws IOException {
        final Map<String, LawRef> laws = new LinkedHashMap<>();
        try {
            final XMLStreamReader reader = StaxFactory.create().createXMLStreamReader(new ByteArrayInputStream(xml));
            try {
                int item = 0;
                String title = "";
                String link = null;
                boolean inItem = false;
                while (reader.hasNext()) {
                    final int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        final String name = reader.getLocalName();
                        if ("item".equals(name)) {
                            inItem = true;
                            item++;
                            title = "";
                            link = null;
                        } else if (inItem && "title".equals(name)) {
                            title = NormTextExtractor.extractInline(reader);
                        } else if (inItem && "link".equals(name)) {
                            link = NormTextExtractor.extractInline(reader);
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT && "item".equals(reader.getLocalName())) {
                        inItem = false;
                        final Matcher matcher = link == null ? null : LINK_PATTERN.matcher(link);
                        if (matcher == null || !matcher.matches()) {
                            throw new IOException("Item " + item + " of the table of contents has no valid archive link.");
                        }
                        final String slug = matcher.group(1);
                        laws.putIfAbsent(slug, new LawRef(slug, title));
                    }
                }
            } finally {
                reader.close();
            }
        } catch (final XMLStreamException e) {
            throw new IOException("The table of contents is not well-formed.", e);
        }
        if (laws.isEmpty()) {
            throw new IOException("The table of contents lists no law.");
        }
        return List.copyOf(new ArrayList<>(laws.values()));
    }
}
