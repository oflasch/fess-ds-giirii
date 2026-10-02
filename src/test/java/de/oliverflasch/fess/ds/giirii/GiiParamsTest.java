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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.exception.DataStoreException;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link GiiParams}.
 *
 * @author Oliver Flasch
 */
public class GiiParamsTest {

    /**
     * Builds data store parameters.
     *
     * @param values the parameters
     * @return the parameter map
     */
    private static DataStoreParams params(final Map<String, String> values) {
        final DataStoreParams params = new DataStoreParams();
        params.putAll(values);
        return params;
    }

    @Test
    public void appliesTheDefaults() {
        final GiiParams params = GiiParams.of(params(Map.of()));
        assertFalse(params.purge(), "purge");
        assertFalse(params.full(), "full");
        assertEquals(Set.of(), params.laws(), "laws");
        assertEquals(0, params.limit(), "limit");
        assertEquals(200L, params.readInterval(), "read_interval");
        assertEquals("", params.userAgent(), "user_agent");
        assertEquals(200, params.maxDigestLength(), "max_digest_length");
        assertEquals(16_777_216, params.maxTocSize(), "max_toc_size");
        assertEquals(67_108_864, params.maxZipSize(), "max_zip_size");
        assertEquals(268_435_456, params.maxXmlSize(), "max_xml_size");
        assertEquals(0.1, params.maxDeletionRatio(), 0.0, "max_deletion_ratio");
        assertEquals(20, params.maxConsecutiveFailures(), "max_consecutive_failures");
        assertFalse(params.isRestricted(), "restricted");
    }

    @Test
    public void readsConfiguredValues() {
        final GiiParams params = GiiParams
                .of(params(Map.ofEntries(Map.entry("full", "TRUE"), Map.entry("laws", " gg , bgb,bimschv_1_2010 "), Map.entry("limit", "5"),
                        Map.entry("read_interval", "0"), Map.entry("user_agent", " agent "), Map.entry("max_digest_length", "80"),
                        Map.entry("max_toc_size", "1000"), Map.entry("max_zip_size", "2000"), Map.entry("max_xml_size", "3000"),
                        Map.entry("max_deletion_ratio", "0.5"), Map.entry("max_consecutive_failures", "3"))));
        assertTrue(params.full(), "full");
        assertEquals(Set.of("gg", "bgb", "bimschv_1_2010"), params.laws(), "laws");
        assertEquals(5, params.limit(), "limit");
        assertEquals(0L, params.readInterval(), "read_interval");
        assertEquals("agent", params.userAgent(), "user_agent");
        assertEquals(80, params.maxDigestLength(), "max_digest_length");
        assertEquals(1000, params.maxTocSize(), "max_toc_size");
        assertEquals(2000, params.maxZipSize(), "max_zip_size");
        assertEquals(3000, params.maxXmlSize(), "max_xml_size");
        assertEquals(0.5, params.maxDeletionRatio(), 0.0, "max_deletion_ratio");
        assertEquals(3, params.maxConsecutiveFailures(), "max_consecutive_failures");
        assertTrue(params.isRestricted(), "restricted");
    }

    @Test
    public void treatsALimitAsARestriction() {
        assertTrue(GiiParams.of(params(Map.of("limit", "1"))).isRestricted(), "limit");
        assertTrue(GiiParams.of(params(Map.of("laws", "gg"))).isRestricted(), "laws");
    }

    @Test
    public void refusesInvalidValuesAndNamesTheParameter() {
        for (final Map.Entry<String, String> invalid : Map
                .ofEntries(Map.entry("purge", "yes"), Map.entry("full", "1"), Map.entry("laws", "gg,../etc"), Map.entry("limit", "-1"),
                        Map.entry("read_interval", "soon"), Map.entry("max_digest_length", "0"), Map.entry("max_toc_size", "0"),
                        Map.entry("max_zip_size", "9999999999"), Map.entry("max_xml_size", "1.5"), Map.entry("max_deletion_ratio", "1.1"),
                        Map.entry("max_consecutive_failures", "0"))
                .entrySet()) {
            final DataStoreException e = assertThrows(DataStoreException.class,
                    () -> GiiParams.of(params(Map.of(invalid.getKey(), invalid.getValue()))), invalid.getKey());
            assertTrue(e.getMessage().contains(invalid.getKey()), "the message names " + invalid.getKey());
        }
        assertThrows(DataStoreException.class, () -> GiiParams.of(params(Map.of("laws", "gg,,bgb"))), "empty list entry");
        assertThrows(DataStoreException.class, () -> GiiParams.of(params(Map.of("max_deletion_ratio", "NaN"))), "not a number");
    }

    @Test
    public void refusesPurgeTogetherWithARestrictionOrFull() {
        assertTrue(GiiParams.of(params(Map.of("purge", "true"))).purge(), "purge alone");
        assertThrows(DataStoreException.class, () -> GiiParams.of(params(Map.of("purge", "true", "full", "true"))), "full");
        assertThrows(DataStoreException.class, () -> GiiParams.of(params(Map.of("purge", "true", "laws", "gg"))), "laws");
        assertThrows(DataStoreException.class, () -> GiiParams.of(params(Map.of("purge", "true", "limit", "1"))), "limit");
    }
}
