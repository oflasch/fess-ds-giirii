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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.oliverflasch.fess.ds.giirii.support.FakeSite;
import de.oliverflasch.fess.ds.giirii.support.HttpFetcher;
import de.oliverflasch.fess.ds.giirii.support.InMemoryIndexState;
import de.oliverflasch.fess.ds.giirii.support.InMemoryIndexState.Doc;
import de.oliverflasch.fess.ds.giirii.support.TestFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.exception.DataStoreException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the synchronization run of {@link GiiSynchronizer} against a loopback site and an
 * in-memory index.
 *
 * @author Oliver Flasch
 */
public class GiiSynchronizerTest {

    /** The public base URL that document URLs are built with. */
    private static final String BASE = "https://www.gesetze-im-internet.de";

    /** The slug of the law without sections. */
    private static final String MOSEL = "moselschabgt2002abest";

    /** The site. */
    private FakeSite site;

    /** The fetcher that requests the site. */
    private HttpFetcher fetcher;

    /** The index. */
    private InMemoryIndexState index;

    /** The recorded failures: the URL or name of what failed. */
    private final List<String> failures = new ArrayList<>();

    /** The number of commits. */
    private final AtomicInteger commits = new AtomicInteger();

    /** Whether the data store is running. */
    private final AtomicBoolean alive = new AtomicBoolean(true);

    /** The number of documents after which the sink fails; negative for never. */
    private int failAfter = -1;

    /** The number of documents after which the sink stops the data store; negative for never. */
    private int stopAfter = -1;

    /** The number of documents the sink received. */
    private int received;

    /**
     * Starts the site and creates the index.
     */
    @BeforeEach
    public void setUp() {
        site = new FakeSite();
        fetcher = new HttpFetcher(site.baseUrl(), "test-agent", Duration.ofSeconds(10)) {
            @Override
            protected void sleep(final long millis, final String path) {
                // retries do not wait in tests
            }
        };
        index = new InMemoryIndexState();
    }

    /**
     * Stops the fetcher and the site.
     */
    @AfterEach
    public void tearDown() {
        fetcher.close();
        site.close();
    }

