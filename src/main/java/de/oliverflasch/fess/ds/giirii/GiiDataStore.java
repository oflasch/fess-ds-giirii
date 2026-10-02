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
import de.oliverflasch.fess.ds.giirii.support.SearchEngineIndexState;
import de.oliverflasch.fess.ds.giirii.support.Section;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.fess.Constants;
import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.ds.AbstractDataStore;
import org.codelibs.fess.ds.callback.IndexUpdateCallback;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.exception.DataStoreException;
import org.codelibs.fess.helper.CrawlerStatsHelper;
import org.codelibs.fess.helper.CrawlerStatsHelper.StatsAction;
import org.codelibs.fess.helper.CrawlerStatsHelper.StatsKeyObject;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.util.ComponentUtil;

/**
 * The Fess data store for the German federal statutes published on gesetze-im-internet.de.
 * <p>
 * The data store indexes one document per section of every law and keeps the index current
 * through conditional requests. It owns the deletion of its documents: Fess deletes every
 * document a run did not store, which removes the unchanged laws of an incremental run, so that
 * deletion is switched off and replaced by {@link GiiSynchronizer}.
 * </p>
 *
 * @author Oliver Flasch
 */
public class GiiDataStore extends AbstractDataStore {

    /** The logger. */
    private static final Logger logger = LogManager.getLogger(GiiDataStore.class);

    /** The host of the site. */
    public static final String HOST = "www.gesetze-im-internet.de";

    /** The base URL of the site. */
    public static final String BASE_URL = "https://" + HOST;

    /** The Fess parameter that controls the deletion of documents a run did not store. */
    protected static final String DELETE_OLD_DOCS = "delete_old_docs";

    /**
     * Creates the data store.
     */
    public GiiDataStore() {
        super();
    }

    /**
     * Returns the handler name under which Fess registers the data store.
     *
     * @return the simple class name
     */
    @Override
    protected String getName() {
        return this.getClass().getSimpleName();
    }

    /**
     * Runs the data store with the deletion by Fess switched off.
     * <p>
     * Fess reads {@code delete_old_docs} from {@code initParamMap} after this method returns. The
     * value is set again after the run because the base class copies the parameters of the
     * configuration into the same map, where the operator can have set it.
     * </p>
     *
     * @param config the data store configuration
     * @param callback receives the documents
     * @param initParamMap the initial parameters, which Fess reads again after the run
     */
    @Override
    public void store(final DataConfig config, final IndexUpdateCallback callback, final DataStoreParams initParamMap) {
        initParamMap.put(DELETE_OLD_DOCS, Constants.FALSE);
        try {
            super.store(config, callback, initParamMap);
        } finally {
            initParamMap.put(DELETE_OLD_DOCS, Constants.FALSE);
        }
    }

