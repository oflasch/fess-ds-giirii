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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the XML document of a law ({@code gii-norm.dtd}).
 *
 * @author Oliver Flasch
 */
public final class LawParser {

    /** The pattern a document number must match; it becomes part of a URL. */
    private static final Pattern DOKNR_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** The format of the {@code builddate} attribute. */
    private static final DateTimeFormatter BUILDDATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** The time zone the {@code builddate} attribute is stated in. */
    private static final ZoneId BUILDDATE_ZONE = ZoneId.of("Europe/Berlin");

    /** The separator between the structural units of a breadcrumb. */
    private static final String PATH_SEPARATOR = " > ";

    /**
     * Prevents instantiation.
     */
    private LawParser() {
    }

    /**
     * The values of one {@code norm} element while it is read.
     *
     * @author Oliver Flasch
     */
    private static final class Norm {

        /** The document number of the norm. */
        private String doknr = "";

        /** The build date attribute as stated. */
        private String builddate = "";

        /** The first abbreviation of the law. */
        private String jurabk = "";

        /** The official abbreviation. */
        private String amtabk = "";

        /** The promulgation date. */
        private String date = "";

        /** The long title. */
        private String longTitle = "";

        /** The short title. */
        private String shortTitle = "";

        /** The section label. */
        private String label = "";

        /** The section title. */
        private String title = "";

        /** The outline number of a structural unit; empty for other norms. */
        private String outlineNumber = "";

        /** The designation of a structural unit, such as {@code Buch 1}. */
        private String outlineLabel = "";

        /** The title of a structural unit. */
        private String outlineTitle = "";

        /** Whether the norm is a structural unit. */
        private boolean structural;

        /** The status comments. */
        private final List<String> status = new ArrayList<>();

        /** The plain text of the norm. */
        private String content = "";

        /** The plain text of the footnotes. */
        private final StringBuilder footnotes = new StringBuilder();

        /**
         * Creates an empty norm.
         */
        private Norm() {
        }
    }

    /**
     * A structural unit on the breadcrumb stack.
     *
     * @param outlineNumber the outline number that defines the nesting
     * @param text the designation and title of the unit
     * @author Oliver Flasch
     */
    private record Unit(String outlineNumber, String text) {
    }

