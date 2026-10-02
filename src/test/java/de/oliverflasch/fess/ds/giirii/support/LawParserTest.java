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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link LawParser} against trimmed copies of real law documents.
 *
 * @author Oliver Flasch
 */
public class LawParserTest {

    /**
     * Wraps norms into a law document.
     *
     * @param norms the norm elements
     * @return the bytes of the document
     */
    private static byte[] law(final String norms) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><dokumente doknr=\"BJNR1\">" + norms + "</dokumente>")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** A minimal header norm. */
    private static final String HEADER = "<norm doknr=\"BJNR1\"><metadaten><jurabk>X</jurabk></metadaten></norm>";

    /**
     * Returns the section with the given label.
     *
     * @param law the law
     * @param label the section label
     * @return the first section with that label
     */
    private static Section section(final Law law, final String label) {
        return law.sections().stream().filter(section -> label.equals(section.label())).findFirst().orElseThrow();
    }

    @Test
    public void readsTheMetadataOfTheLaw() throws IOException {
        final Law law = LawParser.parse(TestFixtures.bytes("gg.xml"));
        assertEquals("BJNR000010949", law.doknr(), "doknr");
        assertEquals("GG", law.jurabk(), "jurabk");
        assertEquals("Grundgesetz für die Bundesrepublik Deutschland", law.longTitle(), "long title");
        assertEquals("1949-05-23", law.date(), "date");
        assertEquals(List.of("Zuletzt geändert durch Art. 1 G v. 22.3.2025 I Nr. 94"), law.status(), "status");
        assertFalse(law.isLawLevelOnly(), "the law has sections");
    }

    @Test
    public void readsOneSectionPerLabelledNorm() throws IOException {
        final Law law = LawParser.parse(TestFixtures.bytes("gg.xml"));
        assertEquals(List.of("Eingangsformel", "Präambel", "Art 1", "Art 2", "Art 3", "Art 4", "Art 5", "Art 6", "Art 7", "Art 8"),
                law.sections().stream().map(Section::label).toList(), "labels");
        final Section art1 = section(law, "Art 1");
        assertEquals("BJNR000010949BJNE001700314", art1.doknr(), "doknr");
        assertEquals("GG", art1.jurabk(), "jurabk");
        assertEquals("", art1.title(), "title");
        assertEquals("", art1.unit(), "unit");
        assertEquals("I. Die Grundrechte", art1.path(), "path");
        assertTrue(art1.content().startsWith("(1) Die Würde des Menschen ist unantastbar."), "content start");
        assertEquals(3, art1.content().split("\n").length, "one line per paragraph");
        assertEquals(Optional.of(Instant.parse("2026-05-06T15:46:50Z")), art1.builddate(), "builddate in Europe/Berlin");
        assertEquals("", section(law, "Präambel").path(), "a section before the first structural unit has no path");
    }

    @Test
    public void nestsStructuralUnitsByOutlineNumber() throws IOException {
        final Law law = LawParser.parse(TestFixtures.bytes("bgb.xml"));
        assertEquals("Buch 1 Allgemeiner Teil > Abschnitt 1 Personen > Titel 1 Natürliche Personen, Verbraucher, Unternehmer",
                section(law, "§ 1").path(), "path of § 1");
        assertEquals("Buch 2 Recht der Schuldverhältnisse > Abschnitt 8 Einzelne Schuldverhältnisse > Titel 1 Kauf, Tausch"
                + " > Untertitel 1 Allgemeine Vorschriften", section(law, "§ 433").path(), "path of § 433");
        final Section section433 = section(law, "§ 433");
        assertEquals("Vertragstypische Pflichten beim Kaufvertrag", section433.title(), "title");
        assertTrue(section433.content().contains("(2) Der Käufer ist verpflichtet"), "content");
        assertEquals("(weggefallen)", section(law, "(XXXX) §§ 3 bis 6").title(), "repealed range");
    }

    @Test
    public void keepsTheUnitOfSectionsWithRepeatedLabels() throws IOException {
        final Law law = LawParser.parse(TestFixtures.bytes("bgbeg.xml"));
        assertEquals(List.of("§ 1", "§ 2", "§ 1", "§ 2"), law.sections().stream().map(Section::label).toList(), "labels");
        assertEquals(List.of("Art 224", "Art 224", "Art 229", "Art 229"), law.sections().stream().map(Section::unit).toList(), "units");
        assertEquals("Art 224 Übergangsvorschrift zum Kindschaftsrechtsreformgesetz vom 16. Dezember 1997", law.sections().get(0).path(),
                "the path keeps the title of the unit");
        assertEquals("Art 229 Weitere Überleitungsvorschriften", law.sections().get(3).path(), "path of the second unit");
        assertEquals(4, law.sections().stream().map(Section::doknr).distinct().count(), "document numbers are unique");
    }

    @Test
    public void extractsTablesWithoutImagesAndUnindexedText() throws IOException {
        final Law law = LawParser.parse(TestFixtures.bytes("stvo.xml"));
        assertEquals("StVO 2013", law.jurabk(), "the first abbreviation");
        final Section section31 = section(law, "§ 31");
        assertTrue(section31.content().contains("(2) Durch das Zusatzzeichen\nwird das Inline-Skaten"), "an image-only table adds no text");
        assertFalse(section31.content().contains("jpg"), "image names are not indexed");
        final Section annex = section(law, "Anlage 1");
        assertEquals("(zu § 40 Absatz 6 und 7) Allgemeine und Besondere Gefahrzeichen", annex.title(), "a title is a single line");
        assertFalse(annex.content().contains("Fundstelle"), "noindex text is dropped");
        assertTrue(annex.content().contains("Gefahrstelle"), "table text is indexed");
    }

    @Test
    public void turnsALawWithoutSectionsIntoOneUnit() throws IOException {
        final Law law = LawParser.parse(TestFixtures.bytes("header-only.xml"));
        assertTrue(law.isLawLevelOnly(), "law level only");
        final Section unit = law.sections().get(0);
        assertEquals("BJNR546100002", unit.doknr(), "doknr");
        assertEquals("", unit.label(), "label");
        assertTrue(unit.lawLevel(), "law level");
        assertEquals("Überschrift: Gem. § 3 Abs. 2 G v. 10.7.1958 I 437 114-2 nur mit der Überschrift aufgenommen.", unit.content(),
                "the footnote text is the content");
    }

    @Test
    public void separatesFootnotesFromContent() throws IOException {
        final Law law = LawParser.parse(law(HEADER
                + "<norm doknr=\"BJNR1BJNE1\"><metadaten><jurabk>X</jurabk><enbez>§ 1</enbez></metadaten>"
                + "<textdaten><text><Content><P>Text<FnR ID=\"F1\"/></P></Content><Footnotes><Footnote ID=\"F1\">Erste</Footnote></Footnotes></text>"
                + "<fussnoten><Content><P>(+++ Hinweis +++)</P></Content></fussnoten></textdaten></norm>"));
        final Section section = law.sections().get(0);
        assertEquals("Text", section.content(), "content");
        assertEquals("Erste\n(+++ Hinweis +++)", section.footnotes(), "footnotes");
        assertEquals(Optional.empty(), section.builddate(), "a missing build date");
    }

    @Test
    public void removesControlCharactersFromText() throws IOException {
        final Law law =
                LawParser.parse(law(HEADER + "<norm doknr=\"BJNR1BJNE1\"><metadaten><jurabk>X</jurabk><enbez>§ 1</enbez></metadaten>"
                        + "<textdaten><text><Content><P>a&#x85;b&#x9;c</P></Content></text></textdaten></norm>"));
        assertEquals("ab c", law.sections().get(0).content(), "control characters");
    }

    @Test
    public void rejectsInvalidDocuments() {
        assertThrows(IOException.class, () -> LawParser.parse(law("")), "no norm");
        assertThrows(IOException.class, () -> LawParser.parse("<dokumente><norm>".getBytes(StandardCharsets.UTF_8)), "not well-formed");
        assertThrows(IOException.class, () -> LawParser.parse(law("<norm doknr=\"BJNR1\"><metadaten/></norm>")), "no abbreviation");
        assertThrows(IOException.class,
                () -> LawParser
                        .parse(law(HEADER + "<norm doknr=\"../x\"><metadaten><jurabk>X</jurabk><enbez>§ 1</enbez></metadaten></norm>")),
                "invalid document number");
        final String section = "<norm doknr=\"BJNR1BJNE1\"><metadaten><jurabk>X</jurabk><enbez>§ 1</enbez></metadaten></norm>";
        assertThrows(IOException.class, () -> LawParser.parse(law(HEADER + section + section)), "repeated document number");
    }

    @Test
    public void doesNotResolveExternalEntities(@TempDir final Path directory) throws IOException {
        final String canary = "CANARY-" + UUID.randomUUID();
        final Path secret = directory.resolve("secret.txt");
        Files.writeString(secret, canary);
        final byte[] xml = ("<?xml version=\"1.0\"?><!DOCTYPE dokumente [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]>"
                + "<dokumente doknr=\"BJNR1\">" + HEADER + "<norm doknr=\"BJNR1BJNE1\"><metadaten><jurabk>X</jurabk><enbez>§ 1</enbez>"
                + "</metadaten><textdaten><text><Content><P>&xxe;</P></Content></text></textdaten></norm></dokumente>")
                        .getBytes(StandardCharsets.UTF_8);
        try {
            final Law law = LawParser.parse(xml);
            assertFalse(law.toString().contains(canary), "the file content must not reach the law");
        } catch (final IOException e) {
            assertFalse(String.valueOf(e.getMessage()).contains(canary), "the file content must not reach the message");
        }
    }

    @Test
    public void doesNotLoadTheExternalDtd() throws IOException {
        // The DOCTYPE points at a port nothing listens on. Parsing succeeds because the DTD is never requested.
        final byte[] xml = ("<?xml version=\"1.0\"?><!DOCTYPE dokumente SYSTEM \"http://127.0.0.1:1/gii-norm.dtd\">"
                + "<dokumente doknr=\"BJNR1\">" + HEADER + "</dokumente>").getBytes(StandardCharsets.UTF_8);
        assertEquals("X", LawParser.parse(xml).jurabk(), "jurabk");
    }
}
