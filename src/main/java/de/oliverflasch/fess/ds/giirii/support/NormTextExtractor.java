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

import java.util.Set;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Converts the text markup of a norm to plain text.
 *
 * @author Oliver Flasch
 */
public final class NormTextExtractor {

    /** The elements that start on a new line. */
    private static final Set<String> BREAK_BEFORE =
            Set.of("P", "DL", "DT", "table", "row", "pre", "Title", "Subtitle", "Ident", "Footnote");

    /** The elements whose end is a line break. A list label ({@code DT}) is absent: it shares the line with its item. */
    private static final Set<String> BREAK_AFTER =
            Set.of("P", "DL", "DD", "LA", "table", "row", "pre", "Title", "Subtitle", "Ident", "Footnote");

    /** The elements that contribute no text to the content of a norm. */
    public static final Set<String> CONTENT_SKIPPED = Set.of("noindex", "IMG", "FILE", "FnR", "Footnotes", "fussnoten");

    /** The elements that contribute no text to the footnotes of a norm. */
    public static final Set<String> FOOTNOTE_SKIPPED = Set.of("IMG", "FILE");

    /**
     * Prevents instantiation.
     */
    private NormTextExtractor() {
    }

    /**
     * Extracts the plain text of the element the reader is positioned at.
     * <p>
     * The reader must be positioned at a start element and is left at the matching end element.
     * Block elements stand on lines of their own, a list label shares the line with its item, table
     * cells are separated by a space, and the text of the skipped elements is dropped. The element the reader starts at is never skipped.
     * </p>
     *
     * @param reader the reader, positioned at a start element
     * @param skipped the local names of the elements whose text is dropped
     * @return the normalized plain text; see {@link #normalize(String)}
     * @throws XMLStreamException if the document is not well-formed
     */
    public static String extract(final XMLStreamReader reader, final Set<String> skipped) throws XMLStreamException {
        final StringBuilder text = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            final int event = reader.next();
            switch (event) {
            case XMLStreamConstants.START_ELEMENT -> {
                final String name = reader.getLocalName();
                if (skipped.contains(name)) {
                    skip(reader);
                } else {
                    depth++;
                    if ("BR".equals(name) || BREAK_BEFORE.contains(name)) {
                        text.append('\n');
                    } else if ("entry".equals(name)) {
                        text.append(' ');
                    }
                }
            }
            case XMLStreamConstants.END_ELEMENT -> {
                depth--;
                if (depth > 0) {
                    final String name = reader.getLocalName();
                    if (BREAK_AFTER.contains(name)) {
                        text.append('\n');
                    } else if ("DT".equals(name)) {
                        text.append(' ');
                    }
                }
            }
            case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> appendInline(text, reader.getText());
            default -> {
                // comments and processing instructions carry no text
            }
            }
        }
        return normalize(text.toString());
    }

    /**
     * Extracts the text of the element the reader is positioned at as a single line.
     *
     * @param reader the reader, positioned at a start element
     * @return the text with all line breaks replaced by spaces
     * @throws XMLStreamException if the document is not well-formed
     */
    public static String extractInline(final XMLStreamReader reader) throws XMLStreamException {
        return extract(reader, Set.of()).replace('\n', ' ');
    }

    /**
     * Moves the reader from a start element to its matching end element.
     *
     * @param reader the reader, positioned at a start element
     * @throws XMLStreamException if the document is not well-formed
     */
    public static void skip(final XMLStreamReader reader) throws XMLStreamException {
        int depth = 1;
        while (depth > 0) {
            final int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    /**
     * Appends character data, turning the line breaks and tabs of the XML source into spaces.
     * Only markup produces line breaks in the result.
     *
     * @param text the buffer to append to
     * @param characters the character data of a text node
     */
    private static void appendInline(final StringBuilder text, final String characters) {
        for (int i = 0; i < characters.length(); i++) {
            final char c = characters.charAt(i);
            text.append(c == '\n' || c == '\r' || c == '\t' ? ' ' : c);
        }
    }

    /**
     * Normalizes extracted text: removes control characters other than the line break, collapses
     * runs of spaces, trims every line and drops empty lines.
     *
     * @param raw the extracted text
     * @return the normalized text, lines separated by a single line break
     */
    public static String normalize(final String raw) {
        final StringBuilder result = new StringBuilder(raw.length());
        final StringBuilder line = new StringBuilder();
        for (int i = 0; i <= raw.length(); i++) {
            final char c = i < raw.length() ? raw.charAt(i) : '\n';
            if (c == '\n') {
                // 1. a line ends: trim its trailing space and keep it when it has text
                final int end = line.length() > 0 && line.charAt(line.length() - 1) == ' ' ? line.length() - 1 : line.length();
                if (end > 0) {
                    if (result.length() > 0) {
                        result.append('\n');
                    }
                    result.append(line, 0, end);
                }
                line.setLength(0);
            } else if (c == ' ' || c == '\t' || c == ' ') {
                // 2. a space is kept once, and never at the start of a line
                if (line.length() > 0 && line.charAt(line.length() - 1) != ' ') {
                    line.append(' ');
                }
            } else if (Character.getType(c) != Character.CONTROL) {
                line.append(c);
            }
        }
        return result.toString();
    }
}
