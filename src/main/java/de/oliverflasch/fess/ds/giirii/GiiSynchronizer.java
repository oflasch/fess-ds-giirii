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

import de.oliverflasch.fess.ds.giirii.support.HttpFetcher;
import de.oliverflasch.fess.ds.giirii.support.IndexState;
import de.oliverflasch.fess.ds.giirii.support.Law;
import de.oliverflasch.fess.ds.giirii.support.LawArchive;
import de.oliverflasch.fess.ds.giirii.support.LawParser;
import de.oliverflasch.fess.ds.giirii.support.LawRef;
import de.oliverflasch.fess.ds.giirii.support.Section;
import de.oliverflasch.fess.ds.giirii.support.SectionLinkResolver;
import de.oliverflasch.fess.ds.giirii.support.TocParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.fess.exception.DataStoreException;

/**
 * Brings the indexed documents of one data store configuration in line with the site.
 * <p>
 * A run requests every listed law with the validator of its indexed copy, re-indexes the laws
 * that changed, and deletes the documents that left the site. A law that fails keeps its
 * indexed documents.
 * </p>
 *
 * @author Oliver Flasch
 */
public class GiiSynchronizer {

    /** The logger. */
    private static final Logger logger = LogManager.getLogger(GiiSynchronizer.class);

    /** The path of the table of contents. */
    public static final String TOC_PATH = "/gii-toc.xml";

    /** The validator stored for an archive whose response carries neither ETag nor Last-Modified. */
    public static final String NO_VALIDATOR = "-";

    /** The maximum size of the index page of a law in bytes. */
    private static final long MAX_INDEX_PAGE_SIZE = 16L * 1024L * 1024L;

    /**
     * Receives the indexable units of a law.
     *
     * @author Oliver Flasch
     */
    @FunctionalInterface
    public interface DocumentSink {

        /**
         * Stores one unit in the index.
         *
         * @param slug the slug of the law
         * @param law the law
         * @param section the unit to index
         * @param url the URL of the unit
         * @param validator the validator of the law archive
         */
        void store(String slug, Law law, Section section, String url, String validator);
    }

    /**
     * Receives the failures of a run.
     *
     * @author Oliver Flasch
     */
    @FunctionalInterface
    public interface FailureRecorder {

        /**
         * Records one failure.
         *
         * @param url the URL or name of what failed
         * @param cause the cause
         */
        void record(String url, Throwable cause);
    }

    /**
     * The outcome of processing one law.
     *
     * @author Oliver Flasch
     */
    private enum Outcome {
        /** The archive was not modified. */
        UNCHANGED,
        /** All units of the law were stored. */
        REINDEXED,
        /** The law failed and keeps its indexed documents. */
        FAILED
    }

    /** The validated parameters. */
    private final GiiParams params;

    /** The public base URL of the site, used to build document URLs. */
    private final String publicBaseUrl;

    /** Requests the table of contents, the archives and the index pages. */
    private final HttpFetcher fetcher;

    /** The stored validators and the deletions. */
    private final IndexState indexState;

    /** Receives the units of changed laws. */
    private final DocumentSink sink;

    /** Receives the failures. */
    private final FailureRecorder failureRecorder;

    /** Sends the documents buffered by the sink to the index. */
    private final Runnable commit;

    /** The identifier of the current crawl session. */
    private final String sessionId;

    /** Reports whether the data store is still running. */
    private final BooleanSupplier alive;

    /** The number of documents passed to the sink in this run. */
    private long stored;

    /**
     * Creates a synchronizer for one run.
     *
     * @param params the validated parameters
     * @param publicBaseUrl the public base URL of the site, without a trailing slash
     * @param fetcher requests the table of contents, the archives and the index pages
     * @param indexState the stored validators and the deletions
     * @param sink receives the units of changed laws
     * @param failureRecorder receives the failures
     * @param commit sends the documents buffered by the sink to the index
     * @param sessionId the identifier of the current crawl session
     * @param alive reports whether the data store is still running
     */
    public GiiSynchronizer(final GiiParams params, final String publicBaseUrl, final HttpFetcher fetcher, final IndexState indexState,
            final DocumentSink sink, final FailureRecorder failureRecorder, final Runnable commit, final String sessionId,
            final BooleanSupplier alive) {
        this.params = params;
        this.publicBaseUrl = publicBaseUrl;
        this.fetcher = fetcher;
        this.indexState = indexState;
        this.sink = sink;
        this.failureRecorder = failureRecorder;
        this.commit = commit;
        this.sessionId = sessionId;
        this.alive = alive;
    }

    /**
     * Deletes all documents of the configuration.
     *
     * @return the number of deleted documents
     * @throws DataStoreException if the deletion fails
     */
    public long purge() {
        try {
            indexState.refresh();
            final long deleted = indexState.deleteAll();
            logger.info("Purged {} documents.", deleted);
            return deleted;
        } catch (final RuntimeException e) {
            failureRecorder.record("purge", e);
            throw new DataStoreException("Failed to purge the documents of the configuration.", e);
        }
    }