    /**
     * Parses a law.
     *
     * @param xml the bytes of the XML document
     * @return the law with at least one indexable unit
     * @throws IOException if the document is not well-formed, contains no norm, states no
     *             abbreviation, or carries an invalid or repeated document number
     */
    public static Law parse(final byte[] xml) throws IOException {
        final List<Norm> norms = new ArrayList<>();
        String rootDoknr = "";
        try {
            final XMLStreamReader reader = StaxFactory.create().createXMLStreamReader(new ByteArrayInputStream(xml));
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                        final String name = reader.getLocalName();
                        if ("dokumente".equals(name)) {
                            rootDoknr = attribute(reader, "doknr");
                        } else if ("norm".equals(name)) {
                            norms.add(readNorm(reader));
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (final XMLStreamException e) {
            throw new IOException("The law document is not well-formed.", e);
        }
        return toLaw(rootDoknr, norms);
    }

    /**
     * Builds the law from its norms.
     *
     * @param rootDoknr the document number of the root element; may be empty
     * @param norms the norms in document order
     * @return the law
     * @throws IOException if the norms do not form a valid law
     */
    private static Law toLaw(final String rootDoknr, final List<Norm> norms) throws IOException {
        if (norms.isEmpty()) {
            throw new IOException("The law document contains no norm.");
        }
        // 1. the first norm carries the metadata of the law
        final Norm header = norms.get(0);
        final String lawDoknr = rootDoknr.isEmpty() ? header.doknr : rootDoknr;
        requireDoknr(lawDoknr);
        if (header.jurabk.isEmpty()) {
            throw new IOException("The law document states no abbreviation.");
        }

        // 2. every norm with a label is a section; structural units build the breadcrumb
        final Deque<Unit> units = new ArrayDeque<>();
        final List<Section> sections = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (final Norm norm : norms) {
            final boolean section = !norm.label.isEmpty();
            // A section can state the structural unit it is numbered within. The unit then
            // usually precedes it as a norm of its own, which carries the title of the unit.
            final boolean knownUnit = section && !units.isEmpty() && units.peekLast().outlineNumber().equals(norm.outlineNumber);
            if (norm.structural && !knownUnit) {
                while (!units.isEmpty() && units.peekLast().outlineNumber().length() >= norm.outlineNumber.length()) {
                    units.removeLast();
                }
                units.addLast(new Unit(norm.outlineNumber, join(" ", norm.outlineLabel, norm.outlineTitle)));
            }
            if (section) {
                requireDoknr(norm.doknr);
                if (!seen.add(norm.doknr)) {
                    throw new IOException("The law document repeats a document number.");
                }
                sections.add(new Section(norm.doknr, norm.label, norm.structural ? norm.outlineLabel : "", norm.title,
                        norm.jurabk.isEmpty() ? header.jurabk : norm.jurabk, norm.content, norm.footnotes.toString(), path(units),
                        builddate(norm.builddate), false));
            }
        }

        // 3. a law without sections is indexed as a single law-level unit
        if (sections.isEmpty()) {
            final String content = join("\n", String.join("\n", header.status), header.footnotes.toString());
            sections.add(new Section(lawDoknr, "", "", "", header.jurabk, content, header.footnotes.toString(), "",
                    builddate(header.builddate), true));
        }
        return new Law(lawDoknr, header.jurabk, header.amtabk, header.longTitle, header.shortTitle, header.date, header.status, sections);
    }

    /**
     * Reads one {@code norm} element.
     *
     * @param reader the reader, positioned at the start of the norm
     * @return the values of the norm
     * @throws XMLStreamException if the document is not well-formed
     */
    private static Norm readNorm(final XMLStreamReader reader) throws XMLStreamException {
        final Norm norm = new Norm();
        norm.doknr = attribute(reader, "doknr");
        norm.builddate = attribute(reader, "builddate");
        while (true) {
            final int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                return norm;
            }
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                case "metadaten" -> readMetadata(reader, norm);
                case "textdaten" -> readTextData(reader, norm);
                default -> NormTextExtractor.skip(reader);
                }
            }
        }
    }

    /**
     * Reads the {@code metadaten} element of a norm.
     *
     * @param reader the reader, positioned at the start of the element
     * @param norm the norm that receives the values
     * @throws XMLStreamException if the document is not well-formed
     */
    private static void readMetadata(final XMLStreamReader reader, final Norm norm) throws XMLStreamException {
        while (true) {
            final int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                return;
            }
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                case "jurabk" -> {
                    final String value = NormTextExtractor.extractInline(reader);
                    if (norm.jurabk.isEmpty()) {
                        norm.jurabk = value;
                    }
                }
                case "amtabk" -> norm.amtabk = NormTextExtractor.extractInline(reader);
                case "ausfertigung-datum" -> norm.date = NormTextExtractor.extractInline(reader);
                case "langue" -> norm.longTitle = NormTextExtractor.extractInline(reader);
                case "kurzue" -> norm.shortTitle = NormTextExtractor.extractInline(reader);
                case "enbez" -> norm.label = NormTextExtractor.extractInline(reader);
                case "titel" -> norm.title = NormTextExtractor.extractInline(reader);
                case "gliederungseinheit" -> readOutline(reader, norm);
                case "standangabe" -> readStatus(reader, norm);
                default -> NormTextExtractor.skip(reader);
                }
            }
        }
    }

    /**
     * Reads the {@code gliederungseinheit} element that marks a structural unit.
     *
     * @param reader the reader, positioned at the start of the element
     * @param norm the norm that receives the values
     * @throws XMLStreamException if the document is not well-formed
     */
    private static void readOutline(final XMLStreamReader reader, final Norm norm) throws XMLStreamException {
        norm.structural = true;
        while (true) {
            final int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                return;
            }
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                case "gliederungskennzahl" -> norm.outlineNumber = NormTextExtractor.extractInline(reader);
                case "gliederungsbez" -> norm.outlineLabel = NormTextExtractor.extractInline(reader);
                case "gliederungstitel" -> norm.outlineTitle = NormTextExtractor.extractInline(reader);
                default -> NormTextExtractor.skip(reader);
                }
            }
        }
    }

    /**
     * Reads one {@code standangabe} element and keeps its comment.
     *
     * @param reader the reader, positioned at the start of the element
     * @param norm the norm that receives the comment
     * @throws XMLStreamException if the document is not well-formed
     */
    private static void readStatus(final XMLStreamReader reader, final Norm norm) throws XMLStreamException {
        while (true) {
            final int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                return;
            }
            if (event == XMLStreamConstants.START_ELEMENT) {
                if ("standkommentar".equals(reader.getLocalName())) {
                    final String comment = NormTextExtractor.extractInline(reader);
                    if (!comment.isEmpty()) {
                        norm.status.add(comment);
                    }
                } else {
                    NormTextExtractor.skip(reader);
                }
            }
        }
    }

    /**
     * Reads the {@code textdaten} element of a norm: its text and its footnotes.
     *
     * @param reader the reader, positioned at the start of the element
     * @param norm the norm that receives the text
     * @throws XMLStreamException if the document is not well-formed
     */
    private static void readTextData(final XMLStreamReader reader, final Norm norm) throws XMLStreamException {
        while (true) {
            final int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                return;
            }
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                case "text" -> readText(reader, norm);
                case "fussnoten" -> appendFootnotes(norm, NormTextExtractor.extract(reader, NormTextExtractor.FOOTNOTE_SKIPPED));
                default -> NormTextExtractor.skip(reader);
                }
            }
        }
    }

    /**
     * Reads the {@code text} element of a norm.
     *
     * @param reader the reader, positioned at the start of the element
     * @param norm the norm that receives the text
     * @throws XMLStreamException if the document is not well-formed
     */
    private static void readText(final XMLStreamReader reader, final Norm norm) throws XMLStreamException {
        while (true) {
            final int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                return;
            }
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                case "Content", "TOC" -> norm.content =
                        join("\n", norm.content, NormTextExtractor.extract(reader, NormTextExtractor.CONTENT_SKIPPED));
                case "Footnotes" -> appendFootnotes(norm, NormTextExtractor.extract(reader, NormTextExtractor.FOOTNOTE_SKIPPED));
                default -> NormTextExtractor.skip(reader);
                }
            }
        }
    }

    /**
     * Appends footnote text to a norm.
     *
     * @param norm the norm that receives the text
     * @param text the footnote text; may be empty
     */
    private static void appendFootnotes(final Norm norm, final String text) {
        if (!text.isEmpty()) {
            if (norm.footnotes.length() > 0) {
                norm.footnotes.append('\n');
            }
            norm.footnotes.append(text);
        }
    }

    /**
     * Returns an attribute of the current start element.
     *
     * @param reader the reader, positioned at a start element
     * @param name the local name of the attribute
     * @return the trimmed attribute value; empty when the attribute is absent
     */
    private static String attribute(final XMLStreamReader reader, final String name) {
        final String value = reader.getAttributeValue(null, name);
        return value == null ? "" : value.trim();
    }

    /**
     * Verifies that a document number is safe to use in a URL.
     *
     * @param doknr the document number
     * @throws IOException if the document number does not match the expected pattern
     */
    private static void requireDoknr(final String doknr) throws IOException {
        if (!DOKNR_PATTERN.matcher(doknr).matches()) {
            throw new IOException("The law document carries an invalid document number.");
        }
    }

    /**
     * Parses a build date.
     *
     * @param value the attribute value in the form {@code yyyyMMddHHmmss}
     * @return the instant, or empty when the value is absent or malformed
     */
    private static Optional<Instant> builddate(final String value) {
        try {
            return Optional.of(LocalDateTime.parse(value, BUILDDATE_FORMAT).atZone(BUILDDATE_ZONE).toInstant());
        } catch (final DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * Renders the breadcrumb of the current structural units.
     *
     * @param units the enclosing units, outermost first
     * @return the breadcrumb; empty when there is no enclosing unit
     */
    private static String path(final Deque<Unit> units) {
        final StringBuilder path = new StringBuilder();
        for (final Iterator<Unit> it = units.iterator(); it.hasNext();) {
            final String text = it.next().text();
            if (!text.isEmpty()) {
                if (path.length() > 0) {
                    path.append(PATH_SEPARATOR);
                }
                path.append(text);
            }
        }
        return path.toString();
    }

    /**
     * Joins the non-empty values.
     *
     * @param separator the separator between two values
     * @param values the values; empty ones are left out
     * @return the joined text
     */
    private static String join(final String separator, final String... values) {
        final StringBuilder joined = new StringBuilder();
        for (final String value : values) {
            if (!value.isEmpty()) {
                if (joined.length() > 0) {
                    joined.append(separator);
                }
                joined.append(value);
            }
        }
        return joined.toString();
    }
}
