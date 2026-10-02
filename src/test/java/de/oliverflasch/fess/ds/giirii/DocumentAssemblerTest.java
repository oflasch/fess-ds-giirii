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
package de.oliverflasch.fess.ds.giirii;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.oliverflasch.fess.ds.giirii.support.Law;
import de.oliverflasch.fess.ds.giirii.support.LawParser;
import de.oliverflasch.fess.ds.giirii.support.Section;
import de.oliverflasch.fess.ds.giirii.support.TestFixtures;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link DocumentAssembler}.
 *
 * @author Oliver Flasch
 */
public class DocumentAssemblerTest {

    /** The time of indexing the tests use. */
    private static final Date NOW = Date.from(Instant.parse("2026-10-01T12:00:00Z"));

    /** The fields a script cannot change. */
    private static final Set<String> PROTECTED = Set.of("url", "gii_law", "gii_validator", "segment", "config_id", "expires");

    /** The defaults Fess supplies. */
    private static final Map<String, Object> DEFAULTS = Map.of("config_id", "D1", "segment", "session-1", "boost", "1.0");

    /**
     * A script evaluator that returns the referenced value, or the script itself when it is quoted.
     */
    private static final BiFunction<String, Map<String, Object>, Object> EVALUATOR = (script, values) -> {
        if (script.startsWith("\"")) {
            return script.substring(1, script.length() - 1);
        }
        return values.get(script);
    };

    /**
     * Creates an assembler.
     *
     * @param scripts the script entries
     * @return the assembler
     */
    private static DocumentAssembler assembler(final Map<String, String> scripts) {
        return new DocumentAssembler("www.gesetze-im-internet.de", 40, scripts, EVALUATOR, PROTECTED, () -> NOW);
    }

    /**
     * Returns the section of a law with the given label.
     *
     * @param law the law
     * @param label the label
     * @return the section
     */
    private static Section section(final Law law, final String label) {
        return law.sections().stream().filter(section -> label.equals(section.label())).findFirst().orElseThrow();
    }

    @Test
    public void fillsAllFieldsOfASection() throws IOException {
        final Law law = LawParser.parse(TestFixtures.bytes("bgb.xml"));
        final Map<String, Object> document = assembler(Map.of()).assemble(DEFAULTS, Map.of(), "bgb", law, section(law, "§ 433"),
                "https://www.gesetze-im-internet.de/bgb/__433.html", "\"etag-1\"");
        assertEquals("https://www.gesetze-im-internet.de/bgb/__433.html", document.get("url"), "url");
        assertEquals("§ 433 BGB – Vertragstypische Pflichten beim Kaufvertrag", document.get("title"), "title");
        final String content = (String) document.get("content");
        assertTrue(content.startsWith("(1) Durch den Kaufvertrag wird der Verkäufer"), "content");
        assertEquals("(1) Durch den Kaufvertrag wird der Verkä", document.get("digest"), "digest");
        assertEquals((long) content.length(), document.get("content_length"), "content_length");
        assertEquals(
                "Bürgerliches Gesetzbuch\nBGB\nBuch 2 Recht der Schuldverhältnisse > Abschnitt 8 Einzelne Schuldverhältnisse"
                        + " > Titel 1 Kauf, Tausch > Untertitel 1 Allgemeine Vorschriften",
                document.get("important_content"), "important_content");
        assertEquals(Date.from(Instant.parse("2026-09-26T19:55:08Z")), document.get("last_modified"), "last_modified");
        assertEquals(NOW, document.get("timestamp"), "timestamp");
        assertEquals("www.gesetze-im-internet.de", document.get("host"), "host");
        assertEquals("www.gesetze-im-internet.de", document.get("site"), "site");
        assertEquals("de", document.get("lang"), "lang");
        assertEquals("text/html", document.get("mimetype"), "mimetype");
        assertEquals("html", document.get("filetype"), "filetype");
        assertEquals("bgb", document.get("gii_law"), "gii_law");
        assertEquals("\"etag-1\"", document.get("gii_validator"), "gii_validator");
        assertEquals("BGB", document.get("gii_jurabk"), "gii_jurabk");
        assertEquals("§ 433", document.get("gii_enbez"), "gii_enbez");
        assertEquals("BJNR001950896BJNE042602377", document.get("gii_doknr"), "gii_doknr");
        assertTrue(((String) document.get("gii_path")).startsWith("Buch 2 Recht der Schuldverhältnisse"), "gii_path");
        assertEquals("D1", document.get("config_id"), "defaults are kept");
        assertEquals("1.0", document.get("boost"), "defaults are kept");
    }

