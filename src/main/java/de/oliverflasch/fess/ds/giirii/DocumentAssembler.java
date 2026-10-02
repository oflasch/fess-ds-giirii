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

import de.oliverflasch.fess.ds.giirii.support.Law;
import de.oliverflasch.fess.ds.giirii.support.Section;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Builds the index document of one indexable unit of a law.
 * <p>
 * The assembler fills every field itself. The script entries of the data store configuration
 * replace or add fields afterwards, except for the protected fields the synchronization depends
 * on.
 * </p>
 *
 * @author Oliver Flasch
 */
public final class DocumentAssembler {

    /** The field that carries the slug of the law. */
    public static final String LAW_FIELD = "gii_law";

    /** The field that carries the validator of the law archive. */
    public static final String VALIDATOR_FIELD = "gii_validator";

    /** The field that carries the abbreviation of the law. */
    public static final String JURABK_FIELD = "gii_jurabk";

    /** The field that carries the section label. */
    public static final String ENBEZ_FIELD = "gii_enbez";

    /** The field that carries the document number of the norm. */
    public static final String DOKNR_FIELD = "gii_doknr";

    /** The field that carries the breadcrumb of the section. */
    public static final String PATH_FIELD = "gii_path";

    /** The fields the plugin registers as {@code keyword} in the index mapping. */
    public static final List<String> KEYWORD_FIELDS =
            List.of(LAW_FIELD, VALIDATOR_FIELD, JURABK_FIELD, ENBEZ_FIELD, DOKNR_FIELD, PATH_FIELD);

    /** The field that carries the URL and thereby the identity of the document. */
    public static final String URL_FIELD = "url";

    /** The separator between the section label with abbreviation and the section title. */
    private static final String TITLE_SEPARATOR = " – ";

    /** The host of the site, used for the {@code host} and {@code site} fields. */
    private final String host;

    /** The length of the digest in characters. */
    private final int maxDigestLength;

    /** The script entries of the configuration: field name to script. */
    private final Map<String, String> scripts;

    /** Evaluates a script against the values of a document. */
    private final BiFunction<String, Map<String, Object>, Object> evaluator;

    /** The fields a script cannot change. */
    private final Set<String> protectedFields;

    /** Supplies the time of indexing. */
    private final Supplier<Date> clock;

    /**
     * Creates an assembler.
     *
     * @param host the host of the site
     * @param maxDigestLength the length of the digest in characters
     * @param scripts the script entries of the configuration: field name to script
     * @param evaluator evaluates a script against the values of a document; a null result leaves the field unchanged
     * @param protectedFields the fields a script cannot change
     * @param clock supplies the time of indexing
     */
    public DocumentAssembler(final String host, final int maxDigestLength, final Map<String, String> scripts,
            final BiFunction<String, Map<String, Object>, Object> evaluator, final Set<String> protectedFields,
            final Supplier<Date> clock) {
        this.host = host;
        this.maxDigestLength = maxDigestLength;
        this.scripts = Map.copyOf(scripts);
        this.evaluator = evaluator;
        this.protectedFields = Set.copyOf(protectedFields);
        this.clock = clock;
    }

