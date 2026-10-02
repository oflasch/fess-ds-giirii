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

import java.time.Instant;
import java.util.Optional;

/**
 * One indexable unit of a law: a section norm, or the law itself when it has no section norms.
 *
 * @param doknr the document number of the norm
 * @param label the section label ({@code enbez}), such as {@code § 433}; empty for a law-level unit
 * @param unit the designation of the structural unit the section is numbered within, such as {@code Art 224}, when the
 *            norm states one itself; empty otherwise
 * @param title the section title ({@code titel}); empty when the norm has none
 * @param jurabk the abbreviation of the law the norm states
 * @param content the plain text of the norm, lines separated by line breaks; may be empty
 * @param footnotes the plain text of the footnotes of the norm; may be empty
 * @param path the breadcrumb of the enclosing structural units, outermost first; may be empty
 * @param builddate the build date of the norm, when the XML states a parseable one
 * @param lawLevel true when the unit stands for a law without section norms
 * @author Oliver Flasch
 */
public record Section(String doknr, String label, String unit, String title, String jurabk, String content, String footnotes, String path,
        Optional<Instant> builddate, boolean lawLevel) {
}
