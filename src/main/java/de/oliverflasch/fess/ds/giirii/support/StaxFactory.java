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

import java.io.ByteArrayInputStream;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;

/**
 * Creates the StAX factory that reads all XML from the site.
 * <p>
 * The table of contents and every law declare a {@code DOCTYPE} with an external DTD. The DTDs
 * declare parameter entities only, so the documents are read without loading them.
 * </p>
 *
 * @author Oliver Flasch
 */
final class StaxFactory {

    /**
     * Prevents instantiation.
     */
    private StaxFactory() {
    }

    /**
     * Creates a factory that loads no DTD, expands no external entity and resolves no external
     * resource.
     * <p>
     * The factory is the implementation built into the JDK. {@code XMLInputFactory.newInstance()}
     * returns whichever implementation the classpath of Fess offers, and its security properties
     * differ.
     * </p>
     *
     * @return the hardened factory
     */
    static XMLInputFactory create() {
        final XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> new ByteArrayInputStream(new byte[0]));
        return factory;
    }
}
