/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.repositories.hdfs;

import java.util.Collection;

import org.elasticsearch.action.admin.cluster.repositories.put.PutRepositoryResponse;
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotResponse;
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.repositories.RepositoryException;
import org.elasticsearch.repositories.RepositoryMissingException;
import org.elasticsearch.snapshots.SnapshotState;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.test.ESIntegTestCase.Scope;
import org.elasticsearch.test.store.MockFSDirectoryService;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

@Ignore("Guava classpath madness")
@ClusterScope(scope = Scope.SUITE, numDataNodes = 1, transportClientRatio = 0.0)
public class HdfsSnapshotRestoreTest extends ESIntegTestCase {

    @Override
    public Settings indexSettings() {
        return Settings.builder()
                .put(super.indexSettings())
                .put(MockFSDirectoryService.RANDOM_PREVENT_DOUBLE_WRITE, false)
                .put(MockFSDirectoryService.RANDOM_NO_DELETE_OPEN_FILE, false)
                .build();
    }

    @Override
    protected Settings nodeSettings(int ordinal) {
        Settings.Builder settings = Settings.builder()
                .put(super.nodeSettings(ordinal))
                .put("path.home", createTempDir())
                .put("path.repo", "")
                .put(MockFSDirectoryService.RANDOM_PREVENT_DOUBLE_WRITE, false)
                .put(MockFSDirectoryService.RANDOM_NO_DELETE_OPEN_FILE, false);
        return settings.build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return pluginList(HdfsTestPlugin.class);
    }

    private String path;

    @Before
    public final void wipeBefore() throws Exception {
        wipeRepositories();
        path = "build/data/repo-" + randomInt();
    }

    @After
    public final void wipeAfter() throws Exception {
        wipeRepositories();
    }

    public void testSimpleWorkflow() {
        Client client = client();
        logger.info("-->  creating hdfs repository with path [{}]", path);

        PutRepositoryResponse putRepositoryResponse = client.admin().cluster().preparePutRepository("test-repo")
                .setType("hdfs")
                .setSettings(Settings.settingsBuilder()
                        //.put("uri", "hdfs://127.0.0.1:51227")
                        .put("conf.fs.es-hdfs.impl", "org.elasticsearch.repositories.hdfs.TestingFs")
                        .put("uri", "es-hdfs://./build/")
                        .put("path", path)
                        .put("conf", "additional-cfg.xml, conf-2.xml")
                        .put("chunk_size", randomIntBetween(100, 1000) + "k")
                        .put("compress", randomBoolean())
                        ).get();
        assertThat(putRepositoryResponse.isAcknowledged(), equalTo(true));

        createIndex("test-idx-1", "test-idx-2", "test-idx-3");
        ensureGreen();

        logger.info("--> indexing some data");
        for (int i = 0; i < 100; i++) {
            index("test-idx-1", "doc", Integer.toString(i), "foo", "bar" + i);
            index("test-idx-2", "doc", Integer.toString(i), "foo", "baz" + i);
            index("test-idx-3", "doc", Integer.toString(i), "foo", "baz" + i);
        }
        refresh();
        assertThat(count(client, "test-idx-1"), equalTo(100L));
        assertThat(count(client, "test-idx-2"), equalTo(100L));
        assertThat(count(client, "test-idx-3"), equalTo(100L));

        logger.info("--> snapshot");
        CreateSnapshotResponse createSnapshotResponse = client.admin().cluster().prepareCreateSnapshot("test-repo", "test-snap").setWaitForCompletion(true).setIndices("test-idx-*", "-test-idx-3").get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), equalTo(createSnapshotResponse.getSnapshotInfo().totalShards()));

        assertThat(client.admin().cluster().prepareGetSnapshots("test-repo").setSnapshots("test-snap").get().getSnapshots().get(0).state(), equalTo(SnapshotState.SUCCESS));

        logger.info("--> delete some data");
        for (int i = 0; i < 50; i++) {
            client.prepareDelete("test-idx-1", "doc", Integer.toString(i)).get();
        }
        for (int i = 50; i < 100; i++) {
            client.prepareDelete("test-idx-2", "doc", Integer.toString(i)).get();
        }
        for (int i = 0; i < 100; i += 2) {
            client.prepareDelete("test-idx-3", "doc", Integer.toString(i)).get();
        }
        refresh();
        assertThat(count(client, "test-idx-1"), equalTo(50L));
        assertThat(count(client, "test-idx-2"), equalTo(50L));
        assertThat(count(client, "test-idx-3"), equalTo(50L));

        logger.info("--> close indices");
        client.admin().indices().prepareClose("test-idx-1", "test-idx-2").get();

        logger.info("--> restore all indices from the snapshot");
        RestoreSnapshotResponse restoreSnapshotResponse = client.admin().cluster().prepareRestoreSnapshot("test-repo", "test-snap").setWaitForCompletion(true).execute().actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

        ensureGreen();
        assertThat(count(client, "test-idx-1"), equalTo(100L));
        assertThat(count(client, "test-idx-2"), equalTo(100L));
        assertThat(count(client, "test-idx-3"), equalTo(50L));

        // Test restore after index deletion
        logger.info("--> delete indices");
        wipeIndices("test-idx-1", "test-idx-2");
        logger.info("--> restore one index after deletion");
        restoreSnapshotResponse = client.admin().cluster().prepareRestoreSnapshot("test-repo", "test-snap").setWaitForCompletion(true).setIndices("test-idx-*", "-test-idx-2").execute().actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));
        ensureGreen();
        assertThat(count(client, "test-idx-1"), equalTo(100L));
        ClusterState clusterState = client.admin().cluster().prepareState().get().getState();
        assertThat(clusterState.getMetaData().hasIndex("test-idx-1"), equalTo(true));
        assertThat(clusterState.getMetaData().hasIndex("test-idx-2"), equalTo(false));
    }

    private void wipeIndices(String... indices) {
        cluster().wipeIndices(indices);
    }

    // RepositoryVerificationException.class
    public void testWrongPath() {
        Client client = client();
        logger.info("-->  creating hdfs repository with path [{}]", path);

        try {
            PutRepositoryResponse putRepositoryResponse = client.admin().cluster().preparePutRepository("test-repo")
                    .setType("hdfs")
                    .setSettings(Settings.settingsBuilder()
                            // .put("uri", "hdfs://127.0.0.1:51227/")
                            .put("conf.fs.es-hdfs.impl", "org.elasticsearch.repositories.hdfs.TestingFs")
                            .put("uri", "es-hdfs:///")
                            .put("path", path + "a@b$c#11:22")
                            .put("chunk_size", randomIntBetween(100, 1000) + "k")
                            .put("compress", randomBoolean()))
                            .get();
            assertThat(putRepositoryResponse.isAcknowledged(), equalTo(true));

            createIndex("test-idx-1", "test-idx-2", "test-idx-3");
            ensureGreen();
            fail("Path name is invalid");
        } catch (RepositoryException re) {
            // expected
        }
    }

    /**
     * Deletes repositories, supports wildcard notation.
     */
    public static void wipeRepositories(String... repositories) {
        // if nothing is provided, delete all
        if (repositories.length == 0) {
            repositories = new String[]{"*"};
        }
        for (String repository : repositories) {
            try {
                client().admin().cluster().prepareDeleteRepository(repository).execute().actionGet();
            } catch (RepositoryMissingException ex) {
                // ignore
            }
        }
    }

    private long count(Client client, String index) {
        return client.prepareSearch(index).setSize(0).get().getHits().totalHits();
    }
}