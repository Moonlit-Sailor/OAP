/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.oap

import java.io.File

import org.apache.hadoop.fs.Path
import org.scalatest.BeforeAndAfterEach

import org.apache.spark.sql.{AnalysisException, QueryTest, Row, SaveMode}
import org.apache.spark.sql.execution.datasources.oap.index.IndexUtils
import org.apache.spark.sql.execution.datasources.oap.utils.OapUtils
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.util.Utils

class OapCheckIndexSuite extends QueryTest with SharedSQLContext with BeforeAndAfterEach {
  import testImplicits._

  sparkConf.set("spark.memory.offHeap.size", "100m")

  override def beforeEach(): Unit = {
    sqlContext.conf.setConf(SQLConf.OAP_IS_TESTING, true)
    sqlContext.conf.setConf(SQLConf.OAP_ENABLE_TRIE_OVER_BTREE, false)
    val path1 = Utils.createTempDir().getAbsolutePath
    val path2 = Utils.createTempDir().getAbsolutePath

    sql(s"""CREATE TEMPORARY VIEW oap_test_1 (a INT, b STRING)
          | USING parquet
          | OPTIONS (path '$path1')""".stripMargin)
    sql(s"""CREATE TEMPORARY VIEW oap_test_2 (a INT, b STRING)
          | USING oap
          | OPTIONS (path '$path2')""".stripMargin)
    sql(s"""CREATE TABLE oap_partition_table (a int, b int, c STRING)
          | USING parquet
          | PARTITIONED by (b, c)""".stripMargin)
  }

  override def afterEach(): Unit = {
    sqlContext.dropTempTable("oap_test_1")
    sqlContext.dropTempTable("oap_test_2")
    sqlContext.sql("drop table oap_partition_table")
    sqlContext.conf.setConf(SQLConf.OAP_ENABLE_TRIE_OVER_BTREE, true)
  }

  test("check index on empty table") {
    checkAnswer(sql("check oindex on oap_test_2"), Nil)
  }

  test("check existent meta file") {
    val data: Seq[(Int, String)] = (1 to 300).map { i => (i, s"this is test $i") }
    val df = data.toDF("key", "value")
    val path = Utils.createTempDir().toString
    df.write.format("oap").mode(SaveMode.Overwrite).save(path)
    val oapDf = spark.read.format("oap").load(path)
    oapDf.createOrReplaceTempView("t")
    checkAnswer(sql("check oindex on t"), Nil)
  }

  test("check nonexistent meta file") {
    val data: Seq[(Int, String)] = (1 to 300).map { i => (i, s"this is test $i") }
    val df = data.toDF("key", "value")
    val path = Utils.createTempDir().toString
    df.write.format("oap").mode(SaveMode.Overwrite).save(path)
    val oapDf = spark.read.format("oap").load(path)
    oapDf.createOrReplaceTempView("t")
    checkAnswer(sql("check oindex on t"), Nil)
    Utils.deleteRecursively(new File(path, OapFileFormat.OAP_META_FILE))
    checkAnswer(sql("check oindex on t"),
      Row(s"Meta file not found in partition: $path"))
  }

  test("check meta file: Partially missing") {
    val data: Seq[(Int, Int)] = (1 to 10).map { i => (i, i) }
    data.toDF("key", "value").createOrReplaceTempView("t")

    sql(
      """
        |INSERT OVERWRITE TABLE oap_partition_table
        |partition (b=1, c='c1')
        |SELECT key from t where value < 4
      """.stripMargin)

    sql(
      """
        |INSERT INTO TABLE oap_partition_table
        |partition (b=2, c='c2')
        |SELECT key from t where value == 4
      """.stripMargin)
    sql("create oindex idx1 on oap_partition_table(a)")

    checkAnswer(sql("check oindex on oap_partition_table"), Nil)

    val partitionPath =
      new Path(spark.sqlContext.conf.warehousePath + "/oap_partition_table/b=2/c=c2")
    Utils.deleteRecursively(new File(partitionPath.toUri.getPath, OapFileFormat.OAP_META_FILE))

    checkAnswer(sql("check oindex on oap_partition_table"),
      Row(s"Meta file not found in partition: ${partitionPath.toUri.getPath}"))
  }

