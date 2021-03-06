/*
 * This file is part of dependency-check-maven.
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
 *
 * Copyright (c) 2014 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.maven;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.owasp.dependencycheck.utils.Settings;

/**
 *
 * @author Jeremy Long
 */
public class BaseTest {

    /**
     * The properties file location.
     */
    public static final String PROPERTIES_FILE = "mojo.properties";

    @BeforeClass
    public static void setUpClass() throws Exception {
        Settings.initialize();
        InputStream mojoProperties = null;
        try {
            mojoProperties = BaseTest.class.getClassLoader().getResourceAsStream(BaseTest.PROPERTIES_FILE);
            Settings.mergeProperties(mojoProperties);
        } finally {
            if (mojoProperties != null) {
                try {
                    mojoProperties.close();
                } catch (IOException ex) {
                    Logger.getLogger(BaseTest.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }

    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        Settings.cleanup(true);
    }
}
