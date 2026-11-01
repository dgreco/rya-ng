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

import java.util.ArrayList;
import java.util.List;

import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.hadoop.conf.Configuration;

import com.google.common.base.Preconditions;

import io.github.dgreco.rya.store.StoreRdfConfiguration;
import io.github.dgreco.rya.store.spi.ServerIterator;

/**
 * Settings of a store on Accumulo: the neutral ones of {@link StoreRdfConfiguration}, plus the
 * Accumulo credentials and scan iterators.
 */
public class AccumuloRdfConfiguration extends StoreRdfConfiguration {

    public static final String CLOUDBASE_INSTANCE = "sc.cloudbase.instancename";
    public static final String CLOUDBASE_ZOOKEEPERS = "sc.cloudbase.zookeepers";
    public static final String CLOUDBASE_USER = "sc.cloudbase.username";
    public static final String CLOUDBASE_PASSWORD = "sc.cloudbase.password";





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

    /** Adds Accumulo scan iterators to every read. */
    public void setAdditionalIterators(final IteratorSetting... additionalIterators) {
        final List<ServerIterator> iterators = new ArrayList<>(additionalIterators.length);
        for (final IteratorSetting it : additionalIterators) {
            iterators.add(new ServerIterator(it.getPriority(), it.getName(), it.getIteratorClass(), it.getOptions()));
        }
        setServerIterators(iterators);
    }

    public IteratorSetting[] getAdditionalIterators() {
        final List<ServerIterator> iterators = getServerIterators();
        final IteratorSetting[] settings = new IteratorSetting[iterators.size()];
        for (int i = 0; i < settings.length; i++) {
            final ServerIterator it = iterators.get(i);
            settings[i] = new IteratorSetting(it.priority(), it.name(), it.className(), it.options());
        }
        return settings;
    }
}