    /**
     * Builds the index document of one unit.
     *
     * @param defaults the default fields Fess supplies for every document of the configuration
     * @param parameters the parameters of the configuration, which scripts can reference
     * @param slug the slug of the law
     * @param law the law
     * @param section the unit to index
     * @param url the URL of the unit
     * @param validator the validator of the law archive
     * @return the fields of the document
     */
    public Map<String, Object> assemble(final Map<String, Object> defaults, final Map<String, Object> parameters, final String slug,
            final Law law, final Section section, final String url, final String validator) {
        // 1. the fields the plugin fills itself
        final Map<String, Object> fields = fields(slug, law, section, url, validator);
        final Map<String, Object> document = new HashMap<>(defaults);
        document.putAll(fields);
        final Map<String, Object> protectedValues = new HashMap<>();
        for (final String field : protectedFields) {
            if (document.containsKey(field)) {
                protectedValues.put(field, document.get(field));
            }
        }

        // 2. script entries replace or add fields
        if (!scripts.isEmpty()) {
            final Map<String, Object> context = new LinkedHashMap<>(parameters);
            context.putAll(fields);
            context.put("footnotes", section.footnotes());
            context.put("law_title", law.longTitle());
            context.put("law_short_title", law.shortTitle());
            context.put("law_date", law.date());
            context.put("law_status", law.status());
            context.put("law_doknr", law.doknr());
            for (final Map.Entry<String, String> entry : scripts.entrySet()) {
                final Object value = evaluator.apply(entry.getValue(), context);
                if (value != null) {
                    document.put(entry.getKey(), value);
                }
            }
        }

        // 3. the synchronization depends on the protected fields, so scripts cannot change them
        for (final String field : protectedFields) {
            if (protectedValues.containsKey(field)) {
                document.put(field, protectedValues.get(field));
            } else {
                document.remove(field);
            }
        }
        return document;
    }

    /**
     * Returns the fields the plugin fills itself.
     *
     * @param slug the slug of the law
     * @param law the law
     * @param section the unit to index
     * @param url the URL of the unit
     * @param validator the validator of the law archive
     * @return the fields in a stable order
     */
    private Map<String, Object> fields(final String slug, final Law law, final Section section, final String url, final String validator) {
        final String title = title(law, section);
        final String content = section.content().isEmpty() ? title : section.content();
        final Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(URL_FIELD, url);
        fields.put("title", title);
        fields.put("content", content);
        fields.put("important_content", importantContent(law, section));
        fields.put("digest", digest(content));
        fields.put("content_length", (long) content.length());
        section.builddate().ifPresent(instant -> fields.put("last_modified", Date.from(instant)));
        fields.put("timestamp", clock.get());
        fields.put("host", host);
        fields.put("site", host);
        fields.put("lang", "de");
        fields.put("mimetype", "text/html");
        fields.put("filetype", "html");
        fields.put(LAW_FIELD, slug);
        fields.put(VALIDATOR_FIELD, validator);
        fields.put(JURABK_FIELD, section.jurabk());
        fields.put(DOKNR_FIELD, section.doknr());
        if (!section.label().isEmpty()) {
            fields.put(ENBEZ_FIELD, section.label());
        }
        if (!section.path().isEmpty()) {
            fields.put(PATH_FIELD, section.path());
        }
        return fields;
    }

    /**
     * Returns the title of a unit.
     *
     * @param law the law
     * @param section the unit
     * @return the unit, label, abbreviation and section title of a section; the long title and
     *         abbreviation of a law-level unit
     */
    private static String title(final Law law, final Section section) {
        if (section.lawLevel()) {
            return law.longTitle().isEmpty() ? law.jurabk() : law.longTitle() + " (" + law.jurabk() + ")";
        }
        final String head = (section.unit().isEmpty() ? "" : section.unit() + " ") + section.label() + " " + section.jurabk();
        return section.title().isEmpty() ? head : head + TITLE_SEPARATOR + section.title();
    }

    /**
     * Returns the text that identifies the law and the position of the unit within it.
     *
     * @param law the law
     * @param section the unit
     * @return the titles and abbreviations of the law and the breadcrumb, one per line
     */
    private static String importantContent(final Law law, final Section section) {
        final List<String> parts = new ArrayList<>();
        for (final String part : List.of(law.longTitle(), law.shortTitle(), law.jurabk(), law.amtabk(), section.path())) {
            if (!part.isEmpty() && !parts.contains(part)) {
                parts.add(part);
            }
        }
        return String.join("\n", parts);
    }

    /**
     * Returns the digest of a content.
     *
     * @param content the content
     * @return the first {@code maxDigestLength} characters as a single line, never ending within a surrogate pair
     */
    private String digest(final String content) {
        int end = Math.min(content.length(), maxDigestLength);
        if (end > 0 && end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))) {
            end--;
        }
        return content.substring(0, end).replace('\n', ' ');
    }
}
