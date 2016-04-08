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

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

import org.apache.hadoop.fs.FileSystem;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.plugin.hadoop.hdfs.HdfsPlugin;

public class SecurityUtils {

    public static <V> V execute(FileSystemFactory ffs, FsCallback<V> callback) throws IOException {
        return execute(ffs.getFileSystem(), callback);
    }

    public static <V> V execute(final FileSystem fs, final FsCallback<V> callback) throws IOException {
        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<V>() {
                @Override
                public V run() throws IOException {
                    return callback.doInHdfs(fs);
                }
            }, HdfsPlugin.hadoopACC());
        } catch (PrivilegedActionException pae) {
            Throwable th = pae.getCause();
            if (th instanceof Error) {
                throw (Error) th;
            }
            if (th instanceof RuntimeException) {
                throw (RuntimeException) th;
            }
            if (th instanceof IOException) {
                throw (IOException) th;
            }
            throw new ElasticsearchException("Privileged block exception", pae);
        }
    }
}
