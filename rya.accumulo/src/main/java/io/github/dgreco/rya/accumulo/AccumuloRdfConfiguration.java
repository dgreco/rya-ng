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
package io.github.dgreco.rya.accumulo;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.hadoop.conf.Configuration;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;

import com.google.common.base.Preconditions;

public class AccumuloRdfConfiguration extends RdfCloudTripleStoreConfiguration {

    public static final String CLOUDBASE_INSTANCE = "sc.cloudbase.instancename";
    public static final String CLOUDBASE_ZOOKEEPERS = "sc.cloudbase.zookeepers";
    public static final String CLOUDBASE_USER = "sc.cloudbase.username";
    public static final String CLOUDBASE_PASSWORD = "sc.cloudbase.password";

    public static final String MAXRANGES_SCANNER = "ac.query.maxranges";

    public static final String CONF_FLUSH_EACH_UPDATE = "ac.dao.flush";

    /** When true (the default), every write also updates the cardinality counters in the eval table. */
    public static final String CONF_MAINTAIN_STATS = "ac.stats.maintain";

    public static final String ITERATOR_SETTINGS_SIZE = "ac.iterators.size";
    public static final String ITERATOR_SETTINGS_BASE = "ac.iterators.%d.";
    public static final String ITERATOR_SETTINGS_NAME = ITERATOR_SETTINGS_BASE + "name";
    public static final String ITERATOR_SETTINGS_CLASS = ITERATOR_SETTINGS_BASE + "iteratorClass";
    public static final String ITERATOR_SETTINGS_PRIORITY = ITERATOR_SETTINGS_BASE + "priority";
    public static final String ITERATOR_SETTINGS_OPTIONS_SIZE = ITERATOR_SETTINGS_BASE + "optionsSize";
    public static final String ITERATOR_SETTINGS_OPTIONS_KEY = ITERATOR_SETTINGS_BASE + "option.%d.name";
    public static final String ITERATOR_SETTINGS_OPTIONS_VALUE = ITERATOR_SETTINGS_BASE + "option.%d.value";

    public AccumuloRdfConfiguration() {
        super();
    }

    public AccumuloRdfConfiguration(final Configuration other) {
        super(other);
    }

    @Override
    public AccumuloRdfConfiguration clone() {
        return new AccumuloRdfConfiguration(this);
    }

    /**
     * Sets the Accumulo username from the configuration object that is meant to
     * be used when connecting an Accumulo client to Accumulo.
     *
     */
    public void setAccumuloUser(final String user) {
    	Preconditions.checkNotNull(user);
    	set(CLOUDBASE_USER, user);
    }

    /**
     * Get the Accumulo username from the configuration object that is meant to
     * be used when connecting an Accumulo client to Accumulo.
     *
     * @return The username if one could be found; otherwise {@code null}.
     */
    public String getAccumuloUser(){
    	return get(CLOUDBASE_USER);
    }

    /**
     * Sets the Accumulo password from the configuration object that is meant to
     * be used when connecting an Accumulo client to Accumulo.
     *
     */
    public void setAccumuloPassword(final String password) {
    	Preconditions.checkNotNull(password);
    	set(CLOUDBASE_PASSWORD, password);
    }

    /**
     * Get the Accumulo password from the configuration object that is meant to
     * be used when connecting an Accumulo client to Accumulo.
     *
     * @return The password if one could be found; otherwise an empty string.
     */
    public String getAccumuloPassword() {
    	return get(CLOUDBASE_PASSWORD);
    }

    /**
     * Sets a comma delimited list of the names of the Zookeeper servers from
     * the configuration object that is meant to be used when connecting a
     * Accumulo client to Accumulo.
     *
     */
    public void setAccumuloZookeepers(final String zookeepers) {
    	Preconditions.checkNotNull(zookeepers);
    	set(CLOUDBASE_ZOOKEEPERS, zookeepers);
    }

    /**
     * Get a comma delimited list of the names of the Zookeeper servers from
     * the configuration object that is meant to be used when connecting a
     * Accumulo client to Accumulo.
     *
     * @return The zookeepers list if one could be found; otherwise {@code null}.
     */
    public String getAccumuloZookeepers() {
    	return get(CLOUDBASE_ZOOKEEPERS);
    }

    /**
     * Sets the Accumulo instance name from the configuration object that is
     * meant to be used when connecting an Accumulo client to Accumulo.
     *
     */
    public void setAccumuloInstance(final String instance) {
    	Preconditions.checkNotNull(instance);
    	set(CLOUDBASE_INSTANCE, instance);
    }

    /**
     * Get the Accumulo instance name from the configuration object that is
     * meant to be used when connecting an Accumulo client to Accumulo.
     *
     * @return The instance name if one could be found; otherwise {@code null}.
     */
    public String getAccumuloInstance() {
    	return get(CLOUDBASE_INSTANCE);
    }

    /**
     * @param username - The Accumulo username from the configuration object that is meant to
     *   be used when connecting an Accumulo client to Accumulo.
     */
    public void setUsername(final String username) {
        super.set(CLOUDBASE_USER, username);
    }

    /**
     * Get the Accumulo username from the configuration object that is meant to
     * be used when connecting an Accumulo client to Accumulo.
     *
     * @return The username if one could be found; otherwise {@code null}.
     */
    public String getUsername() {
        return super.get(CLOUDBASE_USER);
    }

