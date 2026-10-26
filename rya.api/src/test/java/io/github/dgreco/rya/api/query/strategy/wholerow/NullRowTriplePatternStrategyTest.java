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
package io.github.dgreco.rya.api.query.strategy.wholerow;

import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.LAST_BYTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;

import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants;
import io.github.dgreco.rya.api.RdfCloudTripleStoreUtils;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.query.strategy.ByteRange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class NullRowTriplePatternStrategyTest {

  public class MockRdfConfiguration extends RdfCloudTripleStoreConfiguration {

    @Override
    public RdfCloudTripleStoreConfiguration clone() {
      return new MockRdfConfiguration();
    }

  }

  public NullRowTriplePatternStrategyTest() {
  }

  @BeforeAll
  public static void setUpClass() {
  }

  @AfterAll
  public static void tearDownClass() {
  }

  @BeforeEach
  public void setUp() {
  }

  @AfterEach
  public void tearDown() {
  }

  /**
   * Test of getLayout method, of class NullRowTriplePatternStrategy.
   */
  @Test
  public void testGetLayout() {
    NullRowTriplePatternStrategy instance = new NullRowTriplePatternStrategy();
    RdfCloudTripleStoreConstants.TABLE_LAYOUT expResult = RdfCloudTripleStoreConstants.TABLE_LAYOUT.SPO;
    RdfCloudTripleStoreConstants.TABLE_LAYOUT result = instance.getLayout();
    assertEquals(expResult, result);
  }

  /**
   * Test of defineRange method, of class NullRowTriplePatternStrategy.
   * @throws java.lang.Exception
   */
  @Test
  public void testDefineRange() throws Exception {
    RyaIRI subject = null;
    RyaIRI predicate = null;
    RyaType object = null;
    RyaIRI context = null;
    RdfCloudTripleStoreConfiguration conf = new MockRdfConfiguration();
    NullRowTriplePatternStrategy instance = new NullRowTriplePatternStrategy();
    Map.Entry<RdfCloudTripleStoreConstants.TABLE_LAYOUT, ByteRange> expResult = new RdfCloudTripleStoreUtils.CustomEntry<>(RdfCloudTripleStoreConstants.TABLE_LAYOUT.SPO, new ByteRange(new byte[]{}, LAST_BYTES));
    Map.Entry<RdfCloudTripleStoreConstants.TABLE_LAYOUT, ByteRange> result = instance.defineRange(subject, predicate, object, context, conf);
    assertEquals(expResult.getKey(), result.getKey());
    assertTrue(Arrays.equals(expResult.getValue().getStart(), result.getValue().getStart()));
    assertTrue(Arrays.equals(expResult.getValue().getEnd(), result.getValue().getEnd()));
  }

  /**
   * Test of handles method, of class NullRowTriplePatternStrategy.
   */
  @Test
  public void testHandles() {
    RyaIRI subject = null;
    RyaIRI predicate = null;
    RyaType object = null;
    RyaIRI context = null;
    NullRowTriplePatternStrategy instance = new NullRowTriplePatternStrategy();
    assertTrue(instance.handles(subject, predicate, object, context));

    RyaIRI uri = new RyaIRI("urn:test#1234");
    assertFalse(instance.handles(uri, predicate, object, context));
    assertFalse(instance.handles(subject, uri, object, context));
    assertFalse(instance.handles(subject, predicate, uri, context));
  }
  
}
