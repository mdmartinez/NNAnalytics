/**
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

package com.paypal.nnanalytics;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

import org.apache.hadoop.hdfs.server.namenode.GSetGenerator;
import org.apache.hadoop.hdfs.server.namenode.INode;
import org.apache.hadoop.hdfs.server.namenode.INodeWithAdditionalFields;
import org.apache.hadoop.util.GSet;
import org.apache.hadoop.util.GSetCollectionWrapper;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestGSetCollectionWrapper {

  private static GSetCollectionWrapper<INode, INodeWithAdditionalFields> wrapper;
  private static GSet<INode, INodeWithAdditionalFields> original;

  @BeforeClass
  public static void setUp() {
    GSetGenerator gSetGenerator = new GSetGenerator();
    gSetGenerator.clear();
    original = gSetGenerator.getGSet((short) 3, 10, 100);
    wrapper = new GSetCollectionWrapper<>(original);
  }

  @AfterClass
  public static void tearDown() {
    wrapper.clear();
  }

  @Test
  public void testIsEmpty() {
    assertThat(wrapper.isEmpty(), is(original.size() == 0));
  }

  @Test
  public void testContains() {
    INodeWithAdditionalFields first = original.iterator().next();
    assertThat(wrapper.contains(first), is(original.contains(first)));
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testUnsupportedToArray() {
    wrapper.toArray();
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testUnsupportedAdd() {
    wrapper.add(null);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testUnsupportedToArray2() {
    wrapper.toArray(null);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testUnsupportedRemove() {
    wrapper.remove(null);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testUnsupportedContainsAll() {
    wrapper.containsAll(null);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testUnsupportedAddAll() {
    wrapper.addAll(null);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testUnsupportedRemoveAll() {
    wrapper.removeAll(null);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testUnsupportedRetainAll() {
    wrapper.retainAll(null);
  }
}