    /**
     * @param password - The Accumulo password from the configuration object that is meant to
     * be used when connecting an Accumulo client to Accumulo.
     */
    public void setPassword(final String password) {
        super.set(CLOUDBASE_PASSWORD, password);
    }

    /**
     * Get the Accumulo password from the configuration object that is meant to
     * be used when connecting an Accumulo client to Accumulo.
     *
     * @return The password if one could be found; otherwise an empty string.
     */
    public String getPassword() {
        return super.get(CLOUDBASE_PASSWORD, "");
    }

    /**
     * @param instanceName - The Accumulo instance name from the configuration object that is
     * meant to be used when connecting an Accumulo client to Accumulo.
     */
    public void setInstanceName(final String instanceName) {
        super.set(CLOUDBASE_INSTANCE, instanceName);
    }

    /**
     * Get the Accumulo instance name from the configuration object that is
     * meant to be used when connecting an Accumulo client to Accumulo.
     *
     * @return The instance name if one could be found; otherwise {@code null}.
     */
    public String getInstanceName() {
        return super.get(CLOUDBASE_INSTANCE);
    }

    /**
     * @param zookeepers - A comma delimited list of the names of the Zookeeper servers from
     * the configuration object that is meant to be used when connecting a
     * Accumulo client to Accumulo.
     */
    public void setZookeepers(final String zookeepers) {
        super.set(CLOUDBASE_ZOOKEEPERS, zookeepers);
    }

    /**
     * Get a comma delimited list of the names of the Zookeeper servers from
     * the configuration object that is meant to be used when connecting a
     * Accumulo client to Accumulo.
     *
     * @return The zookeepers list if one could be found; otherwise {@code null}.
     */
    public String getZookeepers() {
        return super.get(CLOUDBASE_ZOOKEEPERS);
    }

    public Authorizations getAuthorizations() {
        final String[] auths = getAuths();
        if (auths == null || auths.length == 0) {
            return Authorizations.EMPTY;
        }
        return new Authorizations(auths);
    }

    public void setMaxRangesForScanner(final Integer max) {
        setInt(MAXRANGES_SCANNER, max);
    }

    public Integer getMaxRangesForScanner() {
        return getInt(MAXRANGES_SCANNER, 2);
    }

    public boolean flushEachUpdate(){
        return getBoolean(CONF_FLUSH_EACH_UPDATE, true);
    }

    public void setFlush(final boolean flush){
        setBoolean(CONF_FLUSH_EACH_UPDATE, flush);
    }

    public void setAdditionalIterators(final IteratorSetting... additionalIterators){
        //TODO do we need to worry about cleaning up
        this.set(ITERATOR_SETTINGS_SIZE, Integer.toString(additionalIterators.length));
        int i = 0;
        for(final IteratorSetting iterator : additionalIterators) {
            this.set(String.format(ITERATOR_SETTINGS_NAME, i), iterator.getName());
            this.set(String.format(ITERATOR_SETTINGS_CLASS, i), iterator.getIteratorClass());
            this.set(String.format(ITERATOR_SETTINGS_PRIORITY, i), Integer.toString(iterator.getPriority()));
            final Map<String, String> options = iterator.getOptions();

            this.set(String.format(ITERATOR_SETTINGS_OPTIONS_SIZE, i), Integer.toString(options.size()));
            final Iterator<Entry<String, String>> it = options.entrySet().iterator();
            int j = 0;
            while(it.hasNext()) {
                final Entry<String, String> item = it.next();
                this.set(String.format(ITERATOR_SETTINGS_OPTIONS_KEY, i, j), item.getKey());
                this.set(String.format(ITERATOR_SETTINGS_OPTIONS_VALUE, i, j), item.getValue());
                j++;
            }
            i++;
        }
    }

    public IteratorSetting[] getAdditionalIterators(){
        final int size = Integer.valueOf(this.get(ITERATOR_SETTINGS_SIZE, "0"));
        if(size == 0) {
            return new IteratorSetting[0];
        }

        final IteratorSetting[] settings = new IteratorSetting[size];
        for(int i = 0; i < size; i++) {
            final String name = this.get(String.format(ITERATOR_SETTINGS_NAME, i));
            final String iteratorClass = this.get(String.format(ITERATOR_SETTINGS_CLASS, i));
            final int priority = Integer.valueOf(this.get(String.format(ITERATOR_SETTINGS_PRIORITY, i)));

            final int optionsSize = Integer.valueOf(this.get(String.format(ITERATOR_SETTINGS_OPTIONS_SIZE, i)));
            final Map<String, String> options = new HashMap<>(optionsSize);
            for(int j = 0; j < optionsSize; j++) {
                final String key = this.get(String.format(ITERATOR_SETTINGS_OPTIONS_KEY, i, j));
                final String value = this.get(String.format(ITERATOR_SETTINGS_OPTIONS_VALUE, i, j));
                options.put(key, value);
            }
            settings[i] = new IteratorSetting(priority, name, iteratorClass, options);
        }

        return settings;
    }

    /**
     * @return true if writes keep the eval-table cardinality counters up to date. Default true.
     */
    public boolean isMaintainStats() {
        return getBoolean(CONF_MAINTAIN_STATS, true);
    }

    public void setMaintainStats(final boolean maintain) {
        setBoolean(CONF_MAINTAIN_STATS, maintain);
    }
}