    /**
     * Runs one synchronization, or one purge when the configuration requests it.
     *
     * @param dataConfig the data store configuration
     * @param callback receives the documents
     * @param paramMap the parameters of the run
     * @param scriptMap the script entries of the configuration: field name to script
     * @param defaultDataMap the default fields Fess supplies for every document of the configuration
     * @throws DataStoreException if a parameter is invalid, or the state or the table of contents cannot be read
     */
    @Override
    protected void storeData(final DataConfig dataConfig, final IndexUpdateCallback callback, final DataStoreParams paramMap,
            final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap) {
        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        final GiiParams params = GiiParams.of(paramMap);
        final String configId = dataConfig.getConfigId();
        if (StringUtil.isBlank(configId)) {
            throw new DataStoreException("The data store configuration has no identifier.");
        }

        // 1. documents of unchanged laws must outlive the Fess purge job, so none carries an expiry
        final Map<String, Object> defaults = new HashMap<>(defaultDataMap);
        defaults.remove(fessConfig.getIndexFieldExpires());

        final IndexState indexState =
                new SearchEngineIndexState(ComponentUtil.getSearchEngineClient(), fessConfig.getIndexDocumentUpdateIndex(), configId,
                        new SearchEngineIndexState.Fields(fessConfig.getIndexFieldConfigId(), fessConfig.getIndexFieldSegment(),
                                DocumentAssembler.LAW_FIELD, DocumentAssembler.VALIDATOR_FIELD, DocumentAssembler.KEYWORD_FIELDS));
        final FailureUrlService failureUrlService = ComponentUtil.getComponent(FailureUrlService.class);
        final GiiSynchronizer.FailureRecorder failureRecorder =
                (url, cause) -> failureUrlService.store(dataConfig, cause.getClass().getCanonicalName(), url, cause);

        // 2. the fields a script cannot change
        final String scriptType = getScriptType(paramMap);
        final Set<String> protectedFields =
                Set.of(DocumentAssembler.URL_FIELD, DocumentAssembler.LAW_FIELD, DocumentAssembler.VALIDATOR_FIELD,
                        fessConfig.getIndexFieldSegment(), fessConfig.getIndexFieldConfigId(), fessConfig.getIndexFieldExpires());
        final DocumentAssembler assembler = new DocumentAssembler(HOST, params.maxDigestLength(), scriptMap,
                (script, values) -> convertValue(scriptType, script, values), protectedFields,
                () -> new Date(ComponentUtil.getSystemHelper().getCurrentTimeAsLong()));
        final Map<String, Object> parameters = paramMap.asMap();

        try (HttpFetcher fetcher = new HttpFetcher(BASE_URL, getUserAgent(params), HttpFetcher.DEFAULT_REQUEST_TIMEOUT)) {
            final GiiSynchronizer synchronizer = new GiiSynchronizer(params, BASE_URL, fetcher, indexState,
                    (slug, law, section, url, validator) -> storeDocument(dataConfig, callback, paramMap,
                            assembler.assemble(defaults, parameters, slug, law, section, url, validator), law, section),
                    failureRecorder, callback::commit, paramMap.getAsString(Constants.SESSION_ID), () -> alive);
            if (params.purge()) {
                synchronizer.purge();
            } else {
                synchronizer.run();
            }
        }
    }

    /**
     * Passes one document to the index and records its crawler statistics.
     *
     * @param dataConfig the data store configuration
     * @param callback receives the document
     * @param paramMap the parameters of the run
     * @param document the fields of the document
     * @param law the law the document belongs to
     * @param section the unit the document was built from
     */
    private void storeDocument(final DataConfig dataConfig, final IndexUpdateCallback callback, final DataStoreParams paramMap,
            final Map<String, Object> document, final Law law, final Section section) {
        final CrawlerStatsHelper crawlerStatsHelper = ComponentUtil.getCrawlerStatsHelper();
        final StatsKeyObject statsKey = new StatsKeyObject(dataConfig.getId() + "#" + section.doknr());
        paramMap.put(Constants.CRAWLER_STATS_KEY, statsKey);
        try {
            crawlerStatsHelper.begin(statsKey);
            if (document.get(DocumentAssembler.URL_FIELD) instanceof final String url) {
                statsKey.setUrl(url);
            }
            crawlerStatsHelper.record(statsKey, StatsAction.EVALUATED);
            if (logger.isDebugEnabled()) {
                logger.debug("Storing {} of law {}", section.doknr(), law.doknr());
            }
            callback.store(paramMap, document);
            crawlerStatsHelper.record(statsKey, StatsAction.FINISHED);
        } catch (final RuntimeException e) {
            crawlerStatsHelper.record(statsKey, StatsAction.EXCEPTION);
            throw e;
        } finally {
            crawlerStatsHelper.done(statsKey);
        }
    }

    /**
     * Returns the User-Agent of all requests.
     *
     * @param params the validated parameters
     * @return the configured User-Agent, or the Fess crawler User-Agent when none is configured;
     *         {@link HttpFetcher} replaces a blank value by its fallback
     */
    private String getUserAgent(final GiiParams params) {
        if (StringUtil.isNotBlank(params.userAgent())) {
            return params.userAgent();
        }
        return ComponentUtil.getFessConfig().getUserAgentName();
    }
}
