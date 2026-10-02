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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link SectionLinkResolver}.
 *
 * @author Oliver Flasch
 */
public class SectionLinkResolverTest {

    /** The public base URL. */
    private static final String BASE = "https://www.gesetze-im-internet.de";

    /**
     * Reads an index page fixture the way the synchronization decodes it.
     *
     * @param name the fixture name
     * @return the HTML
     */
    private static Optional<String> page(final String name) {
        return Optional.of(new String(TestFixtures.bytes(name), StandardCharsets.ISO_8859_1));
    }

    /**
     * Builds a law with the given number of sections.
     *
     * @param count the number of sections
     * @return the law
     */
    private static Law law(final int count) {
        final List<Section> sections = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            sections.add(new Section("BJNR1BJNE" + i, "§ " + i, "", "", "X", "text", "", "", Optional.empty(), false));
        }
        return new Law("BJNR1", "X", "", "", "", "", List.of(), sections);
    }

    /**
     * Builds an index page.
     *
     * @param hrefs the link targets in document order
     * @return the HTML
     */
    private static Optional<String> html(final String... hrefs) {
        final StringBuilder html = new StringBuilder("<html><a href=\"../index.html\">Start</a><a href=\"BJNR1.html\">HTML</a>");
        for (final String href : hrefs) {
            html.append("<td><a href=\"").append(href).append("\">x</a></td>");
        }
        return Optional.of(html.append("<a href=\"../impressum.html\">Impressum</a></html>").toString());
    }

    @Test
    public void pairsTheLinksOfARealIndexPageWithTheSections() throws IOException {
        final Law law = LawParser.parse(TestFixtures.bytes("bgb.xml"));
        final List<String> urls = SectionLinkResolver.resolve(BASE, "bgb", law, page("bgb-index.html"));
        assertEquals(law.sections().size(), urls.size(), "one URL per section");
        assertEquals(BASE + "/bgb/__1.html", urls.get(0), "§ 1");
        assertEquals(BASE + "/bgb/___3_bis_6.html", urls.get(2), "a repealed range, whose page name no rule derives");
        assertEquals(BASE + "/bgb/__433.html", urls.get(7), "§ 433");
    }

    @Test
    public void pairsRepeatedLabelsByPosition() throws IOException {
        final Law law = LawParser.parse(TestFixtures.bytes("bgbeg.xml"));
        assertEquals(
                List.of(BASE + "/bgbeg/art_224__1.html", BASE + "/bgbeg/art_224__2.html", BASE + "/bgbeg/art_229__1.html",
                        BASE + "/bgbeg/art_229__2.html"),
                SectionLinkResolver.resolve(BASE, "bgbeg", law, page("bgbeg-index.html")), "URLs");
    }

    @Test
    public void pairsTheGgIndexPage() throws IOException {
        final Law law = LawParser.parse(TestFixtures.bytes("gg.xml"));
        final List<String> urls = SectionLinkResolver.resolve(BASE, "gg", law, page("gg-index.html"));
        assertEquals(BASE + "/gg/eingangsformel.html", urls.get(0), "Eingangsformel");
        assertEquals(BASE + "/gg/pr_ambel.html", urls.get(1), "Präambel");
        assertEquals(BASE + "/gg/art_8.html", urls.get(9), "Art 8");
    }

    @Test
    public void fallsBackToAnchorsWhenTheCountsDiffer() {
        assertEquals(List.of(BASE + "/x/BJNR1.html#BJNR1BJNE1", BASE + "/x/BJNR1.html#BJNR1BJNE2"),
                SectionLinkResolver.resolve(BASE, "x", law(2), html("__1.html", "__2.html", "__3.html")), "more links than sections");
        assertEquals(List.of(BASE + "/x/BJNR1.html#BJNR1BJNE1", BASE + "/x/BJNR1.html#BJNR1BJNE2"),
                SectionLinkResolver.resolve(BASE, "x", law(2), html("__1.html")), "fewer links than sections");
    }

    @Test
    public void fallsBackToAnchorsWhenALinkRepeats() {
        // a repeated link is listed once, so two sections would share it
        assertEquals(List.of(BASE + "/x/BJNR1.html#BJNR1BJNE1", BASE + "/x/BJNR1.html#BJNR1BJNE2"),
                SectionLinkResolver.resolve(BASE, "x", law(2), html("__1.html", "__1.html")), "repeated link");
    }

    @Test
    public void fallsBackToAnchorsWhenALinkIsOutsideThePattern() {
        assertEquals(List.of(BASE + "/x/BJNR1.html#BJNR1BJNE1", BASE + "/x/BJNR1.html#BJNR1BJNE2"),
                SectionLinkResolver.resolve(BASE, "x", law(2), html("__1.html", "a%22onclick=.html")), "link outside the pattern");
    }

    @Test
    public void acceptsThePageNamesWithCommasAndColonsTheSiteUses() {
        assertEquals(List.of(BASE + "/x/___21,_22.html", BASE + "/x/anlagen:.html", BASE + "/x/art_3b_bis_3e,_4.html"),
                SectionLinkResolver.resolve(BASE, "x", law(3), html("___21,_22.html", "anlagen:.html", "art_3b_bis_3e,_4.html")),
                "page names as published");
    }

    @Test
    public void fallsBackToAnchorsForAPageNameWithUnsafeCharacters() {
        for (final String href : List.of("a b.html", "a%2e.html", "a&lt;b.html", "a'b.html", "a;b.html", "a@b.html")) {
            assertEquals(List.of(BASE + "/x/BJNR1.html#BJNR1BJNE1"), SectionLinkResolver.resolve(BASE, "x", law(1), html(href)), href);
        }
    }

    @Test
    public void fallsBackToAnchorsWithoutAnIndexPage() {
        assertEquals(List.of(BASE + "/x/BJNR1.html#BJNR1BJNE1"), SectionLinkResolver.resolve(BASE, "x", law(1), Optional.empty()),
                "missing index page");
    }

    @Test
    public void ignoresLinksThatLeaveTheDirectoryOfTheLaw() {
        assertEquals(List.of(BASE + "/x/__1.html"),
                SectionLinkResolver.resolve(BASE, "x", law(1),
                        html("__1.html", "https://evil.example/a.html", "../other/a.html", "#top.html", "index.html", "a.html?x=1")),
                "only the section page counts");
    }

    @Test
    public void linksALawWithoutSectionsToItsLandingPage() throws IOException {
        final Law law = LawParser.parse(TestFixtures.bytes("header-only.xml"));
        assertEquals(List.of(BASE + "/moselschabgt2002abest/"),
                SectionLinkResolver.resolve(BASE, "moselschabgt2002abest", law, Optional.empty()), "landing page");
    }
}
