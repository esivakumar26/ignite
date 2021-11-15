/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.runner.app;

import static org.apache.ignite.internal.testframework.IgniteTestUtils.testNodeName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgnitionManager;
import org.apache.ignite.internal.ItUtils;
import org.apache.ignite.internal.testframework.IgniteTestUtils;
import org.apache.ignite.internal.testframework.WorkDirectory;
import org.apache.ignite.internal.testframework.WorkDirectoryExtension;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.lang.IgniteException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Ignition interface tests.
 */
@ExtendWith(WorkDirectoryExtension.class)
class ItIgnitionTest {
    /** Network ports of the test nodes. */
    private static final int[] PORTS = {3344, 3345, 3346};

    /** Nodes bootstrap configuration. */
    private final Map<String, String> nodesBootstrapCfg = new LinkedHashMap<>();

    /** Collection of started nodes. */
    private final List<Ignite> startedNodes = new ArrayList<>();

    /** Path to the working directory. */
    @WorkDirectory
    private Path workDir;

    /**
     * Before each.
     */
    @BeforeEach
    void setUp(TestInfo testInfo) {
        String node0Name = testNodeName(testInfo, PORTS[0]);
        String node1Name = testNodeName(testInfo, PORTS[1]);
        String node2Name = testNodeName(testInfo, PORTS[2]);

        nodesBootstrapCfg.put(
                node0Name,
                "{\n"
                        + "  node.metastorageNodes: [ \"" + node0Name + "\" ],\n"
                        + "  network: {\n"
                        + "    port: " + PORTS[0] + ",\n"
                        + "    nodeFinder: {\n"
                        + "      netClusterNodes: [ \"localhost:3344\", \"localhost:3345\", \"localhost:3346\" ]\n"
                        + "    }\n"
                        + "  }\n"
                        + "}"
        );

        nodesBootstrapCfg.put(
                node1Name,
                "{\n"
                        + "  node.metastorageNodes: [ \"" + node0Name + "\" ],\n"
                        + "  network: {\n"
                        + "    port: " + PORTS[1] + ",\n"
                        + "    nodeFinder: {\n"
                        + "      netClusterNodes: [ \"localhost:3344\", \"localhost:3345\", \"localhost:3346\" ]\n"
                        + "    }\n"
                        + "  }\n"
                        + "}"
        );

        nodesBootstrapCfg.put(
                node2Name,
                "{\n"
                        + "  node.metastorageNodes: [ \"" + node0Name + "\" ],\n"
                        + "  network: {\n"
                        + "    port: " + PORTS[2] + ",\n"
                        + "    nodeFinder: {\n"
                        + "      netClusterNodes: [ \"localhost:3344\", \"localhost:3345\", \"localhost:3346\" ]\n"
                        + "    }\n"
                        + "  }\n"
                        + "}"
        );
    }

    /**
     * After each.
     */
    @AfterEach
    void tearDown() throws Exception {
        IgniteUtils.closeAll(ItUtils.reverse(startedNodes));
    }

    /**
     * Check that Ignition.start() with bootstrap configuration returns Ignite instance.
     */
    @Test
    void testNodesStartWithBootstrapConfiguration() {
        nodesBootstrapCfg.forEach((nodeName, configStr) ->
                startedNodes.add(IgnitionManager.start(nodeName, configStr, workDir.resolve(nodeName)))
        );

        Assertions.assertEquals(3, startedNodes.size());

        startedNodes.forEach(Assertions::assertNotNull);
    }

    /**
     * Check that Ignition.start() with bootstrap configuration returns Ignite instance.
     */
    @Test
    void testNodeStartWithoutBootstrapConfiguration(TestInfo testInfo) {
        startedNodes.add(IgnitionManager.start(testNodeName(testInfo, 47500), null, workDir));

        Assertions.assertNotNull(startedNodes.get(0));
    }

    /**
     * Tests scenario when we try to start cluster with single node, but without any node, that hosts metastorage.
     */
    @Test
    void testErrorWhenStartSingleNodeClusterWithoutMetastorage() {
        try {
            startedNodes.add(IgnitionManager.start("other-name", "{\n"
                    + "    \"node\": {\n"
                    + "        \"metastorageNodes\": [\n"
                    + "            \"node-0\", \"node-1\", \"node-2\"\n"
                    + "        ]\n"
                    + "    },\n"
                    + "    \"network\": {\n"
                    + "        \"port\": 3344,\n"
                    + "        \"nodeFinder\": {\n"
                    + "          \"netClusterNodes\": [ \"localhost:3344\"] \n"
                    + "        }\n"
                    + "    }\n"
                    + "}", workDir.resolve("other-name")));
        } catch (Throwable th) {
            assertTrue(IgniteTestUtils.hasCause(th,
                    IgniteException.class,
                    "Cannot start meta storage manager because there is no node in the cluster that hosts meta storage."
            ));
        }
    }

    /**
     * Tests scenario when we try to start node that doesn't host metastorage in cluster with node, that hosts metastorage.
     */
    @Test
    void testStartNodeClusterWithoutMetastorage() throws Exception {
        Ignite ig1 = null;

        Ignite ig2 = null;

        try {
            ig1 = IgnitionManager.start("node-0", "{\n"
                    + "    \"node\": {\n"
                    + "        \"metastorageNodes\": [\n"
                    + "            \"node-0\", \"node-1\", \"node-2\"\n"
                    + "        ]\n"
                    + "    },\n"
                    + "    \"network\": {\n"
                    + "      \"port\": 3344,\n"
                    + "      \"nodeFinder\": {\n"
                    + "        \"netClusterNodes\": [ \"localhost:3345\"]\n"
                    + "      }\n"
                    + "    }\n"
                    + "}", workDir.resolve("node-0"));

            ig2 = IgnitionManager.start("other-name", "{\n"
                    + "    \"node\": {\n"
                    + "        \"metastorageNodes\": [\n"
                    + "            \"node-0\", \"node-1\", \"node-2\"\n"
                    + "        ]\n"
                    + "    },\n"
                    + "    \"network\": {\n"
                    + "      \"port\": 3345,\n"
                    + "      \"nodeFinder\": {\n"
                    + "        \"netClusterNodes\": [ \"localhost:3344\"]\n"
                    + "      }\n"
                    + "    }\n"
                    + "}", workDir.resolve("other-name"));

            assertEquals(ig2.name(), "other-name");
        } finally {
            IgniteUtils.closeAll(ig2, ig1);
        }
    }

    /**
     * Tests scenario when we try to start node with invalid configuration.
     */
    @Test
    void testErrorWhenStartNodeWithInvalidConfiguration() {
        try {
            startedNodes.add(IgnitionManager.start("invalid-config-name",
                    "{Invalid-Configuration}",
                    workDir.resolve("invalid-config-name"))
            );

            fail();
        } catch (Throwable t) {
            assertTrue(IgniteTestUtils.hasCause(t,
                    IgniteException.class,
                    "Unable to parse user-specific configuration."
            ));
        }
    }
}
