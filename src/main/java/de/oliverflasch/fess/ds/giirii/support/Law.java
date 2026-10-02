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

import java.util.List;

/**
 * A parsed law: the metadata of its header norm and its indexable units.
 *
 * @param doknr the document number of the law
 * @param jurabk the abbreviation of the law, such as {@code BGB}
 * @param amtabk the official abbreviation; empty when absent
 * @param longTitle the long title ({@code langue}); empty when absent
 * @param shortTitle the short title ({@code kurzue}); empty when absent
 * @param date the promulgation date ({@code ausfertigung-datum}) as the XML states it; empty when absent
 * @param status the comments of the {@code standangabe} elements, in document order
 * @param sections the indexable units in document order; never empty
 * @author Oliver Flasch
 */
public record Law(String doknr, String jurabk, String amtabk, String longTitle, String shortTitle, String date, List<String> status,
        List<Section> sections) {

    /**
     * Creates the law with immutable copies of its lists.
     */
    public Law {
        status = List.copyOf(status);
        sections = List.copyOf(sections);
    }

    /**
     * Returns whether the law consists of a single law-level unit.
     *
     * @return true when the law has no section norms
     */
    public boolean isLawLevelOnly() {
        return sections.size() == 1 && sections.get(0).lawLevel();
    }
}
