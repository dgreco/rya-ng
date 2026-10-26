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
package io.github.dgreco.rya.api.domain;

import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XMLSchema;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RyaTypeTest {
    static RyaType a = new RyaType(XMLSchema.STRING, "http://www.example.com/Alice");
    static RyaType b = new RyaType(XMLSchema.STRING, "http://www.example.com/Bob");
    static RyaType c = new RyaType(XMLSchema.STRING, "http://www.example.com/Carol");
    static RyaType aIri = new RyaType(XMLSchema.ANYURI, "http://www.example.com/Alice");
    static RyaType bIri = new RyaType(XMLSchema.ANYURI, "http://www.example.com/Bob");
    static RyaType aLang = new RyaType(RDF.LANGSTRING, "http://www.example.com/Alice", "en");
    static RyaType aDiffLang = new RyaType(RDF.LANGSTRING, "http://www.example.com/Alice", "fr");
    static RyaType bLang = new RyaType(RDF.LANGSTRING, "http://www.example.com/Bob", "en");
    RyaType nullData = new RyaType(XMLSchema.STRING, null);
    RyaType nullType = new RyaType(null, "http://www.example.com/Alice");
    RyaType nullLang = new RyaType(RDF.LANGSTRING, "http://www.example.com/Alice", null);
    RyaType nullBoth = new RyaType(null, null);
    RyaType same = new RyaType(XMLSchema.STRING, "http://www.example.com/Alice");

    @Test
    public void testCompareTo() throws Exception {
        Assertions.assertEquals(0, aIri.compareTo(aIri), "compareTo(self) should return zero.");
        Assertions.assertFalse(aIri.compareTo(b) == 0, "compareTo should return nonzero for different data and type.");
        Assertions.assertFalse(a.compareTo(aIri) == 0, "compareTo should return nonzero for same data and different datatypes.");
        Assertions.assertFalse(bIri.compareTo(aIri) == 0, "compareTo should return nonzero for same datatype and different data.");
        Assertions.assertEquals(0, a.compareTo(same), "compareTo should return zero for different objects with matching data and datatype.");
        Assertions.assertEquals(0, aLang.compareTo(aLang), "compareTo(self) return zero.");
        Assertions.assertFalse(aLang.compareTo(aDiffLang) == 0, "compareTo should return nonzero for different languages.");
        Assertions.assertFalse(aLang.compareTo(bLang) == 0, "compareTo should return nonzero for same datatype and language and different data.");
    }

    @Test
    public void testCompareToNullFields() throws Exception {
        Assertions.assertEquals(1, a.compareTo(nullData), "[has no nulls].compareTo([has null data]) should return 1");
        Assertions.assertEquals(1, a.compareTo(nullType), "[has no nulls].compareTo([has null type]) should return 1 if data is equal");
        Assertions.assertEquals(-1, nullData.compareTo(a), "[has null data].compareTo([has no nulls]) should return -1");
        Assertions.assertEquals(-1, nullType.compareTo(a), "[has null type].compareTo([has no nulls]) should return -1 if data is equal");
        Assertions.assertEquals(1, nullType.compareTo(nullData), "[has null type].compareTo([has null data]) should return 1");
        Assertions.assertEquals(1, aLang.compareTo(nullLang), "[has no nulls].compareTo([has null lang]) should return 1");
    }

    @Test
    public void testCompareToSymmetry() throws Exception {
        int forward = Integer.signum(a.compareTo(b));
        int backward = Integer.signum(b.compareTo(a));
        Assertions.assertEquals(forward, backward * -1, "Comparison of different values with same type should yield opposite signs.");
        forward = Integer.signum(bIri.compareTo(b));
        backward = Integer.signum(b.compareTo(bIri));
        Assertions.assertEquals(forward, backward*-1, "Comparison of same values with different types should yield opposite signs.");
        forward = Integer.signum(aIri.compareTo(b));
        backward = Integer.signum(b.compareTo(aIri));
        Assertions.assertEquals(forward, backward * -1, "Comparison of different values with different types should yield opposite signs.");
    }

    @Test
    public void testCompareToTransitive() throws Exception {
        final int sign = Integer.signum(a.compareTo(b));
        Assertions.assertEquals(sign, Integer.signum(b.compareTo(c)), "compareTo(a,b) and compareTo(b,c) should have the same sign.");
        Assertions.assertEquals(sign, Integer.signum(a.compareTo(c)), "if a > b > c, compareTo(a,c) should be consistent.");
    }

    @Test
    public void testEquals() throws Exception {
        Assertions.assertTrue(a.equals(same), "Same data and datatype should be equal.");
        Assertions.assertFalse(a.equals(aIri), "Same data, different datatype should be unequal.");
        Assertions.assertFalse(a.equals(b), "Same datatype, different data should be unequal.");
    }

    @Test
    public void testEqualsNullFields() throws Exception {
        Assertions.assertFalse(a.equals(null), "equals(null) should return false.");
        Assertions.assertFalse(a.equals(nullType), "Same data, one null datatype should be unequal.");
        Assertions.assertFalse(a.equals(nullData), "Same datatype, one null data should be unequal.");
        Assertions.assertFalse(aLang.equals(nullLang), "Same datatype, data, one null lang should be unequal.");
        final RyaType sameNull = new RyaType(null, null);
        Assertions.assertTrue(sameNull.equals(nullBoth), "Matching null fields should be equal.");
    }

    @Test
    public void testEqualsCompareToConsistency() throws Exception {
        Assertions.assertEquals(a.equals(same), a.compareTo(same) == 0, "equals and compareTo inconsistent for matching values and types.");
        Assertions.assertEquals(a.equals(b), a.compareTo(b) == 0, "equals and compareTo inconsistent for different values with same types.");
        Assertions.assertEquals(a.equals(aIri), a.compareTo(aIri) == 0, "equals and compareTo inconsistent for same values having different types.");
        Assertions.assertEquals(a.equals(bIri), a.compareTo(bIri) == 0, "equals and compareTo inconsistent for different values and different types.");
        Assertions.assertEquals(aLang.equals(bLang), aLang.compareTo(bLang) == 0, "equals and compareTo inconsistent for different lang and same types/data.");
    }

    @Test
    public void testHashCodeEquals() throws Exception {
        Assertions.assertEquals(a.hashCode(), same.hashCode(), "Same data and same type should yield same hash code.");
        Assertions.assertEquals(nullData.hashCode(), new RyaType(XMLSchema.STRING, null).hashCode(), "Same type and both null data should yield same hash code.");
        Assertions.assertEquals(nullType.hashCode(), new RyaType(null, "http://www.example.com/Alice").hashCode(), "Same data and both null type should yield same hash code.");
        Assertions.assertEquals(nullBoth.hashCode(), new RyaType(null, null).hashCode(), "Null type and null data should yield same hash code.");
    }
}
