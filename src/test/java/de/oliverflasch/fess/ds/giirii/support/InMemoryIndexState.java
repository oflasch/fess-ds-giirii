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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * An {@link IndexState} that keeps the documents of one configuration in memory.
 *
 * @author Oliver Flasch
 */
public final class InMemoryIndexState implements IndexState {

    /**
     * An indexed document.
     *
     * @param law the slug of the law
     * @param validator the validator of the law archive
     * @param segment the crawl session that stored the document
     * @author Oliver Flasch
     */
    public record Doc(String law, String validator, String segment) {
    }

    /** The documents by URL, which is their identity. */
    private final Map<String, Doc> documents = new LinkedHashMap<>();

    /** The names of the operations that were called, in order. */
    private final List<String> calls = new ArrayList<>();

    /** When true, every operation fails. */
    private boolean failing;

    /**
     * Stores or replaces a document.
     *
     * @param url the URL of the document
     * @param doc the document
     */
    public void put(final String url, final Doc doc) {
        documents.put(url, doc);
    }

    /**
     * Returns the documents.
     *
     * @return the documents by URL
     */
    public Map<String, Doc> documents() {
        return documents;
    }

    /**
     * Returns the URLs of the documents of a law.
     *
     * @param slug the slug of the law
     * @return the URLs in insertion order
     */
    public List<String> urls(final String slug) {
        return documents.entrySet().stream().filter(entry -> slug.equals(entry.getValue().law())).map(Map.Entry::getKey).toList();
    }

    /**
     * Returns the names of the operations that were called.
     *
     * @return the names in order
     */
    public List<String> calls() {
        return calls;
    }

    /**
     * Makes every operation fail.
     *
     * @param failing true to fail
     */
    public void setFailing(final boolean failing) {
        this.failing = failing;
    }

    /**
     * Records an operation and fails when configured to.
     *
     * @param name the name of the operation
     */
    private void call(final String name) {
        calls.add(name);
        if (failing) {
            throw new IllegalStateException("index unavailable");
        }
    }

    @Override
    public void registerFields() {
        call("registerFields");
    }

    @Override
    public void refresh() {
        call("refresh");
    }

    @Override
    public Map<String, Set<String>> loadValidators() {
        call("loadValidators");
        final Map<String, Set<String>> validators = new HashMap<>();
        documents.values().forEach(doc -> validators.computeIfAbsent(doc.law(), slug -> new TreeSet<>()).add(doc.validator()));
        return validators;
    }

    @Override
    public long deleteStaleSections(final String slug, final String sessionId) {
        call("deleteStaleSections:" + slug);
        return remove(doc -> slug.equals(doc.law()) && !sessionId.equals(doc.segment()));
    }

    @Override
    public long deleteLaw(final String slug) {
        call("deleteLaw:" + slug);
        return remove(doc -> slug.equals(doc.law()));
    }

    @Override
    public long deleteAll() {
        call("deleteAll");
        return remove(doc -> true);
    }

    /**
     * Removes the matching documents.
     *
     * @param filter selects the documents to remove
     * @return the number of removed documents
     */
    private long remove(final java.util.function.Predicate<Doc> filter) {
        final int before = documents.size();
        documents.values().removeIf(filter);
        return before - documents.size();
    }
}
