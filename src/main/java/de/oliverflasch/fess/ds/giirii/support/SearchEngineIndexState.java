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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.codelibs.fess.opensearch.client.SearchEngineClient;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.clustermanager.AcknowledgedResponse;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.aggregations.bucket.composite.CompositeAggregation;
import org.opensearch.search.aggregations.bucket.composite.CompositeAggregationBuilder;
import org.opensearch.search.aggregations.bucket.composite.TermsValuesSourceBuilder;

/**
 * The {@link IndexState} of a Fess document index.
 *
 * @author Oliver Flasch
 */
public final class SearchEngineIndexState implements IndexState {

    /** The name of the composite aggregation and of its sources. */
    private static final String STATE_AGGREGATION = "state";

    /** The name of the aggregation source that carries the law. */
    private static final String LAW_SOURCE = "law";

    /** The name of the aggregation source that carries the validator. */
    private static final String VALIDATOR_SOURCE = "validator";

    /** The number of buckets one aggregation request returns. */
    private static final int PAGE_SIZE = 1000;

    /** The time allowed for one request to the search engine, in milliseconds. */
    private static final long REQUEST_TIMEOUT = 60_000L;

    /**
     * The fields of an index this class works with.
     *
     * @param configId the field that carries the identifier of the configuration
     * @param segment the field that carries the identifier of the crawl session
     * @param law the field that carries the slug of the law
     * @param validator the field that carries the validator of the law archive
     * @param keywords all fields the plugin registers as {@code keyword}
     * @author Oliver Flasch
     */
    public record Fields(String configId, String segment, String law, String validator, List<String> keywords) {

        /**
         * Creates the field names with an immutable copy of the keyword list.
         */
        public Fields {
            keywords = List.copyOf(keywords);
        }
    }

    /** The client of the search engine. */
    private final SearchEngineClient client;

    /** The name of the index or alias that documents are written to. */
    private final String index;

    /** The identifier of the data store configuration. */
    private final String configId;

    /** The field names. */
    private final Fields fields;

    /**
     * Creates the state of one configuration.
     *
     * @param client the client of the search engine
     * @param index the name of the index or alias that documents are written to
     * @param configId the identifier of the data store configuration; must not be blank
     * @param fields the field names
     */
    public SearchEngineIndexState(final SearchEngineClient client, final String index, final String configId, final Fields fields) {
        if (configId == null || configId.isBlank()) {
            throw new IllegalArgumentException("The configuration identifier must not be blank.");
        }
        this.client = client;
        this.index = index;
        this.configId = configId;
        this.fields = fields;
    }

    @Override
    public void registerFields() {
        final Map<String, Object> properties = new LinkedHashMap<>();
        for (final String field : fields.keywords()) {
            properties.put(field, Map.of("type", "keyword"));
        }
        final AcknowledgedResponse response = client.admin()
                .indices()
                .preparePutMapping(index)
                .setSource(Map.of("properties", properties))
                .execute()
                .actionGet(REQUEST_TIMEOUT);
        if (!response.isAcknowledged()) {
            throw new IllegalStateException("The search engine did not acknowledge the field mapping.");
        }
    }

    @Override
    public void refresh() {
        client.admin().indices().prepareRefresh(index).execute().actionGet(REQUEST_TIMEOUT);
    }

    @Override
    public Map<String, Set<String>> loadValidators() {
        final Map<String, Set<String>> validators = new HashMap<>();
        Map<String, Object> after = null;
        while (true) {
            // 1. request the next page of (law, validator) pairs
            final CompositeAggregationBuilder aggregation =
                    new CompositeAggregationBuilder(STATE_AGGREGATION, List.of(new TermsValuesSourceBuilder(LAW_SOURCE).field(fields.law()),
                            new TermsValuesSourceBuilder(VALIDATOR_SOURCE).field(fields.validator()))).size(PAGE_SIZE);
            if (after != null) {
                aggregation.aggregateAfter(after);
            }
            final SearchResponse response = client.prepareSearch(index)
                    .setQuery(QueryBuilders.termQuery(fields.configId(), configId))
                    .setSize(0)
                    .addAggregation(aggregation)
                    .execute()
                    .actionGet(REQUEST_TIMEOUT);

            // 2. collect the pairs; the last page is the one that is not full
            final CompositeAggregation result = response.getAggregations().get(STATE_AGGREGATION);
            for (final CompositeAggregation.Bucket bucket : result.getBuckets()) {
                final Map<String, Object> key = bucket.getKey();
                validators.computeIfAbsent(String.valueOf(key.get(LAW_SOURCE)), slug -> new TreeSet<>())
                        .add(String.valueOf(key.get(VALIDATOR_SOURCE)));
            }
            after = result.afterKey();
            if (after == null || result.getBuckets().size() < PAGE_SIZE) {
                return validators;
            }
        }
    }

    @Override
    public long deleteStaleSections(final String slug, final String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("The session identifier must not be blank.");
        }
        return client.deleteByQuery(index, lawQuery(slug).mustNot(QueryBuilders.termQuery(fields.segment(), sessionId)));
    }

    @Override
    public long deleteLaw(final String slug) {
        return client.deleteByQuery(index, lawQuery(slug));
    }

    @Override
    public long deleteAll() {
        return client.deleteByQuery(index, QueryBuilders.boolQuery().filter(QueryBuilders.termQuery(fields.configId(), configId)));
    }

    /**
     * Builds the query for all documents of a law within the configuration.
     *
     * @param slug the slug of the law
     * @return the query
     */
    private BoolQueryBuilder lawQuery(final String slug) {
        return QueryBuilders.boolQuery()
                .filter(QueryBuilders.termQuery(fields.configId(), configId))
                .filter(QueryBuilders.termQuery(fields.law(), slug));
    }
}