    @Test
    public void namesTheUnitOfASectionWithARepeatedLabel() throws IOException {
        final Law law = LawParser.parse(TestFixtures.bytes("bgbeg.xml"));
        final Map<String, Object> first = assembler(Map.of()).assemble(DEFAULTS, Map.of(), "bgbeg", law, law.sections().get(0), "u1", "v");
        final Map<String, Object> second = assembler(Map.of()).assemble(DEFAULTS, Map.of(), "bgbeg", law, law.sections().get(2), "u2", "v");
        assertEquals("Art 224 § 1 BGBEG – Abstammung", first.get("title"), "first title");
        assertEquals("Art 229 § 1 BGBEG – Überleitungsvorschrift zum Gesetz zur Beschleunigung fälliger Zahlungen", second.get("title"),
                "second title");
        assertEquals("§ 1", first.get("gii_enbez"), "the label field keeps the plain label");
    }

    @Test
    public void buildsALawLevelDocument() throws IOException {
        final Law law = LawParser.parse(TestFixtures.bytes("header-only.xml"));
        final Map<String, Object> document = assembler(Map.of()).assemble(DEFAULTS, Map.of(), "moselschabgt2002abest", law,
                law.sections().get(0), "https://www.gesetze-im-internet.de/moselschabgt2002abest/", "\"e\"");
        assertEquals("Ausführungsbestimmungen zum Tarif für die Schifffahrtsabgaben auf der Mosel zwischen Thionville (Diedenhofen)"
                + " und Koblenz (Coblence) (MoselSchAbgT2002ABest)", document.get("title"), "title");
        assertFalse(document.containsKey("gii_enbez"), "no label");
        assertFalse(document.containsKey("gii_path"), "no path");
        assertEquals("BJNR546100002", document.get("gii_doknr"), "gii_doknr");
    }

    @Test
    public void usesTheTitleAsContentOfAnEmptySection() {
        final Section empty = new Section("BJNR1BJNE1", "§ 1", "", "", "X", "", "", "", Optional.empty(), false);
        final Law law = new Law("BJNR1", "X", "", "", "", "", List.of(), List.of(empty));
        final Map<String, Object> document = assembler(Map.of()).assemble(DEFAULTS, Map.of(), "x", law, empty, "u", "v");
        assertEquals("§ 1 X", document.get("title"), "title");
        assertEquals("§ 1 X", document.get("content"), "content");
        assertFalse(document.containsKey("last_modified"), "no build date, no last_modified");
    }

    @Test
    public void appliesScriptsAfterTheDefaults() throws IOException {
        final Law law = LawParser.parse(TestFixtures.bytes("gg.xml"));
        final Map<String, Object> document = assembler(Map.of("title", "gii_enbez", "label", "\"Bundesrecht\"", "notes", "footnotes",
                "law_name", "law_title", "status", "law_status", "source", "my_param", "missing", "no_such_value")).assemble(DEFAULTS,
                        Map.of("my_param", "configured"), "gg", law, section(law, "Art 1"), "u", "v");
        assertEquals("Art 1", document.get("title"), "a script replaces a field");
        assertEquals("Bundesrecht", document.get("label"), "a script adds a field");
        assertEquals("", document.get("notes"), "script-only values are available");
        assertEquals("Grundgesetz für die Bundesrepublik Deutschland", document.get("law_name"), "law_title");
        assertEquals(law.status(), document.get("status"), "law_status");
        assertEquals("configured", document.get("source"), "parameters are available");
        assertFalse(document.containsKey("missing"), "a null result leaves the field unset");
    }

    @Test
    public void doesNotLetScriptsChangeProtectedFields() throws IOException {
        final Law law = LawParser.parse(TestFixtures.bytes("gg.xml"));
        final Map<String, Object> document = assembler(Map.of("url", "\"https://evil.example/\"", "gii_law", "\"other\"", "gii_validator",
                "\"forged\"", "segment", "\"other-session\"", "config_id", "\"D2\"", "expires", "\"2026-01-01\"")).assemble(DEFAULTS,
                        Map.of(), "gg", law, section(law, "Art 1"), "https://www.gesetze-im-internet.de/gg/art_1.html", "\"etag\"");
        assertEquals("https://www.gesetze-im-internet.de/gg/art_1.html", document.get("url"), "url");
        assertEquals("gg", document.get("gii_law"), "gii_law");
        assertEquals("\"etag\"", document.get("gii_validator"), "gii_validator");
        assertEquals("session-1", document.get("segment"), "segment");
        assertEquals("D1", document.get("config_id"), "config_id");
        assertFalse(document.containsKey("expires"), "a script cannot add an expiry");
    }

    @Test
    public void neverCutsTheDigestWithinASurrogatePair() {
        final String content = "a".repeat(39) + "😀" + "b";
        final Section section = new Section("BJNR1BJNE1", "§ 1", "", "", "X", content, "", "", Optional.empty(), false);
        final Law law = new Law("BJNR1", "X", "", "", "", "", List.of(), List.of(section));
        final Map<String, Object> document = assembler(Map.of()).assemble(DEFAULTS, Map.of(), "x", law, section, "u", "v");
        assertEquals("a".repeat(39), document.get("digest"), "digest");
    }
}
