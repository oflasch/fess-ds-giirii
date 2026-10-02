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

/**
 * One entry of the table of contents: a law that the site publishes as an XML archive.
 *
 * @param slug the path segment that identifies the law on the site, such as {@code bgb}
 * @param title the title of the law as the table of contents states it; empty when absent
 * @author Oliver Flasch
 */
public record LawRef(String slug, String title) {
}
