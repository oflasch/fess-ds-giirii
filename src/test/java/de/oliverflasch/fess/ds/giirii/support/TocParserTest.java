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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link TocParser}.
 *
 * @author Oliver Flasch
 */
public class TocParserTest {

    /**
     * Wraps items into a table of contents.
     *
     * @param items the item elements
     * @return the bytes of the document
     */
    private static byte[] toc(final String items) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><items>" + items + "</items>").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds one item.
     *
     * @param link the content of the link element
     * @return the item element
     */
    private static String item(final String link) {
        return "<item><title>T</title><link>" + link + "</link></item>";
    }

    @Test
    public void parsesTheRealTableOfContents() throws IOException {
        final List<LawRef> laws = TocParser.parse(TestFixtures.bytes("toc.xml"));
        assertEquals(List.of("1-dm-goldm_nzg", "bgb", "bgbeg", "gg", "moselschabgt2002abest", "stvo_2013"),
                laws.stream().map(LawRef::slug).sorted().toList(), "slugs");
        final LawRef bgb = laws.stream().filter(law -> "bgb".equals(law.slug())).findFirst().orElseThrow();
        assertEquals("Bürgerliches Gesetzbuch", bgb.title(), "title");
        assertTrue(laws.stream().anyMatch(law -> law.title().contains("\"Geld und Währung\"")), "entities are resolved");
    }

    @Test
    public void keepsTheFirstOfRepeatedSlugs() throws IOException {
        final List<LawRef> laws = TocParser.parse(toc(item("http://www.gesetze-im-internet.de/bgb/xml.zip")
                + item("https://www.gesetze-im-internet.de/gg/xml.zip") + item("http://www.gesetze-im-internet.de/bgb/xml.zip")));
        assertEquals(List.of("bgb", "gg"), laws.stream().map(LawRef::slug).toList(), "slugs");
    }

    @Test
    public void rejectsALinkOnAnotherHost() {
        assertThrows(IOException.class,
                () -> TocParser.parse(toc(item("http://www.gesetze-im-internet.de/bgb/xml.zip") + item("http://evil.example/bgb/xml.zip"))),
                "another host");
        assertThrows(IOException.class, () -> TocParser.parse(toc(item("http://www.gesetze-im-internet.de.evil.example/bgb/xml.zip"))),
                "host suffix");
        assertThrows(IOException.class, () -> TocParser.parse(toc(item("http://user@www.gesetze-im-internet.de/bgb/xml.zip"))),
                "user info");
    }

    @Test
    public void rejectsPathTraversalAndForeignPaths() {
        for (final String link : List.of("http://www.gesetze-im-internet.de/../etc/xml.zip",
                "http://www.gesetze-im-internet.de/a/b/xml.zip", "http://www.gesetze-im-internet.de/bgb/xml.zip?x=1",
                "http://www.gesetze-im-internet.de/BGB/xml.zip", "http://www.gesetze-im-internet.de/bgb/index.html", "file:///etc/passwd",
                "")) {
            assertThrows(IOException.class, () -> TocParser.parse(toc(item(link))), link);
        }
    }

    @Test
    public void rejectsAnItemWithoutLink() {
        assertThrows(IOException.class, () -> TocParser.parse(toc("<item><title>T</title></item>")), "missing link");
    }

    @Test
    public void rejectsAnEmptyOrMalformedDocument() {
        assertThrows(IOException.class, () -> TocParser.parse(toc("")), "no item");
        assertThrows(IOException.class, () -> TocParser.parse("<items><item>".getBytes(StandardCharsets.UTF_8)), "not well-formed");
        assertThrows(IOException.class, () -> TocParser.parse(new byte[0]), "empty");
    }

    @Test
    public void doesNotResolveExternalEntities(@TempDir final Path directory) throws IOException {
        final String canary = "CANARY-" + UUID.randomUUID();
        final Path secret = directory.resolve("secret.txt");
        Files.writeString(secret, canary);
        final byte[] xml = ("<?xml version=\"1.0\"?><!DOCTYPE items [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]><items>"
                + "<item><title>&xxe;</title><link>http://www.gesetze-im-internet.de/bgb/xml.zip</link></item></items>")
                        .getBytes(StandardCharsets.UTF_8);
        try {
            final List<LawRef> laws = TocParser.parse(xml);
            assertFalse(laws.toString().contains(canary), "the file content must not reach the result");
        } catch (final IOException e) {
            assertFalse(String.valueOf(e.getMessage()).contains(canary), "the file content must not reach the message");
        }
    }

    @Test
    public void validatesSlugs() {
        assertTrue(TocParser.isSlug("bimschv_1_2010"), "valid");
        assertTrue(TocParser.isSlug("1-dm-goldm_nzg"), "valid with hyphen");
        assertFalse(TocParser.isSlug("../x"), "traversal");
        assertFalse(TocParser.isSlug("BGB"), "upper case");
        assertFalse(TocParser.isSlug(""), "empty");
        assertFalse(TocParser.isSlug(null), "null");
    }
}
