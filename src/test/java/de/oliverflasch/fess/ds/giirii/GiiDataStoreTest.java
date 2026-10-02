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

import org.codelibs.fess.ds.DataStore;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.util.ComponentUtil;
import org.dbflute.utflute.lastaflute.LastaFluteTestCase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests the registration of {@link GiiDataStore} in the DI container and its guard against the
 * deletion of documents by Fess.
 *
 * <p>
 * The assertions are qualified because the utflute base class declares methods of the same names.
 * </p>
 *
 * @author Oliver Flasch
 */
public class GiiDataStoreTest extends LastaFluteTestCase {

    @Override
    protected String prepareConfigFile() {
        return "test_app.xml";
    }

    @Override
    protected boolean isSuppressTestCaseTransaction() {
        return true;
    }

    @Test
    public void test_isResolvedByItsHandlerName() {
        final DataStore dataStore = ComponentUtil.getDataStoreFactory().getDataStore("GiiDataStore");
        Assertions.assertInstanceOf(GiiDataStore.class, dataStore, "the handler name resolves to the data store");
        Assertions.assertEquals("GiiDataStore", new GiiDataStore().getName(), "the handler name is the simple class name");
    }

    @Test
    public void test_switchesOffTheDeletionByFessEvenWhenTheRunFails() {
        // The operator asks Fess to delete old documents. The run then fails before it starts,
        // because this container has no crawler components. Fess reads the parameter after the
        // run, in a finally block, so the parameter must be "false" at that point.
        final DataStoreParams initParamMap = new DataStoreParams();
        initParamMap.put("delete_old_docs", "true");
        Assertions.assertThrows(RuntimeException.class, () -> new GiiDataStore().store(new DataConfig(), null, initParamMap),
                "the run fails");
        Assertions.assertEquals("false", initParamMap.getAsString("delete_old_docs"), "delete_old_docs after a failed run");
    }
}
