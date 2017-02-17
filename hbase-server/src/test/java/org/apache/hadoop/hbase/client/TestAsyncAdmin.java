/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.client;

import static org.apache.hadoop.hbase.client.AsyncProcess.START_LOG_ERRORS_AFTER_COUNT_KEY;
import static org.apache.hadoop.hbase.TableName.META_TABLE_NAME;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.AsyncMetaTableAccessor;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.TableNotEnabledException;
import org.apache.hadoop.hbase.master.MasterFileSystem;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.testclassification.ClientTests;
import org.apache.hadoop.hbase.testclassification.LargeTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.FSTableDescriptors;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.Pair;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestName;

/**
 * Class to test AsyncAdmin.
 */
@Category({LargeTests.class, ClientTests.class})
public class TestAsyncAdmin {

  private static final Log LOG = LogFactory.getLog(TestAdmin1.class);
  private final static HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private static byte[] FAMILY = Bytes.toBytes("testFamily");
  private static final byte[] FAMILY_0 = Bytes.toBytes("cf0");
  private static final byte[] FAMILY_1 = Bytes.toBytes("cf1");

  private static AsyncConnection ASYNC_CONN;
  private AsyncAdmin admin;

  @Rule
  public TestName name = new TestName();

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    TEST_UTIL.getConfiguration().setInt(HConstants.HBASE_CLIENT_PAUSE, 10);
    TEST_UTIL.getConfiguration().setInt(HConstants.HBASE_CLIENT_RETRIES_NUMBER, 3);
    TEST_UTIL.getConfiguration().setInt(HConstants.HBASE_RPC_TIMEOUT_KEY, 1000);
    TEST_UTIL.getConfiguration().setInt(HConstants.HBASE_CLIENT_OPERATION_TIMEOUT, 3000);
    TEST_UTIL.getConfiguration().setInt(START_LOG_ERRORS_AFTER_COUNT_KEY, 0);
    TEST_UTIL.startMiniCluster(1);
    ASYNC_CONN = ConnectionFactory.createAsyncConnection(TEST_UTIL.getConfiguration()).get();
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    IOUtils.closeQuietly(ASYNC_CONN);
    TEST_UTIL.shutdownMiniCluster();
  }

  @Before
  public void setUp() throws Exception {
    this.admin = ASYNC_CONN.getAdmin();
  }

  @Test
  public void testTableExist() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    boolean exist;
    exist = admin.tableExists(tableName).get();
    assertEquals(false, exist);
    TEST_UTIL.createTable(tableName, FAMILY);
    exist = admin.tableExists(tableName).get();
    assertEquals(true, exist);
    exist = admin.tableExists(TableName.META_TABLE_NAME).get();
    assertEquals(true, exist);
  }

  @Test
  public void testListTables() throws Exception {
    int numTables = admin.listTables().get().length;
    final TableName tableName1 = TableName.valueOf(name.getMethodName() + "1");
    final TableName tableName2 = TableName.valueOf(name.getMethodName() + "2");
    final TableName tableName3 = TableName.valueOf(name.getMethodName() + "3");
    TableName[] tables = new TableName[] { tableName1, tableName2, tableName3 };
    for (int i = 0; i < tables.length; i++) {
      TEST_UTIL.createTable(tables[i], FAMILY);
    }

    HTableDescriptor[] tableDescs = admin.listTables().get();
    int size = tableDescs.length;
    assertTrue(size >= tables.length);
    for (int i = 0; i < tables.length && i < size; i++) {
      boolean found = false;
      for (int j = 0; j < tableDescs.length; j++) {
        if (tableDescs[j].getTableName().equals(tables[i])) {
          found = true;
          break;
        }
      }
      assertTrue("Not found: " + tables[i], found);
    }

    TableName[] tableNames = admin.listTableNames().get();
    size = tableNames.length;
    assertTrue(size == (numTables + tables.length));
    for (int i = 0; i < tables.length && i < size; i++) {
      boolean found = false;
      for (int j = 0; j < tableNames.length; j++) {
        if (tableNames[j].equals(tables[i])) {
          found = true;
          break;
        }
      }
      assertTrue("Not found: " + tables[i], found);
    }

    for (int i = 0; i < tables.length; i++) {
      TEST_UTIL.deleteTable(tables[i]);
    }

    tableDescs = admin.listTables((Pattern) null, true).get();
    assertTrue("Not found system tables", tableDescs.length > 0);
    tableNames = admin.listTableNames((Pattern) null, true).get();
    assertTrue("Not found system tables", tableNames.length > 0);
  }

  @Test(timeout = 300000)
  public void testGetTableDescriptor() throws Exception {
    HColumnDescriptor fam1 = new HColumnDescriptor("fam1");
    HColumnDescriptor fam2 = new HColumnDescriptor("fam2");
    HColumnDescriptor fam3 = new HColumnDescriptor("fam3");
    HTableDescriptor htd = new HTableDescriptor(TableName.valueOf(name.getMethodName()));
    htd.addFamily(fam1);
    htd.addFamily(fam2);
    htd.addFamily(fam3);
    admin.createTable(htd).join();
    HTableDescriptor confirmedHtd = admin.getTableDescriptor(htd.getTableName()).get();
    assertEquals(htd.compareTo(confirmedHtd), 0);
  }

  @Test(timeout = 300000)
  public void testCreateTable() throws Exception {
    HTableDescriptor[] tables = admin.listTables().get();
    int numTables = tables.length;
    final  TableName tableName = TableName.valueOf(name.getMethodName());
    admin.createTable(new HTableDescriptor(tableName).addFamily(new HColumnDescriptor(FAMILY)))
        .join();
    tables = admin.listTables().get();
    assertEquals(numTables + 1, tables.length);
    assertTrue("Table must be enabled.", TEST_UTIL.getHBaseCluster().getMaster()
        .getTableStateManager().isTableState(tableName, TableState.State.ENABLED));
    assertEquals(TableState.State.ENABLED, getStateFromMeta(tableName));
  }

  private TableState.State getStateFromMeta(TableName table) throws Exception {
    Optional<TableState> state = AsyncMetaTableAccessor.getTableState(
      ASYNC_CONN.getRawTable(META_TABLE_NAME), table).get();
    assertTrue(state.isPresent());
    return state.get().getState();
  }

  @Test(timeout = 300000)
  public void testCreateTableNumberOfRegions() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    HTableDescriptor desc = new HTableDescriptor(tableName);
    desc.addFamily(new HColumnDescriptor(HConstants.CATALOG_FAMILY));
    admin.createTable(desc).join();
    List<HRegionLocation> regions;
    try (RegionLocator l = TEST_UTIL.getConnection().getRegionLocator(tableName)) {
      regions = l.getAllRegionLocations();
      assertEquals("Table should have only 1 region", 1, regions.size());
    }

    final TableName tableName2 = TableName.valueOf(tableName.getNameAsString() + "_2");
    desc = new HTableDescriptor(tableName2);
    desc.addFamily(new HColumnDescriptor(HConstants.CATALOG_FAMILY));
    admin.createTable(desc, new byte[][] { new byte[] { 42 } }).join();
    try (RegionLocator l = TEST_UTIL.getConnection().getRegionLocator(tableName2)) {
      regions = l.getAllRegionLocations();
      assertEquals("Table should have only 2 region", 2, regions.size());
    }

    final TableName tableName3 = TableName.valueOf(tableName.getNameAsString() + "_3");
    desc = new HTableDescriptor(tableName3);
    desc.addFamily(new HColumnDescriptor(HConstants.CATALOG_FAMILY));
    admin.createTable(desc, "a".getBytes(), "z".getBytes(), 3).join();
    try (RegionLocator l = TEST_UTIL.getConnection().getRegionLocator(tableName3)) {
      regions = l.getAllRegionLocations();
      assertEquals("Table should have only 3 region", 3, regions.size());
    }

    final TableName tableName4 = TableName.valueOf(tableName.getNameAsString() + "_4");
    desc = new HTableDescriptor(tableName4);
    desc.addFamily(new HColumnDescriptor(HConstants.CATALOG_FAMILY));
    try {
      admin.createTable(desc, "a".getBytes(), "z".getBytes(), 2).join();
      fail("Should not be able to create a table with only 2 regions using this API.");
    } catch (CompletionException e) {
      assertTrue(e.getCause() instanceof IllegalArgumentException);
    }

    final TableName tableName5 = TableName.valueOf(tableName.getNameAsString() + "_5");
    desc = new HTableDescriptor(tableName5);
    desc.addFamily(new HColumnDescriptor(HConstants.CATALOG_FAMILY));
    admin.createTable(desc, new byte[] { 1 }, new byte[] { 127 }, 16).join();
    try (RegionLocator l = TEST_UTIL.getConnection().getRegionLocator(tableName5)) {
      regions = l.getAllRegionLocations();
      assertEquals("Table should have 16 region", 16, regions.size());
    }
  }

  @Test(timeout = 300000)
  public void testCreateTableWithRegions() throws IOException, InterruptedException {
    final TableName tableName = TableName.valueOf(name.getMethodName());

    byte[][] splitKeys = { new byte[] { 1, 1, 1 }, new byte[] { 2, 2, 2 }, new byte[] { 3, 3, 3 },
        new byte[] { 4, 4, 4 }, new byte[] { 5, 5, 5 }, new byte[] { 6, 6, 6 },
        new byte[] { 7, 7, 7 }, new byte[] { 8, 8, 8 }, new byte[] { 9, 9, 9 }, };
    int expectedRegions = splitKeys.length + 1;

    HTableDescriptor desc = new HTableDescriptor(tableName);
    desc.addFamily(new HColumnDescriptor(HConstants.CATALOG_FAMILY));
    admin.createTable(desc, splitKeys).join();

    List<HRegionLocation> regions;
    Iterator<HRegionLocation> hris;
    HRegionInfo hri;
    ClusterConnection conn = (ClusterConnection) TEST_UTIL.getConnection();
    try (RegionLocator l = TEST_UTIL.getConnection().getRegionLocator(tableName)) {
      regions = l.getAllRegionLocations();

      assertEquals(
        "Tried to create " + expectedRegions + " regions " + "but only found " + regions.size(),
        expectedRegions, regions.size());
      System.err.println("Found " + regions.size() + " regions");

      hris = regions.iterator();
      hri = hris.next().getRegionInfo();
      assertTrue(hri.getStartKey() == null || hri.getStartKey().length == 0);
      assertTrue(Bytes.equals(hri.getEndKey(), splitKeys[0]));
      hri = hris.next().getRegionInfo();
      assertTrue(Bytes.equals(hri.getStartKey(), splitKeys[0]));
      assertTrue(Bytes.equals(hri.getEndKey(), splitKeys[1]));
      hri = hris.next().getRegionInfo();
      assertTrue(Bytes.equals(hri.getStartKey(), splitKeys[1]));
      assertTrue(Bytes.equals(hri.getEndKey(), splitKeys[2]));
      hri = hris.next().getRegionInfo();
      assertTrue(Bytes.equals(hri.getStartKey(), splitKeys[2]));
      assertTrue(Bytes.equals(hri.getEndKey(), splitKeys[3]));
      hri = hris.next().getRegionInfo();
      assertTrue(Bytes.equals(hri.getStartKey(), splitKeys[3]));
      assertTrue(Bytes.equals(hri.getEndKey(), splitKeys[4]));
      hri = hris.next().getRegionInfo();
      assertTrue(Bytes.equals(hri.getStartKey(), splitKeys[4]));
      assertTrue(Bytes.equals(hri.getEndKey(), splitKeys[5]));
      hri = hris.next().getRegionInfo();
      assertTrue(Bytes.equals(hri.getStartKey(), splitKeys[5]));
      assertTrue(Bytes.equals(hri.getEndKey(), splitKeys[6]));
      hri = hris.next().getRegionInfo();
      assertTrue(Bytes.equals(hri.getStartKey(), splitKeys[6]));
      assertTrue(Bytes.equals(hri.getEndKey(), splitKeys[7]));
      hri = hris.next().getRegionInfo();
      assertTrue(Bytes.equals(hri.getStartKey(), splitKeys[7]));
      assertTrue(Bytes.equals(hri.getEndKey(), splitKeys[8]));
      hri = hris.next().getRegionInfo();
      assertTrue(Bytes.equals(hri.getStartKey(), splitKeys[8]));
      assertTrue(hri.getEndKey() == null || hri.getEndKey().length == 0);

      verifyRoundRobinDistribution(conn, l, expectedRegions);
    }

    // Now test using start/end with a number of regions

    // Use 80 bit numbers to make sure we aren't limited
    byte[] startKey = { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 };
    byte[] endKey = { 9, 9, 9, 9, 9, 9, 9, 9, 9, 9 };

    // Splitting into 10 regions, we expect (null,1) ... (9, null)
    // with (1,2) (2,3) (3,4) (4,5) (5,6) (6,7) (7,8) (8,9) in the middle

    expectedRegions = 10;

    final TableName tableName2 = TableName.valueOf(tableName.getNameAsString() + "_2");

    desc = new HTableDescriptor(tableName2);
    desc.addFamily(new HColumnDescriptor(HConstants.CATALOG_FAMILY));
    admin.createTable(desc, startKey, endKey, expectedRegions).join();

    try (RegionLocator l = TEST_UTIL.getConnection().getRegionLocator(tableName2)) {
      regions = l.getAllRegionLocations();
      assertEquals(
        "Tried to create " + expectedRegions + " regions " + "but only found " + regions.size(),
        expectedRegions, regions.size());
      System.err.println("Found " + regions.size() + " regions");

      hris = regions.iterator();
      hri = hris.next().getRegionInfo();
      assertTrue(hri.getStartKey() == null || hri.getStartKey().length == 0);
      assertTrue(Bytes.equals(hri.getEndKey(), new byte[] { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 }));
      hri = hris.next().getRegionInfo();
      assertTrue(Bytes.equals(hri.getStartKey(), new byte[] { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 }));
      assertTrue(Bytes.equals(hri.getEndKey(), new byte[] { 2, 2, 2, 2, 2, 2, 2, 2, 2, 2 }));
      hri = hris.next().getRegionInfo();
      assertTrue(Bytes.equals(hri.getStartKey(), new byte[] { 2, 2, 2, 2, 2, 2, 2, 2, 2, 2 }));
      assertTrue(Bytes.equals(hri.getEndKey(), new byte[] { 3, 3, 3, 3, 3, 3, 3, 3, 3, 3 }));
      hri = hris.next().getRegionInfo();
      assertTrue(Bytes.equals(hri.getStartKey(), new byte[] { 3, 3, 3, 3, 3, 3, 3, 3, 3, 3 }));
      assertTrue(Bytes.equals(hri.getEndKey(), new byte[] { 4, 4, 4, 4, 4, 4, 4, 4, 4, 4 }));
      hri = hris.next().getRegionInfo();
      assertTrue(Bytes.equals(hri.getStartKey(), new byte[] { 4, 4, 4, 4, 4, 4, 4, 4, 4, 4 }));
      assertTrue(Bytes.equals(hri.getEndKey(), new byte[] { 5, 5, 5, 5, 5, 5, 5, 5, 5, 5 }));
      hri = hris.next().getRegionInfo();
      assertTrue(Bytes.equals(hri.getStartKey(), new byte[] { 5, 5, 5, 5, 5, 5, 5, 5, 5, 5 }));
      assertTrue(Bytes.equals(hri.getEndKey(), new byte[] { 6, 6, 6, 6, 6, 6, 6, 6, 6, 6 }));
      hri = hris.next().getRegionInfo();
      assertTrue(Bytes.equals(hri.getStartKey(), new byte[] { 6, 6, 6, 6, 6, 6, 6, 6, 6, 6 }));
      assertTrue(Bytes.equals(hri.getEndKey(), new byte[] { 7, 7, 7, 7, 7, 7, 7, 7, 7, 7 }));
      hri = hris.next().getRegionInfo();
      assertTrue(Bytes.equals(hri.getStartKey(), new byte[] { 7, 7, 7, 7, 7, 7, 7, 7, 7, 7 }));
      assertTrue(Bytes.equals(hri.getEndKey(), new byte[] { 8, 8, 8, 8, 8, 8, 8, 8, 8, 8 }));
      hri = hris.next().getRegionInfo();
      assertTrue(Bytes.equals(hri.getStartKey(), new byte[] { 8, 8, 8, 8, 8, 8, 8, 8, 8, 8 }));
      assertTrue(Bytes.equals(hri.getEndKey(), new byte[] { 9, 9, 9, 9, 9, 9, 9, 9, 9, 9 }));
      hri = hris.next().getRegionInfo();
      assertTrue(Bytes.equals(hri.getStartKey(), new byte[] { 9, 9, 9, 9, 9, 9, 9, 9, 9, 9 }));
      assertTrue(hri.getEndKey() == null || hri.getEndKey().length == 0);

      verifyRoundRobinDistribution(conn, l, expectedRegions);
    }

    // Try once more with something that divides into something infinite

    startKey = new byte[] { 0, 0, 0, 0, 0, 0 };
    endKey = new byte[] { 1, 0, 0, 0, 0, 0 };

    expectedRegions = 5;

    final TableName tableName3 = TableName.valueOf(tableName.getNameAsString() + "_3");

    desc = new HTableDescriptor(tableName3);
    desc.addFamily(new HColumnDescriptor(HConstants.CATALOG_FAMILY));
    admin.createTable(desc, startKey, endKey, expectedRegions).join();

    try (RegionLocator l = TEST_UTIL.getConnection().getRegionLocator(tableName3)) {
      regions = l.getAllRegionLocations();
      assertEquals(
        "Tried to create " + expectedRegions + " regions " + "but only found " + regions.size(),
        expectedRegions, regions.size());
      System.err.println("Found " + regions.size() + " regions");

      verifyRoundRobinDistribution(conn, l, expectedRegions);
    }

    // Try an invalid case where there are duplicate split keys
    splitKeys = new byte[][] { new byte[] { 1, 1, 1 }, new byte[] { 2, 2, 2 },
        new byte[] { 3, 3, 3 }, new byte[] { 2, 2, 2 } };

    final TableName tableName4 = TableName.valueOf(tableName.getNameAsString() + "_4");
    desc = new HTableDescriptor(tableName4);
    desc.addFamily(new HColumnDescriptor(HConstants.CATALOG_FAMILY));
    try {
      admin.createTable(desc, splitKeys).join();
      fail("Should not be able to create this table because of " + "duplicate split keys");
    } catch (CompletionException e) {
      assertTrue(e.getCause() instanceof IllegalArgumentException);
    }
  }

  private void verifyRoundRobinDistribution(ClusterConnection c, RegionLocator regionLocator,
      int expectedRegions) throws IOException {
    int numRS = c.getCurrentNrHRS();
    List<HRegionLocation> regions = regionLocator.getAllRegionLocations();
    Map<ServerName, List<HRegionInfo>> server2Regions = new HashMap<>();
    regions.stream().forEach((loc) -> {
      ServerName server = loc.getServerName();
      server2Regions.computeIfAbsent(server, (s) -> new ArrayList<>()).add(loc.getRegionInfo());
    });
    if (numRS >= 2) {
      // Ignore the master region server,
      // which contains less regions by intention.
      numRS--;
    }
    float average = (float) expectedRegions / numRS;
    int min = (int) Math.floor(average);
    int max = (int) Math.ceil(average);
    server2Regions.values().forEach((regionList) -> {
      assertTrue(regionList.size() == min || regionList.size() == max);
    });
  }

  @Test(timeout = 300000)
  public void testCreateTableWithOnlyEmptyStartRow() throws IOException {
    byte[] tableName = Bytes.toBytes(name.getMethodName());
    byte[][] splitKeys = new byte[1][];
    splitKeys[0] = HConstants.EMPTY_BYTE_ARRAY;
    HTableDescriptor desc = new HTableDescriptor(TableName.valueOf(tableName));
    desc.addFamily(new HColumnDescriptor("col"));
    try {
      admin.createTable(desc, splitKeys).join();
      fail("Test case should fail as empty split key is passed.");
    } catch (CompletionException e) {
      assertTrue(e.getCause() instanceof IllegalArgumentException);
    }
  }

  @Test(timeout = 300000)
  public void testCreateTableWithEmptyRowInTheSplitKeys() throws IOException {
    byte[] tableName = Bytes.toBytes(name.getMethodName());
    byte[][] splitKeys = new byte[3][];
    splitKeys[0] = "region1".getBytes();
    splitKeys[1] = HConstants.EMPTY_BYTE_ARRAY;
    splitKeys[2] = "region2".getBytes();
    HTableDescriptor desc = new HTableDescriptor(TableName.valueOf(tableName));
    desc.addFamily(new HColumnDescriptor("col"));
    try {
      admin.createTable(desc, splitKeys).join();
      fail("Test case should fail as empty split key is passed.");
    } catch (CompletionException e) {
      assertTrue(e.getCause() instanceof IllegalArgumentException);
    }
  }

  @Test(timeout = 300000)
  public void testDeleteTable() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    admin.createTable(new HTableDescriptor(tableName).addFamily(new HColumnDescriptor(FAMILY))).join();
    assertTrue(admin.tableExists(tableName).get());
    TEST_UTIL.getAdmin().disableTable(tableName);
    admin.deleteTable(tableName).join();
    assertFalse(admin.tableExists(tableName).get());
  }

  @Test(timeout = 300000)
  public void testDeleteTables() throws Exception {
    TableName[] tables = { TableName.valueOf(name.getMethodName() + "1"),
        TableName.valueOf(name.getMethodName() + "2"), TableName.valueOf(name.getMethodName() + "3") };
    Arrays.stream(tables).map(HTableDescriptor::new)
        .map((table) -> table.addFamily(new HColumnDescriptor(FAMILY))).forEach((table) -> {
          admin.createTable(table).join();
          admin.tableExists(table.getTableName()).thenAccept((exist) -> assertTrue(exist)).join();
          try {
            TEST_UTIL.getAdmin().disableTable(table.getTableName());
          } catch (Exception e) {
          }
        });
    HTableDescriptor[] failed = admin.deleteTables(Pattern.compile("testDeleteTables.*")).get();
    assertEquals(0, failed.length);
    Arrays.stream(tables).forEach((table) -> {
      admin.tableExists(table).thenAccept((exist) -> assertFalse(exist)).join();
    });
  }

  @Test(timeout = 300000)
  public void testTruncateTable() throws IOException {
    testTruncateTable(TableName.valueOf(name.getMethodName()), false);
  }

  @Test(timeout = 300000)
  public void testTruncateTablePreservingSplits() throws IOException {
    testTruncateTable(TableName.valueOf(name.getMethodName()), true);
  }

  private void testTruncateTable(final TableName tableName, boolean preserveSplits)
      throws IOException {
    byte[][] splitKeys = new byte[2][];
    splitKeys[0] = Bytes.toBytes(4);
    splitKeys[1] = Bytes.toBytes(8);

    // Create & Fill the table
    Table table = TEST_UTIL.createTable(tableName, HConstants.CATALOG_FAMILY, splitKeys);
    try {
      TEST_UTIL.loadNumericRows(table, HConstants.CATALOG_FAMILY, 0, 10);
      assertEquals(10, TEST_UTIL.countRows(table));
    } finally {
      table.close();
    }
    assertEquals(3, TEST_UTIL.getHBaseCluster().getRegions(tableName).size());

    // Truncate & Verify
    TEST_UTIL.getAdmin().disableTable(tableName);
    admin.truncateTable(tableName, preserveSplits).join();
    table = TEST_UTIL.getConnection().getTable(tableName);
    try {
      assertEquals(0, TEST_UTIL.countRows(table));
    } finally {
      table.close();
    }
    if (preserveSplits) {
      assertEquals(3, TEST_UTIL.getHBaseCluster().getRegions(tableName).size());
    } else {
      assertEquals(1, TEST_UTIL.getHBaseCluster().getRegions(tableName).size());
    }
  }

  @Test(timeout = 300000)
  public void testDisableAndEnableTable() throws Exception {
    final byte[] row = Bytes.toBytes("row");
    final byte[] qualifier = Bytes.toBytes("qualifier");
    final byte[] value = Bytes.toBytes("value");
    final TableName tableName = TableName.valueOf(name.getMethodName());
    Table ht = TEST_UTIL.createTable(tableName, HConstants.CATALOG_FAMILY);
    Put put = new Put(row);
    put.addColumn(HConstants.CATALOG_FAMILY, qualifier, value);
    ht.put(put);
    Get get = new Get(row);
    get.addColumn(HConstants.CATALOG_FAMILY, qualifier);
    ht.get(get);

    this.admin.disableTable(ht.getName()).join();
    assertTrue("Table must be disabled.", TEST_UTIL.getHBaseCluster().getMaster()
        .getTableStateManager().isTableState(ht.getName(), TableState.State.DISABLED));
    assertEquals(TableState.State.DISABLED, getStateFromMeta(tableName));

    // Test that table is disabled
    get = new Get(row);
    get.addColumn(HConstants.CATALOG_FAMILY, qualifier);
    boolean ok = false;
    try {
      ht.get(get);
    } catch (TableNotEnabledException e) {
      ok = true;
    }
    ok = false;
    // verify that scan encounters correct exception
    Scan scan = new Scan();
    try {
      ResultScanner scanner = ht.getScanner(scan);
      Result res = null;
      do {
        res = scanner.next();
      } while (res != null);
    } catch (TableNotEnabledException e) {
      ok = true;
    }
    assertTrue(ok);
    this.admin.enableTable(tableName).join();
    assertTrue("Table must be enabled.", TEST_UTIL.getHBaseCluster().getMaster()
        .getTableStateManager().isTableState(ht.getName(), TableState.State.ENABLED));
    assertEquals(TableState.State.ENABLED, getStateFromMeta(tableName));

    // Test that table is enabled
    try {
      ht.get(get);
    } catch (RetriesExhaustedException e) {
      ok = false;
    }
    assertTrue(ok);
    ht.close();
  }

  @Test(timeout = 300000)
  public void testDisableAndEnableTables() throws Exception {
    final byte[] row = Bytes.toBytes("row");
    final byte[] qualifier = Bytes.toBytes("qualifier");
    final byte[] value = Bytes.toBytes("value");
    final TableName tableName1 = TableName.valueOf(name.getMethodName() + "1");
    final TableName tableName2 = TableName.valueOf(name.getMethodName());
    Table ht1 = TEST_UTIL.createTable(tableName1, HConstants.CATALOG_FAMILY);
    Table ht2 = TEST_UTIL.createTable(tableName2, HConstants.CATALOG_FAMILY);
    Put put = new Put(row);
    put.addColumn(HConstants.CATALOG_FAMILY, qualifier, value);
    ht1.put(put);
    ht2.put(put);
    Get get = new Get(row);
    get.addColumn(HConstants.CATALOG_FAMILY, qualifier);
    ht1.get(get);
    ht2.get(get);

    this.admin.disableTables("testDisableAndEnableTable.*").join();

    // Test that tables are disabled
    get = new Get(row);
    get.addColumn(HConstants.CATALOG_FAMILY, qualifier);
    boolean ok = false;
    try {
      ht1.get(get);
      ht2.get(get);
    } catch (org.apache.hadoop.hbase.DoNotRetryIOException e) {
      ok = true;
    }

    assertEquals(TableState.State.DISABLED, getStateFromMeta(tableName1));
    assertEquals(TableState.State.DISABLED, getStateFromMeta(tableName2));

    assertTrue(ok);
    this.admin.enableTables("testDisableAndEnableTable.*").join();

    // Test that tables are enabled
    try {
      ht1.get(get);
    } catch (IOException e) {
      ok = false;
    }
    try {
      ht2.get(get);
    } catch (IOException e) {
      ok = false;
    }
    assertTrue(ok);

    ht1.close();
    ht2.close();

    assertEquals(TableState.State.ENABLED, getStateFromMeta(tableName1));
    assertEquals(TableState.State.ENABLED, getStateFromMeta(tableName2));
  }

  @Test(timeout = 300000)
  public void testEnableTableRetainAssignment() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    byte[][] splitKeys = { new byte[] { 1, 1, 1 }, new byte[] { 2, 2, 2 }, new byte[] { 3, 3, 3 },
        new byte[] { 4, 4, 4 }, new byte[] { 5, 5, 5 }, new byte[] { 6, 6, 6 },
        new byte[] { 7, 7, 7 }, new byte[] { 8, 8, 8 }, new byte[] { 9, 9, 9 } };
    int expectedRegions = splitKeys.length + 1;
    HTableDescriptor desc = new HTableDescriptor(tableName);
    desc.addFamily(new HColumnDescriptor(HConstants.CATALOG_FAMILY));
    admin.createTable(desc, splitKeys).join();

    try (RegionLocator l = TEST_UTIL.getConnection().getRegionLocator(tableName)) {
      List<HRegionLocation> regions = l.getAllRegionLocations();

      assertEquals(
        "Tried to create " + expectedRegions + " regions " + "but only found " + regions.size(),
        expectedRegions, regions.size());
      // Disable table.
      admin.disableTable(tableName).join();
      // Enable table, use retain assignment to assign regions.
      admin.enableTable(tableName).join();
      List<HRegionLocation> regions2 = l.getAllRegionLocations();

      // Check the assignment.
      assertEquals(regions.size(), regions2.size());
      assertTrue(regions2.containsAll(regions));
    }
  }

  @Test(timeout = 300000)
  public void testDisableCatalogTable() throws Exception {
    try {
      this.admin.disableTable(TableName.META_TABLE_NAME).join();
      fail("Expected to throw ConstraintException");
    } catch (Exception e) {
    }
    // Before the fix for HBASE-6146, the below table creation was failing as the hbase:meta table
    // actually getting disabled by the disableTable() call.
    HTableDescriptor htd = new HTableDescriptor(TableName.valueOf(name.getMethodName().getBytes()));
    HColumnDescriptor hcd = new HColumnDescriptor("cf1".getBytes());
    htd.addFamily(hcd);
    admin.createTable(htd).join();
  }

  @Test
  public void testAddColumnFamily() throws IOException {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    // Create a table with two families
    HTableDescriptor baseHtd = new HTableDescriptor(tableName);
    baseHtd.addFamily(new HColumnDescriptor(FAMILY_0));
    admin.createTable(baseHtd).join();
    admin.disableTable(tableName).join();
    try {
      // Verify the table descriptor
      verifyTableDescriptor(tableName, FAMILY_0);

      // Modify the table removing one family and verify the descriptor
      admin.addColumnFamily(tableName, new HColumnDescriptor(FAMILY_1)).join();
      verifyTableDescriptor(tableName, FAMILY_0, FAMILY_1);
    } finally {
      admin.deleteTable(tableName);
    }
  }

  @Test
  public void testAddSameColumnFamilyTwice() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    // Create a table with one families
    HTableDescriptor baseHtd = new HTableDescriptor(tableName);
    baseHtd.addFamily(new HColumnDescriptor(FAMILY_0));
    admin.createTable(baseHtd).join();
    admin.disableTable(tableName).join();
    try {
      // Verify the table descriptor
      verifyTableDescriptor(tableName, FAMILY_0);

      // Modify the table removing one family and verify the descriptor
      this.admin.addColumnFamily(tableName, new HColumnDescriptor(FAMILY_1)).join();
      verifyTableDescriptor(tableName, FAMILY_0, FAMILY_1);

      try {
        // Add same column family again - expect failure
        this.admin.addColumnFamily(tableName, new HColumnDescriptor(FAMILY_1)).join();
        Assert.fail("Delete a non-exist column family should fail");
      } catch (Exception e) {
        // Expected.
      }
    } finally {
      admin.deleteTable(tableName).join();
    }
  }

  @Test
  public void testModifyColumnFamily() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());

    HColumnDescriptor cfDescriptor = new HColumnDescriptor(FAMILY_0);
    int blockSize = cfDescriptor.getBlocksize();
    // Create a table with one families
    HTableDescriptor baseHtd = new HTableDescriptor(tableName);
    baseHtd.addFamily(cfDescriptor);
    admin.createTable(baseHtd).join();
    admin.disableTable(tableName).join();
    try {
      // Verify the table descriptor
      verifyTableDescriptor(tableName, FAMILY_0);

      int newBlockSize = 2 * blockSize;
      cfDescriptor.setBlocksize(newBlockSize);

      // Modify colymn family
      admin.modifyColumnFamily(tableName, cfDescriptor).join();

      HTableDescriptor htd = admin.getTableDescriptor(tableName).get();
      HColumnDescriptor hcfd = htd.getFamily(FAMILY_0);
      assertTrue(hcfd.getBlocksize() == newBlockSize);
    } finally {
      admin.deleteTable(tableName).join();
    }
  }

  @Test
  public void testModifyNonExistingColumnFamily() throws IOException {
    final TableName tableName = TableName.valueOf(name.getMethodName());

    HColumnDescriptor cfDescriptor = new HColumnDescriptor(FAMILY_1);
    int blockSize = cfDescriptor.getBlocksize();
    // Create a table with one families
    HTableDescriptor baseHtd = new HTableDescriptor(tableName);
    baseHtd.addFamily(new HColumnDescriptor(FAMILY_0));
    admin.createTable(baseHtd).join();
    admin.disableTable(tableName).join();
    try {
      // Verify the table descriptor
      verifyTableDescriptor(tableName, FAMILY_0);

      int newBlockSize = 2 * blockSize;
      cfDescriptor.setBlocksize(newBlockSize);

      // Modify a column family that is not in the table.
      try {
        admin.modifyColumnFamily(tableName, cfDescriptor).join();
        Assert.fail("Modify a non-exist column family should fail");
      } catch (Exception e) {
        // Expected.
      }
    } finally {
      admin.deleteTable(tableName).join();
    }
  }

  @Test
  public void testDeleteColumnFamily() throws IOException {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    // Create a table with two families
    HTableDescriptor baseHtd = new HTableDescriptor(tableName);
    baseHtd.addFamily(new HColumnDescriptor(FAMILY_0));
    baseHtd.addFamily(new HColumnDescriptor(FAMILY_1));
    admin.createTable(baseHtd).join();
    admin.disableTable(tableName).join();
    try {
      // Verify the table descriptor
      verifyTableDescriptor(tableName, FAMILY_0, FAMILY_1);

      // Modify the table removing one family and verify the descriptor
      admin.deleteColumnFamily(tableName, FAMILY_1).join();
      verifyTableDescriptor(tableName, FAMILY_0);
    } finally {
      admin.deleteTable(tableName).join();
    }
  }

  @Test
  public void testDeleteSameColumnFamilyTwice() throws IOException {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    // Create a table with two families
    HTableDescriptor baseHtd = new HTableDescriptor(tableName);
    baseHtd.addFamily(new HColumnDescriptor(FAMILY_0));
    baseHtd.addFamily(new HColumnDescriptor(FAMILY_1));
    admin.createTable(baseHtd).join();
    admin.disableTable(tableName).join();
    try {
      // Verify the table descriptor
      verifyTableDescriptor(tableName, FAMILY_0, FAMILY_1);

      // Modify the table removing one family and verify the descriptor
      admin.deleteColumnFamily(tableName, FAMILY_1).join();
      verifyTableDescriptor(tableName, FAMILY_0);

      try {
        // Delete again - expect failure
        admin.deleteColumnFamily(tableName, FAMILY_1).join();
        Assert.fail("Delete a non-exist column family should fail");
      } catch (Exception e) {
        // Expected.
      }
    } finally {
      admin.deleteTable(tableName).join();
    }
  }

  private void verifyTableDescriptor(final TableName tableName, final byte[]... families)
      throws IOException {
    Admin admin = TEST_UTIL.getAdmin();

    // Verify descriptor from master
    HTableDescriptor htd = admin.getTableDescriptor(tableName);
    verifyTableDescriptor(htd, tableName, families);

    // Verify descriptor from HDFS
    MasterFileSystem mfs = TEST_UTIL.getMiniHBaseCluster().getMaster().getMasterFileSystem();
    Path tableDir = FSUtils.getTableDir(mfs.getRootDir(), tableName);
    HTableDescriptor td = FSTableDescriptors
        .getTableDescriptorFromFs(mfs.getFileSystem(), tableDir);
    verifyTableDescriptor(td, tableName, families);
  }

  private void verifyTableDescriptor(final HTableDescriptor htd, final TableName tableName,
      final byte[]... families) {
    Set<byte[]> htdFamilies = htd.getFamiliesKeys();
    assertEquals(tableName, htd.getTableName());
    assertEquals(families.length, htdFamilies.size());
    for (byte[] familyName : families) {
      assertTrue("Expected family " + Bytes.toString(familyName), htdFamilies.contains(familyName));
    }
  }

  @Test(timeout = 30000)
  public void testBalancer() throws Exception {
    boolean initialState = admin.isBalancerEnabled().get();

    // Start the balancer, wait for it.
    boolean prevState = admin.setBalancerRunning(!initialState).get();

    // The previous state should be the original state we observed
    assertEquals(initialState, prevState);

    // Current state should be opposite of the original
    assertEquals(!initialState, admin.isBalancerEnabled().get());

    // Reset it back to what it was
    prevState = admin.setBalancerRunning(initialState).get();

    // The previous state should be the opposite of the initial state
    assertEquals(!initialState, prevState);
    // Current state should be the original state again
    assertEquals(initialState, admin.isBalancerEnabled().get());
  }

  private void createTableWithDefaultConf(TableName TABLENAME) throws Exception {
    HTableDescriptor htd = new HTableDescriptor(TABLENAME);
    HColumnDescriptor hcd = new HColumnDescriptor("value");
    htd.addFamily(hcd);

    admin.createTable(htd, null).get();
  }

  @Test
  public void testCloseRegion() throws Exception {
    TableName TABLENAME = TableName.valueOf("TestHBACloseRegion");
    createTableWithDefaultConf(TABLENAME);

    HRegionInfo info = null;
    HRegionServer rs = TEST_UTIL.getRSForFirstRegionInTable(TABLENAME);
    List<HRegionInfo> onlineRegions = ProtobufUtil.getOnlineRegions(rs.getRSRpcServices());
    for (HRegionInfo regionInfo : onlineRegions) {
      if (!regionInfo.getTable().isSystemTable()) {
        info = regionInfo;
        boolean closed = admin.closeRegionWithEncodedRegionName(regionInfo.getEncodedName(),
          rs.getServerName().getServerName()).get();
        assertTrue(closed);
      }
    }
    boolean isInList = ProtobufUtil.getOnlineRegions(rs.getRSRpcServices()).contains(info);
    long timeout = System.currentTimeMillis() + 10000;
    while ((System.currentTimeMillis() < timeout) && (isInList)) {
      Thread.sleep(100);
      isInList = ProtobufUtil.getOnlineRegions(rs.getRSRpcServices()).contains(info);
    }

    assertFalse("The region should not be present in online regions list.", isInList);
  }

  @Test
  public void testCloseRegionIfInvalidRegionNameIsPassed() throws Exception {
    final String name = "TestHBACloseRegion1";
    byte[] TABLENAME = Bytes.toBytes(name);
    createTableWithDefaultConf(TableName.valueOf(TABLENAME));

    HRegionInfo info = null;
    HRegionServer rs = TEST_UTIL.getRSForFirstRegionInTable(TableName.valueOf(TABLENAME));
    List<HRegionInfo> onlineRegions = ProtobufUtil.getOnlineRegions(rs.getRSRpcServices());
    for (HRegionInfo regionInfo : onlineRegions) {
      if (!regionInfo.isMetaTable()) {
        if (regionInfo.getRegionNameAsString().contains(name)) {
          info = regionInfo;
          boolean catchNotServingException = false;
          try {
            admin.closeRegionWithEncodedRegionName("sample", rs.getServerName().getServerName())
                .get();
          } catch (Exception e) {
            catchNotServingException = true;
            // expected, ignore it
          }
          assertTrue(catchNotServingException);
        }
      }
    }
    onlineRegions = ProtobufUtil.getOnlineRegions(rs.getRSRpcServices());
    assertTrue("The region should be present in online regions list.",
      onlineRegions.contains(info));
  }

  @Test
  public void testCloseRegionThatFetchesTheHRIFromMeta() throws Exception {
    TableName TABLENAME = TableName.valueOf("TestHBACloseRegion2");
    createTableWithDefaultConf(TABLENAME);

    HRegionInfo info = null;
    HRegionServer rs = TEST_UTIL.getRSForFirstRegionInTable(TABLENAME);
    List<HRegionInfo> onlineRegions = ProtobufUtil.getOnlineRegions(rs.getRSRpcServices());
    for (HRegionInfo regionInfo : onlineRegions) {
      if (!regionInfo.isMetaTable()) {

        if (regionInfo.getRegionNameAsString().contains("TestHBACloseRegion2")) {
          info = regionInfo;
          admin.closeRegion(regionInfo.getRegionNameAsString(), rs.getServerName().getServerName())
              .get();
        }
      }
    }

    boolean isInList = ProtobufUtil.getOnlineRegions(rs.getRSRpcServices()).contains(info);
    long timeout = System.currentTimeMillis() + 10000;
    while ((System.currentTimeMillis() < timeout) && (isInList)) {
      Thread.sleep(100);
      isInList = ProtobufUtil.getOnlineRegions(rs.getRSRpcServices()).contains(info);
    }

    assertFalse("The region should not be present in online regions list.", isInList);
  }

  @Test
  public void testCloseRegionWhenServerNameIsNull() throws Exception {
    byte[] TABLENAME = Bytes.toBytes("TestHBACloseRegion3");
    createTableWithDefaultConf(TableName.valueOf(TABLENAME));

    HRegionServer rs = TEST_UTIL.getRSForFirstRegionInTable(TableName.valueOf(TABLENAME));

    try {
      List<HRegionInfo> onlineRegions = ProtobufUtil.getOnlineRegions(rs.getRSRpcServices());
      for (HRegionInfo regionInfo : onlineRegions) {
        if (!regionInfo.isMetaTable()) {
          if (regionInfo.getRegionNameAsString().contains("TestHBACloseRegion3")) {
            admin.closeRegionWithEncodedRegionName(regionInfo.getEncodedName(), null).get();
          }
        }
      }
      fail("The test should throw exception if the servername passed is null.");
    } catch (IllegalArgumentException e) {
    }
  }

  @Test
  public void testCloseRegionWhenServerNameIsEmpty() throws Exception {
    byte[] TABLENAME = Bytes.toBytes("TestHBACloseRegionWhenServerNameIsEmpty");
    createTableWithDefaultConf(TableName.valueOf(TABLENAME));

    HRegionServer rs = TEST_UTIL.getRSForFirstRegionInTable(TableName.valueOf(TABLENAME));

    try {
      List<HRegionInfo> onlineRegions = ProtobufUtil.getOnlineRegions(rs.getRSRpcServices());
      for (HRegionInfo regionInfo : onlineRegions) {
        if (!regionInfo.isMetaTable()) {
          if (regionInfo.getRegionNameAsString()
              .contains("TestHBACloseRegionWhenServerNameIsEmpty")) {
            admin.closeRegionWithEncodedRegionName(regionInfo.getEncodedName(), " ").get();
          }
        }
      }
      fail("The test should throw exception if the servername passed is empty.");
    } catch (IllegalArgumentException e) {
    }
  }

  @Test
  public void testCloseRegionWhenEncodedRegionNameIsNotGiven() throws Exception {
    byte[] TABLENAME = Bytes.toBytes("TestHBACloseRegion4");
    createTableWithDefaultConf(TableName.valueOf(TABLENAME));

    HRegionInfo info = null;
    HRegionServer rs = TEST_UTIL.getRSForFirstRegionInTable(TableName.valueOf(TABLENAME));

    List<HRegionInfo> onlineRegions = ProtobufUtil.getOnlineRegions(rs.getRSRpcServices());
    for (HRegionInfo regionInfo : onlineRegions) {
      if (!regionInfo.isMetaTable()) {
        if (regionInfo.getRegionNameAsString().contains("TestHBACloseRegion4")) {
          info = regionInfo;
          boolean catchNotServingException = false;
          try {
            admin.closeRegionWithEncodedRegionName(regionInfo.getRegionNameAsString(),
              rs.getServerName().getServerName()).get();
          } catch (Exception e) {
            // expected, ignore it.
            catchNotServingException = true;
          }
          assertTrue(catchNotServingException);
        }
      }
    }
    onlineRegions = ProtobufUtil.getOnlineRegions(rs.getRSRpcServices());
    assertTrue("The region should be present in online regions list.",
      onlineRegions.contains(info));
  }

  @Test
  public void testGetRegion() throws Exception {
    AsyncHBaseAdmin rawAdmin = (AsyncHBaseAdmin) admin;

    final TableName tableName = TableName.valueOf("testGetRegion");
    LOG.info("Started " + tableName);
    TEST_UTIL.createMultiRegionTable(tableName, HConstants.CATALOG_FAMILY);

    try (RegionLocator locator = TEST_UTIL.getConnection().getRegionLocator(tableName)) {
      HRegionLocation regionLocation = locator.getRegionLocation(Bytes.toBytes("mmm"));
      HRegionInfo region = regionLocation.getRegionInfo();
      byte[] regionName = region.getRegionName();
      Pair<HRegionInfo, ServerName> pair = rawAdmin.getRegion(regionName).get();
      assertTrue(Bytes.equals(regionName, pair.getFirst().getRegionName()));
      pair = rawAdmin.getRegion(region.getEncodedNameAsBytes()).get();
      assertTrue(Bytes.equals(regionName, pair.getFirst().getRegionName()));
    }
  }
}