    /**
     * Publishes the table of contents.
     *
     * @param slugs the listed laws
     */
    private void toc(final String... slugs) {
        final StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>"
                + "<!DOCTYPE items SYSTEM \"http://www.gesetze-im-internet.de/dtd/1.0/gii-toc.dtd\"><items>");
        for (final String slug : slugs) {
            xml.append("<item><title>")
                    .append(slug)
                    .append("</title><link>http://www.gesetze-im-internet.de/")
                    .append(slug)
                    .append("/xml.zip</link></item>");
        }
        site.serve(GiiSynchronizer.TOC_PATH, xml.append("</items>").toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Publishes a law.
     *
     * @param slug the slug
     * @param xml the law document
     * @param indexPage the index page, or null for none
     * @param etag the entity tag of the archive
     */
    private void publish(final String slug, final byte[] xml, final byte[] indexPage, final String etag) {
        site.serve("/" + slug + "/xml.zip", TestFixtures.zip("law.xml", xml), etag);
        if (indexPage == null) {
            site.remove("/" + slug + "/index.html");
        } else {
            site.serve("/" + slug + "/index.html", indexPage);
        }
    }

    /**
     * Publishes the three fixture laws: GG (10 sections), BGB (9 sections) and a law without sections.
     */
    private void publishAll() {
        toc("gg", "bgb", MOSEL);
        publish("gg", TestFixtures.bytes("gg.xml"), TestFixtures.bytes("gg-index.html"), "\"gg-1\"");
        publish("bgb", TestFixtures.bytes("bgb.xml"), TestFixtures.bytes("bgb-index.html"), "\"bgb-1\"");
        publish(MOSEL, TestFixtures.bytes("header-only.xml"), null, "\"mosel-1\"");
    }

    /**
     * Publishes the three fixture laws and indexes them in session {@code s1}. The recorded
     * requests and index operations of that run are forgotten.
     */
    private void indexAll() {
        publishAll();
        run("s1", Map.of());
        site.clearRequests();
        index.calls().clear();
    }

    /**
     * Creates a synchronizer.
     *
     * @param session the crawl session
     * @param values the data store parameters; {@code read_interval} defaults to 0
     * @return the synchronizer
     */
    private GiiSynchronizer synchronizer(final String session, final Map<String, String> values) {
        final Map<String, String> all = new HashMap<>(Map.of("read_interval", "0"));
        all.putAll(values);
        final DataStoreParams params = new DataStoreParams();
        params.putAll(all);
        return new GiiSynchronizer(GiiParams.of(params), BASE, fetcher, index, (slug, law, section, url, validator) -> {
            if (received == failAfter) {
                throw new IllegalStateException("index rejected the document");
            }
            received++;
            index.put(url, new Doc(slug, validator, session));
            if (received == stopAfter) {
                alive.set(false);
            }
        }, (url, cause) -> failures.add(url), commits::incrementAndGet, session, alive::get);
    }

    /**
     * Runs a synchronization.
     *
     * @param session the crawl session
     * @param values the data store parameters
     * @return the counters of the run
     */
    private RunSummary run(final String session, final Map<String, String> values) {
        return synchronizer(session, values).run();
    }

    /**
     * Returns the names of the delete operations the index received.
     *
     * @return the names in order
     */
    private List<String> deletions() {
        return index.calls().stream().filter(call -> call.startsWith("delete")).toList();
    }

    @Test
    public void indexesEveryLawOnTheFirstRun() {
        publishAll();
        final RunSummary summary = run("s1", Map.of());
        assertEquals(new RunSummary(3, 0, 3, 0, 20, 0, true), summary, "summary");
        assertEquals(10, index.urls("gg").size(), "GG sections");
        assertEquals(9, index.urls("bgb").size(), "BGB sections");
        assertTrue(index.urls("bgb").contains(BASE + "/bgb/__433.html"), "section page URL");
        assertEquals(List.of(BASE + "/" + MOSEL + "/"), index.urls(MOSEL), "landing page URL of a law without sections");
        assertEquals(new Doc("gg", "\"gg-1\"", "s1"), index.documents().get(BASE + "/gg/art_1.html"), "validator and session");
        assertEquals(1, commits.get(), "one commit");
        assertTrue(failures.isEmpty(), "no failure");
        assertTrue(site.requests("/" + MOSEL + "/index.html").isEmpty(), "no index page request for a law without sections");
    }

    @Test
    public void neitherStoresNorDeletesAnUnchangedLaw() {
        indexAll();
        site.clearRequests();
        final RunSummary summary = run("s2", Map.of());
        assertEquals(new RunSummary(3, 3, 0, 0, 0, 0, true), summary, "summary");
        assertEquals(20, index.documents().size(), "all documents remain");
        assertTrue(index.documents().values().stream().allMatch(doc -> "s1".equals(doc.segment())), "no document was stored again");
        assertEquals("\"gg-1\"", site.requests("/gg/xml.zip").get(0).ifNoneMatch(), "the stored validator is sent");
        assertEquals(4, site.requests().size(), "the table of contents and one conditional request per law");
        assertTrue(deletions().isEmpty(), "no deletion");
    }

    @Test
    public void reindexesAChangedLawAndDeletesTheSectionsThatLeftIt() {
        indexAll();
        // the new version of GG has no Art 8
        final String xml = TestFixtures.text("gg.xml");
        final int cut = xml.lastIndexOf("<norm ");
        final String index9 = new String(TestFixtures.bytes("gg-index.html"), StandardCharsets.ISO_8859_1).replace("href=\"art_8.html\"",
                "href=\"../art_8.html\"");
        publish("gg", (xml.substring(0, cut) + "</dokumente>").getBytes(StandardCharsets.UTF_8),
                index9.getBytes(StandardCharsets.ISO_8859_1), "\"gg-2\"");

        final RunSummary summary = run("s2", Map.of());
        assertEquals(new RunSummary(3, 2, 1, 0, 9, 1, true), summary, "summary");
        assertEquals(9, index.urls("gg").size(), "GG sections");
        assertFalse(index.urls("gg").contains(BASE + "/gg/art_8.html"), "the section that left the law is deleted");
        assertTrue(index.urls("gg").stream().map(index.documents()::get).allMatch(doc -> doc.equals(new Doc("gg", "\"gg-2\"", "s2"))),
                "all GG documents carry the new validator");
        assertEquals(new Doc("bgb", "\"bgb-1\"", "s1"), index.documents().get(BASE + "/bgb/__1.html"), "BGB is untouched");
        assertEquals(List.of("deleteStaleSections:gg"), deletions(), "only the changed law is pruned");
    }

    @Test
    public void keepsTheDocumentsAndTheValidatorOfALawThatFails() {
        indexAll();
        site.remove("/gg/xml.zip");
        site.serve("/bgb/xml.zip", "not an archive".getBytes(StandardCharsets.UTF_8), "\"bgb-2\"");

        final RunSummary summary = run("s2", Map.of());
        assertEquals(new RunSummary(3, 1, 0, 2, 0, 0, true), summary, "summary");
        assertEquals(new Doc("gg", "\"gg-1\"", "s1"), index.documents().get(BASE + "/gg/art_1.html"), "GG keeps documents and validator");
        assertEquals(new Doc("bgb", "\"bgb-1\"", "s1"), index.documents().get(BASE + "/bgb/__1.html"), "BGB keeps documents and validator");
        assertEquals(List.of(BASE + "/gg/xml.zip", BASE + "/bgb/xml.zip"), failures, "both failures are recorded");
        assertTrue(deletions().isEmpty(), "no deletion");

        // the next run requests the failed law again with the old validator
        site.clearRequests();
        run("s3", Map.of());
        assertEquals("\"bgb-1\"", site.requests("/bgb/xml.zip").get(0).ifNoneMatch(), "retried with the stored validator");
    }

    @Test
    public void requestsALawWithTwoValidatorsUnconditionally() {
        publishAll();
        index.put(BASE + "/gg/art_1.html", new Doc("gg", "\"gg-1\"", "s0"));
        index.put(BASE + "/gg/art_99.html", new Doc("gg", "\"gg-0\"", "s0"));

        final RunSummary summary = run("s1", Map.of("laws", "gg"));
        assertNull(site.requests("/gg/xml.zip").get(0).ifNoneMatch(), "no condition for an inconsistent law");
        assertEquals(1, summary.reindexed(), "re-indexed");
        assertEquals(10, index.urls("gg").size(), "GG sections");
        assertFalse(index.documents().containsKey(BASE + "/gg/art_99.html"), "the leftover document is deleted");
    }

    @Test
    public void reindexesEveryLawWithFull() {
        indexAll();
        site.clearRequests();
        final RunSummary summary = run("s2", Map.of("full", "true"));
        assertEquals(new RunSummary(3, 0, 3, 0, 20, 0, true), summary, "summary");
        assertTrue(site.requests().stream().allMatch(request -> request.ifNoneMatch() == null), "no conditional request");
        assertTrue(index.documents().values().stream().allMatch(doc -> "s2".equals(doc.segment())), "every document was stored again");
    }

    @Test
    public void storesAPlaceholderWhenTheArchiveHasNoValidator() {
        toc(MOSEL);
        site.serve("/" + MOSEL + "/xml.zip", TestFixtures.zip("law.xml", TestFixtures.bytes("header-only.xml")));
        run("s1", Map.of("laws", MOSEL));
        assertEquals(GiiSynchronizer.NO_VALIDATOR, index.documents().get(BASE + "/" + MOSEL + "/").validator(), "placeholder");
        site.clearRequests();
        final RunSummary summary = run("s2", Map.of("laws", MOSEL));
        assertNull(site.requests("/" + MOSEL + "/xml.zip").get(0).ifNoneMatch(), "the placeholder is not sent as a condition");
        assertNull(site.requests("/" + MOSEL + "/xml.zip").get(0).ifModifiedSince(), "the placeholder is not sent as a date");
        assertEquals(1, summary.reindexed(), "a law without validator is re-indexed on every run");
    }

    @Test
    public void usesAnchorUrlsWhenTheIndexPageIsMissing() {
        toc("gg");
        publish("gg", TestFixtures.bytes("gg.xml"), null, "\"gg-1\"");
        final RunSummary summary = run("s1", Map.of("laws", "gg"));
        assertEquals(1, summary.reindexed(), "the law is indexed");
        assertTrue(index.urls("gg").contains(BASE + "/gg/BJNR000010949.html#BJNR000010949BJNE001700314"), "anchor URL of Art 1");
        assertTrue(failures.isEmpty(), "a missing index page is no failure");
    }

    @Test
    public void removesALawThatLeftTheTableOfContents() {
        indexAll();
        toc("gg", "bgb");
        final RunSummary summary = run("s2", Map.of("max_deletion_ratio", "0.5"));
        assertEquals(1, summary.deleted(), "deleted documents");
        assertTrue(index.urls(MOSEL).isEmpty(), "the law is removed");
        assertEquals(List.of("deleteLaw:" + MOSEL), deletions(), "deletions");
    }

    @Test
    public void removesNothingWhenTheTableOfContentsLostTooManyLaws() {
        indexAll();
        toc("gg", "bgb");
        // one of three indexed laws is missing, which exceeds the default ratio of 0.1
        final RunSummary summary = run("s2", Map.of());
        assertEquals(0, summary.deleted(), "deleted documents");
        assertEquals(List.of(BASE + "/" + MOSEL + "/"), index.urls(MOSEL), "the law stays indexed");
        assertTrue(deletions().isEmpty(), "no deletion");
        assertEquals(List.of(BASE + GiiSynchronizer.TOC_PATH), failures, "the refusal is recorded");
    }

    @Test
    public void removesNoLawInARestrictedRun() {
        indexAll();
        toc("gg", "bgb");
        run("s2", Map.of("max_deletion_ratio", "1", "laws", "gg"));
        run("s3", Map.of("max_deletion_ratio", "1", "limit", "1"));
        assertEquals(List.of(BASE + "/" + MOSEL + "/"), index.urls(MOSEL), "the law stays indexed");
        assertTrue(deletions().isEmpty(), "no deletion");
    }

    @Test
    public void restrictsTheRunToTheConfiguredLawsAndLimit() {
        publishAll();
        assertEquals(new RunSummary(3, 0, 1, 0, 9, 0, true), run("s1", Map.of("laws", "bgb,unknown")), "laws");
        assertTrue(site.requests("/gg/xml.zip").isEmpty(), "a law outside the filter is not requested");
        assertEquals(new RunSummary(3, 0, 1, 0, 10, 0, true), run("s2", Map.of("limit", "1")), "limit takes the first law");
    }

    @Test
    public void endsTheLoopAfterConsecutiveFailures() {
        toc("a", "b", "gg", "bgb");
        publish("gg", TestFixtures.bytes("gg.xml"), TestFixtures.bytes("gg-index.html"), "\"gg-1\"");
        index.put(BASE + "/old/", new Doc("old", "\"old\"", "s0"));

        final RunSummary summary = run("s1", Map.of("max_consecutive_failures", "2", "max_deletion_ratio", "1"));
        assertEquals(new RunSummary(4, 0, 0, 2, 0, 0, false), summary, "summary");
        assertTrue(site.requests("/gg/xml.zip").isEmpty(), "the loop ended before the third law");
        assertEquals(1, index.urls("old").size(), "no law is removed after an aborted loop");
    }

    @Test
    public void countsOnlyConsecutiveFailures() {
        toc("a", "gg", "b", MOSEL);
        publish("gg", TestFixtures.bytes("gg.xml"), TestFixtures.bytes("gg-index.html"), "\"gg-1\"");
        publish(MOSEL, TestFixtures.bytes("header-only.xml"), null, "\"mosel-1\"");
        final RunSummary summary = run("s1", Map.of("max_consecutive_failures", "2"));
        assertEquals(new RunSummary(4, 0, 2, 2, 11, 0, true), summary, "a success resets the counter");
    }

    @Test
    public void endsWithoutDeletionWhenTheTableOfContentsFails() {
        indexAll();
        final int documents = index.documents().size();

        site.status(GiiSynchronizer.TOC_PATH, 500, Map.of());
        assertThrows(DataStoreException.class, () -> run("s2", Map.of("max_deletion_ratio", "1")), "HTTP 500");

        site.serve(GiiSynchronizer.TOC_PATH,
                "<items><item><link>http://evil.example/gg/xml.zip</link></item></items>".getBytes(StandardCharsets.UTF_8));
        assertThrows(DataStoreException.class, () -> run("s3", Map.of("max_deletion_ratio", "1")), "invalid link");

        site.serve(GiiSynchronizer.TOC_PATH, "<items></items>".getBytes(StandardCharsets.UTF_8));
        assertThrows(DataStoreException.class, () -> run("s4", Map.of("max_deletion_ratio", "1")), "no entry");

        toc("gg");
        assertThrows(DataStoreException.class, () -> run("s5", Map.of("max_deletion_ratio", "1", "max_toc_size", "20")), "size limit");

        assertEquals(documents, index.documents().size(), "all documents remain");
        assertTrue(deletions().isEmpty(), "no deletion");
        assertTrue(site.requests().stream().noneMatch(request -> request.path().equals("/evil/xml.zip")), "no request off the list");
    }

    @Test
    public void enforcesTheArchiveAndDocumentSizeLimits() {
        publishAll();
        assertEquals(2, run("s1", Map.of("max_zip_size", "3000")).failed(), "the archives of GG and BGB exceed 3000 bytes");
        assertEquals(1, index.documents().size(), "only the small law is indexed");
        assertEquals(2, run("s2", Map.of("max_xml_size", "5000")).failed(), "the documents of GG and BGB exceed 5000 bytes");
    }

    @Test
    public void failsALawWhenTheIndexRejectsADocument() {
        publishAll();
        index.put(BASE + "/gg/art_99.html", new Doc("gg", "\"gg-0\"", "s0"));
        failAfter = 3;
        final RunSummary summary = run("s1", Map.of("laws", "gg"));
        assertEquals(1, summary.failed(), "the law failed");
        assertEquals(0, summary.reindexed(), "the law is not complete");
        assertTrue(index.documents().containsKey(BASE + "/gg/art_99.html"), "an incomplete law is not pruned");
        assertTrue(deletions().isEmpty(), "no deletion");

        // the law now has two validators in the index, so the next run requests it unconditionally
        failAfter = -1;
        site.clearRequests();
        assertEquals(1, run("s2", Map.of("laws", "gg")).reindexed(), "re-indexed on the next run");
        assertNull(site.requests("/gg/xml.zip").get(0).ifNoneMatch(), "no condition");
        assertFalse(index.documents().containsKey(BASE + "/gg/art_99.html"), "pruned once the law is complete");
    }

    @Test
    public void stopsAfterTheCurrentLaw() {
        publishAll();
        index.put(BASE + "/old/", new Doc("old", "\"old\"", "s0"));
        stopAfter = 1;
        final RunSummary summary = run("s1", Map.of("max_deletion_ratio", "1"));
        assertEquals(new RunSummary(3, 0, 1, 0, 10, 0, false), summary, "the current law is completed, the rest is skipped");
        assertTrue(site.requests("/bgb/xml.zip").isEmpty(), "the next law is not requested");
        assertEquals(1, commits.get(), "the stored documents are committed");
        assertEquals(1, index.urls("old").size(), "no law is removed after a stopped run");
    }

    @Test
    public void endsWhenTheStateCannotBeRead() {
        publishAll();
        index.setFailing(true);
        assertThrows(DataStoreException.class, () -> run("s1", Map.of()), "index unavailable");
        assertTrue(site.requests().isEmpty(), "no request is sent");
    }

    @Test
    public void refusesToRunWithoutASession() {
        publishAll();
        assertThrows(DataStoreException.class, () -> run(" ", Map.of()), "blank session");
        assertTrue(site.requests().isEmpty(), "no request is sent");
    }

    @Test
    public void purgesAllDocumentsWithoutAnyRequest() {
        indexAll();
        site.clearRequests();
        index.calls().clear();

        final long deleted = synchronizer("s2", Map.of("purge", "true")).purge();
        assertEquals(20, deleted, "deleted documents");
        assertTrue(index.documents().isEmpty(), "the index is empty");
        assertEquals(List.of("refresh", "deleteAll"), index.calls(), "index operations");
        assertTrue(site.requests().isEmpty(), "a purge sends no request");
        assertEquals(0, received - 20, "a purge stores no document");
    }

    @Test
    public void recordsAFailedPurge() {
        index.setFailing(true);
        final DataStoreException e =
                assertThrows(DataStoreException.class, () -> synchronizer("s1", Map.of("purge", "true")).purge(), "index unavailable");
        assertNotNull(e.getCause(), "the cause is kept for the log");
        assertEquals(List.of("purge"), failures, "the failure is recorded");
    }
}