    /**
     * Runs the synchronization.
     *
     * @return the counters of the run
     * @throws DataStoreException if the state or the table of contents cannot be read; nothing
     *             is deleted in that case
     */
    public RunSummary run() {
        final long start = System.currentTimeMillis();
        if (sessionId == null || sessionId.isBlank()) {
            throw new DataStoreException("The crawl session identifier is not set.");
        }

        // 1. register the mapping and load the state
        final Map<String, Set<String>> state;
        try {
            indexState.registerFields();
            indexState.refresh();
            state = indexState.loadValidators();
        } catch (final RuntimeException e) {
            throw new DataStoreException("Failed to read the synchronization state from the index.", e);
        }

        // 2. fetch and parse the table of contents
        final List<LawRef> listed = fetchToc();

        // 3. apply the laws filter and the limit
        final List<LawRef> selected = select(listed);

        // 4. process the laws
        final List<String> completed = new ArrayList<>();
        int unchanged = 0;
        int failed = 0;
        int consecutiveFailures = 0;
        boolean finished = true;
        for (int i = 0; i < selected.size(); i++) {
            if (!alive.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                logger.info("The run was stopped after {} of {} laws.", i, selected.size());
                finished = false;
                break;
            }
            if (i > 0) {
                pause(params.readInterval());
            }
            final LawRef ref = selected.get(i);
            final Outcome outcome = process(ref, state.getOrDefault(ref.slug(), Set.of()));
            if (outcome == Outcome.FAILED) {
                failed++;
                consecutiveFailures++;
                if (consecutiveFailures >= params.maxConsecutiveFailures()) {
                    logger.error("{} consecutive laws failed; ending the run after {} of {} laws.", consecutiveFailures, i + 1,
                            selected.size());
                    finished = false;
                    break;
                }
            } else {
                consecutiveFailures = 0;
                if (outcome == Outcome.UNCHANGED) {
                    unchanged++;
                } else {
                    completed.add(ref.slug());
                }
            }
        }

        // 5. send the buffered documents and make them visible, 6. delete what left the site
        long deleted = 0;
        try {
            commit.run();
            indexState.refresh();
            deleted += deleteStaleSections(completed);
            if (finished && !params.isRestricted()) {
                deleted += deleteRemovedLaws(state.keySet(), listed);
            }
        } catch (final RuntimeException e) {
            logger.error("Failed to commit the run; no document was deleted.", e);
            failureRecorder.record("commit", e);
        }

        // 7. report
        final RunSummary summary = new RunSummary(listed.size(), unchanged, completed.size(), failed, stored, deleted, finished);
        logger.info("Run finished in {} ms: {} laws listed, {} unchanged, {} re-indexed, {} failed; {} documents stored, {} deleted.",
                System.currentTimeMillis() - start, summary.listed(), summary.unchanged(), summary.reindexed(), summary.failed(),
                summary.stored(), summary.deleted());
        return summary;
    }

    /**
     * Fetches and parses the table of contents.
     *
     * @return the listed laws
     * @throws DataStoreException if the table of contents cannot be fetched or is invalid
     */
    private List<LawRef> fetchToc() {
        try {
            final HttpFetcher.Response response = fetcher.get(TOC_PATH, Optional.empty(), params.maxTocSize());
            if (response.status() != 200) {
                throw new DataStoreException("HTTP " + response.status() + " for the table of contents.");
            }
            return TocParser.parse(response.body());
        } catch (final IOException e) {
            throw new DataStoreException("Failed to read the table of contents.", e);
        }
    }

    /**
     * Applies the {@code laws} filter and the {@code limit} parameter.
     *
     * @param listed the laws of the table of contents
     * @return the laws this run processes, in the order of the table of contents
     */
    private List<LawRef> select(final List<LawRef> listed) {
        List<LawRef> selected = listed;
        if (!params.laws().isEmpty()) {
            selected = listed.stream().filter(ref -> params.laws().contains(ref.slug())).toList();
            if (selected.size() < params.laws().size()) {
                logger.warn("{} of the {} configured laws are not in the table of contents.", params.laws().size() - selected.size(),
                        params.laws().size());
            }
        }
        if (params.limit() > 0 && selected.size() > params.limit()) {
            selected = selected.subList(0, params.limit());
        }
        return selected;
    }

    /**
     * Processes one law and records its failure.
     *
     * @param ref the law
     * @param validators the validators of its indexed documents
     * @return the outcome
     */
    private Outcome process(final LawRef ref, final Set<String> validators) {
        final String path = "/" + ref.slug() + "/xml.zip";
        try {
            return synchronize(ref.slug(), path, validators);
        } catch (final IOException | RuntimeException e) {
            logger.warn("Law {} failed: {}", ref.slug(), e.toString());
            if (logger.isDebugEnabled()) {
                logger.debug("Failure of law {}", ref.slug(), e);
            }
            failureRecorder.record(publicBaseUrl + path, e);
            return Outcome.FAILED;
        }
    }

