package io.github.dgreco.rya.api.layout;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */



import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants;

/**
 * Created by IntelliJ IDEA.
 * Date: 4/25/12
 * Time: 12:20 PM
 * To change this template use File | Settings | File Templates.
 */
public class TablePrefixLayoutStrategy implements TableLayoutStrategy{
    private String tablePrefix = RdfCloudTripleStoreConstants.TBL_PRFX_DEF;

    public TablePrefixLayoutStrategy() {
    }

    public TablePrefixLayoutStrategy(String tablePrefix) {
        this.tablePrefix = tablePrefix;
    }

    @Override
    public String getSpo() {
        return tablePrefix + RdfCloudTripleStoreConstants.TBL_SPO_SUFFIX;
    }

    @Override
    public String getPo() {
        return tablePrefix + RdfCloudTripleStoreConstants.TBL_PO_SUFFIX;
    }

    @Override
    public String getOsp() {
        return tablePrefix + RdfCloudTripleStoreConstants.TBL_OSP_SUFFIX;
    }

    @Override
    public String getNs() {
        return tablePrefix + RdfCloudTripleStoreConstants.TBL_NS_SUFFIX;
    }

    @Override
    public String getEval() {
        return tablePrefix + RdfCloudTripleStoreConstants.TBL_EVAL_SUFFIX;
    }

    @Override
    public String getGraphs() {
        return tablePrefix + RdfCloudTripleStoreConstants.TBL_GRAPHS_SUFFIX;
    }

    @Override
    public String getText() {
        return tablePrefix + RdfCloudTripleStoreConstants.TBL_TEXT_SUFFIX;
    }

    @Override
    public String getGeo() {
        return tablePrefix + RdfCloudTripleStoreConstants.TBL_GEO_SUFFIX;
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    public void setTablePrefix(String tablePrefix) {
        this.tablePrefix = tablePrefix;
    }
}
