/*
 * Copyright 2026 David Greco
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
package org.tripatha.hbase;

import org.tripatha.store.spi.BulkImport;

/**
 * Where {@link HBaseStore#bulk()} gets its import from: {@code tripatha.hbase.bulk}, found by
 * {@link java.util.ServiceLoader}. That module needs hbase-server for HBase's file writer and bulk
 * load, which this one leaves out, so only the loader depends on it.
 */
public interface HBaseBulkImportProvider {
    BulkImport create(HBaseStore store);
}