  test("check index on table") {
    val data: Seq[(Int, String)] = (1 to 300).map { i => (i, s"this is test $i") }
    data.toDF("key", "value").createOrReplaceTempView("t")
    sql("insert overwrite table oap_test_1 select * from t")
    sql("insert overwrite table oap_test_2 select * from t")

    checkAnswer(sql("check oindex on oap_test_2"), Nil)

    sql("create oindex index1 on oap_test_1 (a)")
    sql("create oindex index1 on oap_test_2 (a)")
    checkAnswer(sql("check oindex on oap_test_1"), Nil)
    checkAnswer(sql("check oindex on oap_test_2"), Nil)
  }

  test("check index on table: Missing data file") {
    val data = sparkContext.parallelize(1 to 300, 3).map { i => (i, s"this is test $i") }
    val df = data.toDF("key", "value")
    val path = Utils.createTempDir().toString
    df.write.format("oap").mode(SaveMode.Overwrite).save(path)
    val oapDf = spark.read.format("oap").load(path)
    oapDf.createOrReplaceTempView("t")
    checkAnswer(sql("check oindex on t"), Nil)

    // Delete a data file
    val metaOpt = OapUtils.getMeta(sparkContext.hadoopConfiguration, new Path(path))
    assert(metaOpt.nonEmpty)
    assert(metaOpt.get.fileMetas.nonEmpty)
    val dataFileName = metaOpt.get.fileMetas.head.dataFileName
    Utils.deleteRecursively(new File(path, dataFileName))

    val checkResult =
      if (metaOpt.get.fileMetas.length > 1) {
        Seq(Row(s"Data file: $path/$dataFileName not found!"))
      } else {
        Nil
      }
    // Check again
    checkAnswer(sql("check oindex on t"), checkResult)
  }

  test("check index on table: Missing index file") {
    val data: Seq[(Int, String)] = (1 to 300).map { i => (i, s"this is test $i") }
    val df = data.toDF("key", "value")
    val path = Utils.createTempDir().toString
    df.write.format("oap").mode(SaveMode.Overwrite).save(path)
    val oapDf = spark.read.format("oap").load(path)
    oapDf.createOrReplaceTempView("t")
    checkAnswer(sql("check oindex on t"), Nil)

    // Create a B+ tree index on Column("key")
    sql("create oindex idx1 on t(key)")
    checkAnswer(sql("check oindex on t"), Nil)

    // Delete an index file
    val metaOpt = OapUtils.getMeta(sparkContext.hadoopConfiguration, new Path(path))
    assert(metaOpt.nonEmpty)
    assert(metaOpt.get.fileMetas.nonEmpty)
    assert(metaOpt.get.indexMetas.nonEmpty)
    val indexMeta = metaOpt.get.indexMetas.head
    val dataFileName = metaOpt.get.fileMetas.head.dataFileName
    val indexFileName =
      IndexUtils.indexFileFromDataFile(new Path(path, dataFileName),
        indexMeta.name, indexMeta.time).toUri.getPath
    Utils.deleteRecursively(new File(indexFileName))

    // Check again
    checkAnswer(sql("check oindex on t"),
      Row(
        s"""Missing index:idx1,
          |indexColumn(s): key, indexType: BTree
          |for Data File: $path/$dataFileName
          |of table: t""".stripMargin))
  }

  test("check index on partitioned table") {
    val data: Seq[(Int, Int)] = (1 to 10).map { i => (i, i) }
    data.toDF("key", "value").createOrReplaceTempView("t")

    sql(
      """
        |INSERT OVERWRITE TABLE oap_partition_table
        |partition (b=1, c='c1')
        |SELECT key from t where value < 4
      """.stripMargin)

    sql(
      """
        |INSERT INTO TABLE oap_partition_table
        |partition (b=2, c='c2')
        |SELECT key from t where value == 4
      """.stripMargin)
    sql("create oindex idx1 on oap_partition_table(a)")

    checkAnswer(sql("check oindex on oap_partition_table"), Nil)
  }

