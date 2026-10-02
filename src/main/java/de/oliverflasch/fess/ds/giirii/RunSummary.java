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

/**
 * The counters of one synchronization run.
 *
 * @param listed the number of laws the table of contents lists
 * @param unchanged the number of laws whose archive was not modified
 * @param reindexed the number of laws that were stored completely
 * @param failed the number of laws that failed
 * @param stored the number of documents passed to the index
 * @param deleted the number of documents deleted from the index
 * @param finished true when the processing loop reached the end of the selected laws
 * @author Oliver Flasch
 */
public record RunSummary(int listed, int unchanged, int reindexed, int failed, long stored, long deleted, boolean finished) {
}
