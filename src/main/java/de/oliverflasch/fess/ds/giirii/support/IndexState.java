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

import java.util.Map;
import java.util.Set;

/**
 * The view of the document index that the synchronization needs: the stored validators of the
 * indexed laws, and the deletions the plugin performs itself.
 * <p>
 * Every operation is restricted to the documents of one data store configuration.
 * </p>
 *
 * @author Oliver Flasch
 */
public interface IndexState {

    /**
     * Registers the fields of the plugin in the index mapping. The registration is idempotent.
     */
    void registerFields();

    /**
     * Makes the documents written so far visible to the queries of this interface.
     */
    void refresh();

    /**
     * Returns the validators stored on the indexed documents of each law.
     *
     * @return the validators by slug; a law with more than one validator is inconsistent
     */
    Map<String, Set<String>> loadValidators();

    /**
     * Deletes the documents of a law that the given crawl session did not store.
     *
     * @param slug the slug of the law
     * @param sessionId the identifier of the current crawl session
     * @return the number of deleted documents
     */
    long deleteStaleSections(String slug, String sessionId);

    /**
     * Deletes all documents of a law.
     *
     * @param slug the slug of the law
     * @return the number of deleted documents
     */
    long deleteLaw(String slug);

    /**
     * Deletes all documents of the configuration.
     *
     * @return the number of deleted documents
     */
    long deleteAll();
}
