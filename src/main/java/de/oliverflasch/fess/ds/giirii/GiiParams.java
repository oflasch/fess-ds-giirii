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

import de.oliverflasch.fess.ds.giirii.support.TocParser;
import java.util.LinkedHashSet;
import java.util.Set;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.exception.DataStoreException;

/**
 * The validated parameters of a data store configuration.
 *
 * @param purge true deletes all documents of the configuration and ends the run
 * @param full true re-indexes every law regardless of its validator
 * @param laws the slugs the run is restricted to; empty for all laws
 * @param limit the maximum number of laws processed; 0 for no limit
 * @param readInterval the wait between two laws in milliseconds
 * @param userAgent the User-Agent header value; blank when not configured
 * @param maxDigestLength the length of the digest in characters
 * @param maxTocSize the maximum size of the table of contents in bytes
 * @param maxZipSize the maximum size of a law archive in bytes
 * @param maxXmlSize the maximum size of an inflated law document in bytes
 * @param maxDeletionRatio the largest share of indexed laws that one run removes
 * @param maxConsecutiveFailures the number of consecutive failed laws that ends the processing loop
 * @author Oliver Flasch
 */
public record GiiParams(boolean purge, boolean full, Set<String> laws, int limit, long readInterval, String userAgent, int maxDigestLength,
        int maxTocSize, int maxZipSize, int maxXmlSize, double maxDeletionRatio, int maxConsecutiveFailures) {

    /** The parameter that requests a purge run. */
    public static final String PURGE = "purge";

    /** The parameter that requests a complete re-index. */
    public static final String FULL = "full";

    /** The parameter that restricts the run to a list of laws. */
    public static final String LAWS = "laws";

    /** The parameter that limits the number of processed laws. */
    public static final String LIMIT = "limit";

    /** The parameter for the wait between two laws. */
    public static final String READ_INTERVAL = "read_interval";

    /** The parameter for the User-Agent header. */
    public static final String USER_AGENT = "user_agent";

    /** The parameter for the digest length. */
    public static final String MAX_DIGEST_LENGTH = "max_digest_length";

    /** The parameter for the size limit of the table of contents. */
    public static final String MAX_TOC_SIZE = "max_toc_size";

    /** The parameter for the size limit of a law archive. */
    public static final String MAX_ZIP_SIZE = "max_zip_size";

    /** The parameter for the size limit of an inflated law document. */
    public static final String MAX_XML_SIZE = "max_xml_size";

    /** The parameter for the share of indexed laws one run removes. */
    public static final String MAX_DELETION_RATIO = "max_deletion_ratio";

    /** The parameter for the number of consecutive failures that ends the processing loop. */
    public static final String MAX_CONSECUTIVE_FAILURES = "max_consecutive_failures";

    /** The largest size limit: documents are held in memory as byte arrays. */
    private static final long MAX_SIZE = Integer.MAX_VALUE - 8L;

    /**
     * Creates the parameters with an immutable copy of the law list.
     */
    public GiiParams {
        laws = Set.copyOf(laws);
    }

    /**
     * Returns whether the run processes a subset of the table of contents.
     *
     * @return true when {@code laws} or {@code limit} is set
     */
    public boolean isRestricted() {
        return !laws.isEmpty() || limit > 0;
    }

    /**
     * Reads and validates the parameters of a data store configuration.
     *
     * @param paramMap the parameters of the configuration
     * @return the validated parameters
     * @throws DataStoreException if a value cannot be parsed, lies outside its range, or
     *             {@code purge} is combined with {@code full}, {@code laws} or {@code limit}
     */
    public static GiiParams of(final DataStoreParams paramMap) {
        final boolean purge = getBoolean(paramMap, PURGE);
        final boolean full = getBoolean(paramMap, FULL);
        final Set<String> laws = getLaws(paramMap);
        final int limit = (int) getLong(paramMap, LIMIT, 0, 0, Integer.MAX_VALUE);
        if (purge && (full || !laws.isEmpty() || limit > 0)) {
            throw new DataStoreException(
                    "The parameter " + PURGE + " cannot be combined with " + FULL + ", " + LAWS + " or " + LIMIT + ".");
        }
        final String userAgent = paramMap.getAsString(USER_AGENT, "").trim();
        return new GiiParams(purge, full, laws, limit, getLong(paramMap, READ_INTERVAL, 200, 0, 3_600_000L), userAgent,
                (int) getLong(paramMap, MAX_DIGEST_LENGTH, 200, 1, 100_000),
                (int) getLong(paramMap, MAX_TOC_SIZE, 16_777_216L, 1, MAX_SIZE),
                (int) getLong(paramMap, MAX_ZIP_SIZE, 67_108_864L, 1, MAX_SIZE),
                (int) getLong(paramMap, MAX_XML_SIZE, 268_435_456L, 1, MAX_SIZE), getRatio(paramMap, MAX_DELETION_RATIO, 0.1),
                (int) getLong(paramMap, MAX_CONSECUTIVE_FAILURES, 20, 1, Integer.MAX_VALUE));
    }

    /**
     * Reads a boolean parameter.
     *
     * @param paramMap the parameters of the configuration
     * @param key the parameter name
     * @return the value; false when the parameter is absent or blank
     * @throws DataStoreException if the value is neither {@code true} nor {@code false}
     */
    private static boolean getBoolean(final DataStoreParams paramMap, final String key) {
        final String value = paramMap.getAsString(key, "").trim();
        if (value.isEmpty() || "false".equalsIgnoreCase(value)) {
            return false;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        throw new DataStoreException("The parameter " + key + " must be true or false.");
    }

    /**
     * Reads an integer parameter.
     *
     * @param paramMap the parameters of the configuration
     * @param key the parameter name
     * @param defaultValue the value used when the parameter is absent or blank
     * @param min the smallest valid value
     * @param max the largest valid value
     * @return the value
     * @throws DataStoreException if the value is not an integer between {@code min} and {@code max}
     */
    private static long getLong(final DataStoreParams paramMap, final String key, final long defaultValue, final long min, final long max) {
        final String value = paramMap.getAsString(key, "").trim();
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            final long number = Long.parseLong(value);
            if (number >= min && number <= max) {
                return number;
            }
        } catch (final NumberFormatException e) {
            // reported below together with the range violation
        }
        throw new DataStoreException("The parameter " + key + " must be an integer between " + min + " and " + max + ".");
    }

    /**
     * Reads a ratio parameter.
     *
     * @param paramMap the parameters of the configuration
     * @param key the parameter name
     * @param defaultValue the value used when the parameter is absent or blank
     * @return the value
     * @throws DataStoreException if the value is not a number between 0 and 1
     */
    private static double getRatio(final DataStoreParams paramMap, final String key, final double defaultValue) {
        final String value = paramMap.getAsString(key, "").trim();
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            final double number = Double.parseDouble(value);
            if (number >= 0.0 && number <= 1.0) {
                return number;
            }
        } catch (final NumberFormatException e) {
            // reported below together with the range violation
        }
        throw new DataStoreException("The parameter " + key + " must be a number between 0 and 1.");
    }

    /**
     * Reads the list of laws the run is restricted to.
     *
     * @param paramMap the parameters of the configuration
     * @return the slugs in the configured order; empty when the parameter is absent or blank
     * @throws DataStoreException if an entry is not a valid slug
     */
    private static Set<String> getLaws(final DataStoreParams paramMap) {
        final Set<String> laws = new LinkedHashSet<>();
        final String value = paramMap.getAsString(LAWS, "").trim();
        if (value.isEmpty()) {
            return laws;
        }
        for (final String entry : value.split(",")) {
            final String slug = entry.trim();
            if (!TocParser.isSlug(slug)) {
                throw new DataStoreException("The parameter " + LAWS + " must be a comma-separated list of law slugs.");
            }
            laws.add(slug);
        }
        return laws;
    }
}
