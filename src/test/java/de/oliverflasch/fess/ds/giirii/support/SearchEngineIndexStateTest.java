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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.codelibs.fess.opensearch.client.SearchEngineClient;
import org.junit.jupiter.api.Test;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;

/**
 * Tests the delete queries of {@link SearchEngineIndexState}: every query is restricted to the
 * configuration.
 *
 * @author Oliver Flasch
 */
public class SearchEngineIndexStateTest {

    /** The field names of a Fess index. */
    private static final SearchEngineIndexState.Fields FIELDS =
            new SearchEngineIndexState.Fields("config_id", "segment", "gii_law", "gii_validator", List.of("gii_law", "gii_validator"));

    /** The queries the client received. */
    private final List<QueryBuilder> queries = new ArrayList<>();

    /** The indices the client received. */
    private final List<String> indices = new ArrayList<>();

    /** A client that records delete queries instead of sending them. */
    private final SearchEngineClient client = new SearchEngineClient() {
        @Override
        public long deleteByQuery(final String index, final QueryBuilder queryBuilder) {
            indices.add(index);
            queries.add(queryBuilder);
            return 7;
        }
    };

    /** The state under test. */
    private final SearchEngineIndexState state = new SearchEngineIndexState(client, "fess.update", "D1", FIELDS);

    @Test
    public void deletesTheStaleSectionsOfOneLawOfOneConfiguration() {
        assertEquals(7, state.deleteStaleSections("bgb", "session-2"), "deleted");
        final BoolQueryBuilder expected = QueryBuilders.boolQuery()
                .filter(QueryBuilders.termQuery("config_id", "D1"))
                .filter(QueryBuilders.termQuery("gii_law", "bgb"))
                .mustNot(QueryBuilders.termQuery("segment", "session-2"));
        assertEquals(expected, queries.get(0), "query");
        assertEquals("fess.update", indices.get(0), "index");
    }

    @Test
    public void deletesOneLawOfOneConfiguration() {
        state.deleteLaw("gg");
        assertEquals(QueryBuilders.boolQuery()
                .filter(QueryBuilders.termQuery("config_id", "D1"))
                .filter(QueryBuilders.termQuery("gii_law", "gg")), queries.get(0), "query");
    }

    @Test
    public void deletesOnlyTheDocumentsOfItsConfiguration() {
        state.deleteAll();
        assertEquals(QueryBuilders.boolQuery().filter(QueryBuilders.termQuery("config_id", "D1")), queries.get(0), "query");
    }

    @Test
    public void refusesABlankConfigurationOrSession() {
        assertThrows(IllegalArgumentException.class, () -> new SearchEngineIndexState(client, "fess.update", " ", FIELDS),
                "blank configuration");
        assertThrows(IllegalArgumentException.class, () -> new SearchEngineIndexState(client, "fess.update", null, FIELDS),
                "no configuration");
        assertThrows(IllegalArgumentException.class, () -> state.deleteStaleSections("bgb", ""), "blank session");
        assertTrue(queries.isEmpty(), "no query is sent");
    }
}
