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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link LawArchive}.
 *
 * @author Oliver Flasch
 */
public class LawArchiveTest {

    /** A small XML document. */
    private static final byte[] XML = "<dokumente/>".getBytes(StandardCharsets.UTF_8);

    @Test
    public void extractsTheSingleXmlEntry() throws IOException {
        assertArrayEquals(XML, LawArchive.extractXml(TestFixtures.zip("BJNR000010949.xml", XML), 1024), "content");
    }

    @Test
    public void extractsTheXmlEntryAmongImages() throws IOException {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("bgbl1_2013_j0367-1_0010.jpg", new byte[] { 1, 2, 3 });
        entries.put("BJNR036710013.XML", XML);
        entries.put("second.xml", "<other/>".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(XML, LawArchive.extractXml(TestFixtures.zip(entries), 1024), "the first XML entry");
    }

    @Test
    public void readsAnEntryWithATraversalNameWithoutUsingThePath() throws IOException {
        assertArrayEquals(XML, LawArchive.extractXml(TestFixtures.zip("../../x.xml", XML), 1024), "content");
    }

    @Test
    public void rejectsAnArchiveWithoutXml() {
        assertThrows(IOException.class, () -> LawArchive.extractXml(TestFixtures.zip("image.jpg", new byte[] { 1 }), 1024), "no XML");
        assertThrows(IOException.class, () -> LawArchive.extractXml("not a zip".getBytes(StandardCharsets.UTF_8), 1024), "no archive");
        assertThrows(IOException.class, () -> LawArchive.extractXml(new byte[0], 1024), "empty");
    }

    @Test
    public void stopsInflatingBeyondTheLimit() throws IOException {
        // 8 MiB of zeros compress to a few kilobytes
        final byte[] bomb = TestFixtures.zip("bomb.xml", new byte[8 * 1024 * 1024]);
        assertThrows(IOException.class, () -> LawArchive.extractXml(bomb, 1024 * 1024), "beyond the limit");
        assertArrayEquals(new byte[8 * 1024 * 1024], LawArchive.extractXml(bomb, 8 * 1024 * 1024), "at the limit");
    }
}