    /**
     * Requests the archive of a law and re-indexes the law when the archive changed.
     *
     * @param slug the slug of the law
     * @param path the path of the archive
     * @param validators the validators of the indexed documents of the law
     * @return {@link Outcome#UNCHANGED} or {@link Outcome#REINDEXED}
     * @throws IOException if the archive cannot be fetched, exceeds a limit or is malformed
     */
    private Outcome synchronize(final String slug, final String path, final Set<String> validators) throws IOException {
        // 1. a conditional request needs exactly one usable stored validator
        final Optional<String> condition = !params.full() && validators.size() == 1
                ? validators.stream().filter(validator -> !NO_VALIDATOR.equals(validator)).findFirst()
                : Optional.empty();
        final HttpFetcher.Response response = fetcher.get(path, condition, params.maxZipSize());
        if (response.status() == 304 && condition.isPresent()) {
            return Outcome.UNCHANGED;
        }
        if (response.status() != 200) {
            throw new IOException("HTTP " + response.status() + " for the archive.");
        }

        // 2. parse the law and resolve the URLs of its units
        final Law law = LawParser.parse(LawArchive.extractXml(response.body(), params.maxXmlSize()));
        final List<String> urls = SectionLinkResolver.resolve(publicBaseUrl, slug, law, fetchIndexPage(slug, law));
        if (new HashSet<>(urls).size() != urls.size()) {
            throw new IOException("The units of the law do not have unique URLs.");
        }

        // 3. store every unit with the new validator
        final String validator = response.validator().orElse(NO_VALIDATOR);
        for (int i = 0; i < urls.size(); i++) {
            sink.store(slug, law, law.sections().get(i), urls.get(i), validator);
            stored++;
        }
        return Outcome.REINDEXED;
    }

    /**
     * Fetches the index page of a law. A failure is logged and leads to anchor URLs.
     *
     * @param slug the slug of the law
     * @param law the parsed law
     * @return the HTML of the index page; empty when the law has no sections or the page is unavailable
     */
    private Optional<String> fetchIndexPage(final String slug, final Law law) {
        if (law.isLawLevelOnly()) {
            return Optional.empty();
        }
        try {
            final HttpFetcher.Response response = fetcher.get("/" + slug + "/index.html", Optional.empty(), MAX_INDEX_PAGE_SIZE);
            if (response.status() == 200) {
                // page names are ASCII, so the single-byte charset of the site decodes them losslessly
                return Optional.of(new String(response.body(), StandardCharsets.ISO_8859_1));
            }
            logger.warn("HTTP {} for the index page of law {}.", response.status(), slug);
        } catch (final IOException e) {
            logger.warn("The index page of law {} failed: {}", slug, e.toString());
        }
        return Optional.empty();
    }

    /**
     * Deletes the documents of completed laws that this run did not store.
     *
     * @param completed the slugs of the laws that were stored completely
     * @return the number of deleted documents
     */
    private long deleteStaleSections(final List<String> completed) {
        long deleted = 0;
        for (final String slug : completed) {
            try {
                deleted += indexState.deleteStaleSections(slug, sessionId);
            } catch (final RuntimeException e) {
                logger.warn("Failed to delete the stale documents of law {}.", slug, e);
                failureRecorder.record(publicBaseUrl + "/" + slug + "/", e);
            }
        }
        return deleted;
    }

    /**
     * Deletes the documents of laws that are indexed and no longer listed.
     * <p>
     * A table of contents that lost more than {@code max_deletion_ratio} of the indexed laws is
     * treated as defective: nothing is deleted.
     * </p>
     *
     * @param indexed the slugs of the indexed laws
     * @param listed the laws of the table of contents
     * @return the number of deleted documents
     */
    private long deleteRemovedLaws(final Set<String> indexed, final List<LawRef> listed) {
        final Set<String> removed = new TreeSet<>(indexed);
        listed.forEach(ref -> removed.remove(ref.slug()));
        if (removed.isEmpty()) {
            return 0;
        }
        if (removed.size() > params.maxDeletionRatio() * indexed.size()) {
            final String message =
                    removed.size() + " of " + indexed.size() + " indexed laws are missing from the table of contents; this exceeds "
                            + GiiParams.MAX_DELETION_RATIO + "=" + params.maxDeletionRatio() + ", so no law was removed.";
            logger.error(message);
            failureRecorder.record(publicBaseUrl + TOC_PATH, new DataStoreException(message));
            return 0;
        }
        long deleted = 0;
        for (final String slug : removed) {
            try {
                deleted += indexState.deleteLaw(slug);
                logger.info("Removed law {}, which is no longer listed.", slug);
            } catch (final RuntimeException e) {
                logger.warn("Failed to remove law {}.", slug, e);
                failureRecorder.record(publicBaseUrl + "/" + slug + "/", e);
            }
        }
        return deleted;
    }

    /**
     * Waits between two laws.
     *
     * @param millis the wait in milliseconds
     */
    protected void pause(final long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
