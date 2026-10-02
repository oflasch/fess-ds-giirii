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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.StringReader;
import java.util.Set;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link NormTextExtractor}.
 *
 * @author Oliver Flasch
 */
public class NormTextExtractorTest {

    /**
     * Extracts the text of the root element of a fragment.
     *
     * @param xml the fragment
     * @param skipped the elements whose text is dropped
     * @return the extracted text
     * @throws XMLStreamException if the fragment is not well-formed
     */
    private static String extract(final String xml, final Set<String> skipped) throws XMLStreamException {
        final XMLStreamReader reader = StaxFactory.create().createXMLStreamReader(new StringReader(xml));
        while (reader.next() != XMLStreamConstants.START_ELEMENT) {
            // move to the root element
        }
        final String text = NormTextExtractor.extract(reader, skipped);
        assertEquals(XMLStreamConstants.END_ELEMENT, reader.getEventType(), "the reader stops at the end of the element");
        return text;
    }

    @Test
    public void separatesParagraphs() throws XMLStreamException {
        assertEquals("(1) Erster Absatz.\n(2) Zweiter Absatz.",
                extract("<Content><P>(1) Erster Absatz.</P><P>(2) Zweiter Absatz.</P></Content>", NormTextExtractor.CONTENT_SKIPPED),
                "paragraphs");
    }

    @Test
    public void rendersListsAndLineBreaks() throws XMLStreamException {
        assertEquals("Liste:\n1. erster Punkt,\n2. zweiter\nPunkt\nNach der Liste.", extract(
                "<Content><P>Liste: <DL><DT>1.</DT><DD><LA>erster Punkt,</LA></DD><DT>2.</DT><DD><LA>zweiter<BR/>Punkt</LA></DD></DL>Nach der Liste.</P></Content>",
                NormTextExtractor.CONTENT_SKIPPED), "list");
    }

    @Test
    public void rendersTableRowsAsLines() throws XMLStreamException {
        assertEquals("Zeichen Bedeutung\n205 Vorfahrt gewähren",
                extract("<Content><table><tgroup><tbody><row><entry>Zeichen</entry><entry>Bedeutung</entry></row>"
                        + "<row><entry>205</entry><entry><IMG SRC=\"a.jpg\"/>Vorfahrt gewähren</entry></row></tbody></tgroup></table></Content>",
                        NormTextExtractor.CONTENT_SKIPPED),
                "table");
    }

    @Test
    public void keepsInlineMarkupText() throws XMLStreamException {
        assertEquals("H2O und m2 sind fett und kursiv.",
                extract("<P>H<SUB>2</SUB>O und m<SUP>2</SUP> sind <B>fett</B> und <I>kursiv</I>.</P>", NormTextExtractor.CONTENT_SKIPPED),
                "inline");
    }

    @Test
    public void dropsSkippedElements() throws XMLStreamException {
        assertEquals("Text mit Verweis.",
                extract("<Content><P>Text<FnR ID=\"F1\"/> mit <noindex>nicht indexiert <B>auch nicht</B></noindex>Verweis.</P>"
                        + "<Footnotes><Footnote ID=\"F1\">Fußnote</Footnote></Footnotes></Content>", NormTextExtractor.CONTENT_SKIPPED),
                "skipped");
    }

    @Test
    public void neverSkipsTheRootElement() throws XMLStreamException {
        assertEquals("Erste\nZweite",
                extract("<Footnotes><Footnote>Erste</Footnote><Footnote>Zweite</Footnote></Footnotes>", NormTextExtractor.CONTENT_SKIPPED),
                "root");
    }

    @Test
    public void treatsSourceLineBreaksAsSpaces() throws XMLStreamException {
        assertEquals("ein Satz über zwei Zeilen", extract("<P>ein Satz\n    über\tzwei   Zeilen</P>", Set.of()), "source whitespace");
    }

    @Test
    public void extractsInlineTextAsOneLine() throws XMLStreamException {
        final XMLStreamReader reader = StaxFactory.create().createXMLStreamReader(new StringReader("<titel>Erste<BR/>Zeile</titel>"));
        reader.next();
        assertEquals("Erste Zeile", NormTextExtractor.extractInline(reader), "inline");
    }

    @Test
    public void normalizesText() {
        assertEquals("a b\nc", NormTextExtractor.normalize("  a \u0000 b\u0007 \n\n \n\tc  "), "control characters and spaces");
        assertEquals("", NormTextExtractor.normalize(" \n \n"), "only whitespace");
        assertEquals("", NormTextExtractor.normalize(""), "empty");
    }
}
