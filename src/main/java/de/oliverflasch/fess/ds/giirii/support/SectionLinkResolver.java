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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Assigns a URL to every indexable unit of a law.
 * <p>
 * The page name of a section cannot be computed from its label. The index page of a law lists
 * one link per section in the order of the sections in the XML, so the links are paired with
 * the sections by position. A law whose index page does not pair receives anchor URLs into the
 * page that renders the whole law.
 * </p>
 *
 * @author Oliver Flasch
 */
public final class SectionLinkResolver {

    /** The logger. */
    private static final Logger logger = LogManager.getLogger(SectionLinkResolver.class);

    /** Matches the {@code href} attributes of the index page; the value length is bounded. */
    private static final Pattern HREF_PATTERN = Pattern.compile("href\\s*=\\s*\"([^\"]{1,200})\"", Pattern.CASE_INSENSITIVE);

    /**
     * The pattern a section page name must match. Every allowed character is a literal path
     * character of a URL, so a page name is appended to the URL of the law unchanged. The site
     * uses commas and colons in the names of some pages ({@code ___21,_22.html}, {@code anlagen:.html}).
     */
    private static final Pattern PAGE_PATTERN = Pattern.compile("^[A-Za-z0-9_.,:()~!-]+\\.html$");

    /** The name of the index page itself. */
    private static final String INDEX_PAGE = "index.html";

    /**
     * Prevents instantiation.
     */
    private SectionLinkResolver() {
    }

    /**
     * Returns the URLs of the indexable units of a law, in the order of {@link Law#sections()}.
     *
     * @param baseUrl the public base URL of the site, without a trailing slash
     * @param slug the slug of the law
     * @param law the parsed law
     * @param indexPage the HTML of the index page of the law; empty when it is unavailable
     * @return one unique URL per unit
     */
    public static List<String> resolve(final String baseUrl, final String slug, final Law law, final Optional<String> indexPage) {
        final String lawUrl = baseUrl + "/" + slug + "/";
        // 1. a law without sections links to its landing page
        if (law.isLawLevelOnly()) {
            return List.of(lawUrl);
        }
        // 2. section pages, when the index page pairs with the sections
        final int expected = law.sections().size();
        if (indexPage.isPresent()) {
            final Optional<List<String>> pages = sectionPages(indexPage.get(), law.doknr());
            if (pages.isPresent() && pages.get().size() == expected) {
                return pages.get().stream().map(page -> lawUrl + page).toList();
            }
            logger.warn("The index page of law {} lists {} section links for {} sections; using anchor URLs.", slug,
                    pages.map(List::size).orElse(-1), expected);
        } else {
            logger.warn("The index page of law {} is unavailable; using anchor URLs for {} sections.", slug, expected);
        }
        // 3. anchors in the page that renders the whole law
        final List<String> urls = new ArrayList<>(expected);
        for (final Section section : law.sections()) {
            urls.add(lawUrl + law.doknr() + ".html#" + section.doknr());
        }
        return urls;
    }

    /**
     * Extracts the section page names from an index page.
     * <p>
     * Candidates are the relative links to HTML pages in the directory of the law, without the
     * index page and the page that renders the whole law.
     * </p>
     *
     * @param html the HTML of the index page
     * @param lawDoknr the document number of the law, which names the page of the whole law
     * @return the page names in document order without repetitions, or empty when a candidate
     *         does not match the page name pattern
     */
    private static Optional<List<String>> sectionPages(final String html, final String lawDoknr) {
        final String fullPage = lawDoknr + ".html";
        final Set<String> pages = new LinkedHashSet<>();
        final Set<String> excluded = new HashSet<>(List.of(INDEX_PAGE, fullPage));
        final Matcher matcher = HREF_PATTERN.matcher(html);
        while (matcher.find()) {
            final String href = matcher.group(1);
            if (!isRelativePage(href) || excluded.contains(href)) {
                continue;
            }
            if (!PAGE_PATTERN.matcher(href).matches()) {
                return Optional.empty();
            }
            pages.add(href);
        }
        return Optional.of(List.copyOf(pages));
    }

    /**
     * Returns whether a link points to an HTML page in the directory of the index page.
     *
     * @param href the value of an {@code href} attribute
     * @return true for a link without directory, query and fragment that ends with {@code .html}; a link with a scheme
     *         and an authority contains a slash and is therefore no page of the directory
     */
    private static boolean isRelativePage(final String href) {
        return href.endsWith(".html") && href.indexOf('/') < 0 && href.indexOf('#') < 0 && href.indexOf('?') < 0;
    }
}