  test("check index on partitioned table: Missing data file") {
    val data = sparkContext.parallelize(1 to 300, 4).map { i => (i, i) }
    data.toDF("key", "value").createOrReplaceTempView("t")

    sql(
      """
        |INSERT OVERWRITE TABLE oap_partition_table
        |partition (b=1, c='c1')
        |SELECT key from t where value < 4
      """.stripMargin)

    sql(
      """
        |INSERT INTO TABLE oap_partition_table
        |partition (b=2, c='c2')
        |SELECT key from t where value >= 104
      """.stripMargin)
    sql("create oindex idx1 on oap_partition_table(a)")

    checkAnswer(sql("check oindex on oap_partition_table"), Nil)

    // Delete a data file
    val partitionPath = new Path(spark.sqlContext.conf.warehousePath + "/oap_partition_table/b=2/c=c2")
    val metaOpt = OapUtils.getMeta(sparkContext.hadoopConfiguration, partitionPath)
    assert(metaOpt.nonEmpty)
    assert(metaOpt.get.fileMetas.nonEmpty)

    val dataFileName = metaOpt.get.fileMetas.head.dataFileName
    Utils.deleteRecursively(new File(new Path(partitionPath, dataFileName).toUri.getPath))

    val checkResult: Seq[Row] =
      if (metaOpt.get.fileMetas.length > 1) {
        Seq(Row(s"Data file: ${partitionPath.toUri.getPath}/$dataFileName not found!"))
      } else {
        Nil
      }
    // Check again
    checkAnswer(sql("check oindex on oap_partition_table"), checkResult)
  }

  test("check index on partitioned table: Missing index file") {
    val data = sparkContext.parallelize(1 to 300, 4).map { i => (i, i) }
    data.toDF("key", "value").createOrReplaceTempView("t")

    sql(
      """
        |INSERT OVERWRITE TABLE oap_partition_table
        |partition (b=1, c='c1')
        |SELECT key from t where value < 4
      """.stripMargin)

    sql(
      """
        |INSERT INTO TABLE oap_partition_table
        |partition (b=2, c='c2')
        |SELECT key from t where value == 104
      """.stripMargin)

    // Create a B+ tree index on Column("a")
    sql("create oindex idx1 on oap_partition_table(a)")

    checkAnswer(sql("check oindex on oap_partition_table"), Nil)

    // Delete an index file
    val partitionPath = new Path(spark.sqlContext.conf.warehousePath + "/oap_partition_table/b=2/c=c2")
    val metaOpt = OapUtils.getMeta(sparkContext.hadoopConfiguration, partitionPath)
    assert(metaOpt.nonEmpty)
    assert(metaOpt.get.fileMetas.nonEmpty)
    assert(metaOpt.get.indexMetas.nonEmpty)
    val indexMeta = metaOpt.get.indexMetas.head

    val dataFileName = metaOpt.get.fileMetas.head.dataFileName
    val indexFileName =
      IndexUtils.indexFileFromDataFile(
        new Path(partitionPath, dataFileName), indexMeta.name, indexMeta.time).toUri.getPath
    Utils.deleteRecursively(new File(indexFileName))

    // Check again
    checkAnswer(sql("check oindex on oap_partition_table"),
      Row(
        s"""Missing index:idx1,
          |indexColumn(s): a, indexType: BTree
          |for Data File: ${partitionPath.toUri.getPath}/$dataFileName
          |of table: oap_partition_table""".stripMargin))
  }

  test("check multiple partition directories for ambiguous indices") {
    val data = sparkContext.parallelize(1 to 300, 4).map { i => (i, i) }
    data.toDF("key", "value").createOrReplaceTempView("t")

    sql(
      """
        |INSERT OVERWRITE TABLE oap_partition_table
        |partition (b=1, c='c1')
        |SELECT key from t where value < 4
      """.stripMargin)

    sql(
      """
        |INSERT INTO TABLE oap_partition_table
        |partition (b=2, c='c2')
        |SELECT key from t where value == 104
      """.stripMargin)

    // Create a B+ tree index on Column("a")
    sql("create oindex idx1 on oap_partition_table(a) partition(b=1, c='c1')")

    val partitionPath =
      new Path(spark.sqlContext.conf.warehousePath, "oap_partition_table/b=2/c=c2")
    checkAnswer(sql("check oindex on oap_partition_table"),
      Row(s"Meta file not found in partition: ${partitionPath.toUri.getPath}"))

    sql("create oindex idx1 on oap_partition_table(a) using bitmap partition(b=2, c='c2')")

    val exception = intercept[AnalysisException]{
      sql("check oindex on oap_partition_table")
    }
    assert(exception.message.startsWith(
      "\nAmbiguous Index(different indices have the same name):\nindex name:idx1"))

  }
}
