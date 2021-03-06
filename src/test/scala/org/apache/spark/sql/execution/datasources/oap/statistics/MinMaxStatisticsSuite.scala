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
package org.apache.spark.sql.execution.datasources.oap.statistics

import scala.util.Random

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.datasources.oap.index.{IndexScanner, IndexUtils}
import org.apache.spark.unsafe.Platform

class MinMaxStatisticsSuite extends StatisticsTest {

  private class TestMinMax extends MinMaxStatistics {
    def getMin: InternalRow = min
    def getMax: InternalRow = max
  }

  test("write function test") {
    val keys = Random.shuffle(1 to 300).map(i => rowGen(i)).toArray

    val testMinMax = new TestMinMax
    testMinMax.initialize(schema)
    for (key <- keys) testMinMax.addOapKey(key)
    testMinMax.write(out, null) // MinMax does not need sortKeys parameter

    val min = testMinMax.getMin
    val max = testMinMax.getMax
    for (key <- keys) {
      assert(ordering.compare(key, min) >= 0)
      assert(ordering.compare(key, max) <= 0)
    }

    val bytes = out.buf.toByteArray
    var offset = 0L

    assert(Platform.getInt(bytes, Platform.BYTE_ARRAY_OFFSET) == MinMaxStatisticsType.id)
    offset += 4
    val minSize = Platform.getInt(bytes, Platform.BYTE_ARRAY_OFFSET + offset)
    val minFromFile = Statistics.getUnsafeRow(schema.length, bytes, offset, minSize)
    checkInternalRow(minFromFile, converter(rowGen(1)))
    offset += (4 + minSize)

    val maxSize = Platform.getInt(bytes, Platform.BYTE_ARRAY_OFFSET + offset)
    val maxFromFile = Statistics.getUnsafeRow(schema.length, bytes, offset, maxSize)
    checkInternalRow(maxFromFile, converter(rowGen(300)))
    offset += (4 + minSize)
  }

  test("read function test") {
    val keys = Random.shuffle(1 to 300).map(i => rowGen(i)).toArray

    IndexUtils.writeInt(out, MinMaxStatisticsType.id)
    Statistics.writeInternalRow(converter, rowGen(1), out)
    Statistics.writeInternalRow(converter, rowGen(300), out)

    val bytes = out.buf.toByteArray

    val testMinMax = new TestMinMax
    testMinMax.initialize(schema)
    testMinMax.read(bytes, 0)

    val min = testMinMax.getMin
    val max = testMinMax.getMax

    for (key <- keys) {
      assert(ordering.compare(key, min) >= 0)
      assert(ordering.compare(key, max) <= 0)
    }

    assert(ordering.compare(rowGen(1), min) == 0)
    assert(ordering.compare(rowGen(300), max) == 0)
  }

  test("read and write") {
    val keys = Random.shuffle(1 to 300).map(i => rowGen(i)).toArray

    val minmaxWrite = new TestMinMax
    minmaxWrite.initialize(schema)
    for (key <- keys) minmaxWrite.addOapKey(key)
    minmaxWrite.write(out, null) // MinMax does not need sortKeys parameter

    val bytes = out.buf.toByteArray

    val minmaxRead = new TestMinMax
    minmaxRead.initialize(schema)
    minmaxRead.read(bytes, 0)
    val min = minmaxRead.getMin
    val max = minmaxRead.getMax

    for (key <- keys) {
      assert(ordering.compare(key, min) >= 0)
      assert(ordering.compare(key, max) <= 0)
    }

    assert(ordering.compare(rowGen(1), min) == 0)
    assert(ordering.compare(rowGen(300), max) == 0)
  }

  test("analyse function test") {
    val keys = Random.shuffle(1 to 300).map(i => rowGen(i)).toArray

    val minmaxWrite = new TestMinMax
    minmaxWrite.initialize(schema)
    for (key <- keys) minmaxWrite.addOapKey(key)
    minmaxWrite.write(out, null) // MinMax does not need sortKeys parameter

    val bytes = out.buf.toByteArray

    val minmaxRead = new TestMinMax
    minmaxRead.initialize(schema)
    minmaxRead.read(bytes, 0)

    generateInterval(rowGen(-10), rowGen(-1), startInclude = true, endInclude = true)
    assert(minmaxRead.analyse(intervalArray) == StaticsAnalysisResult.SKIP_INDEX)

    generateInterval(rowGen(301), rowGen(400), startInclude = true, endInclude = true)
    assert(minmaxRead.analyse(intervalArray) == StaticsAnalysisResult.SKIP_INDEX)

    generateInterval(rowGen(300), rowGen(400), startInclude = true, endInclude = true)
    assert(minmaxRead.analyse(intervalArray) == StaticsAnalysisResult.USE_INDEX)

    generateInterval(rowGen(30), rowGen(40), startInclude = true, endInclude = true)
    assert(minmaxRead.analyse(intervalArray) == StaticsAnalysisResult.USE_INDEX)

    generateInterval(rowGen(-10), rowGen(1), startInclude = true, endInclude = true)
    assert(minmaxRead.analyse(intervalArray) == StaticsAnalysisResult.USE_INDEX)

    generateInterval(IndexScanner.DUMMY_KEY_START, IndexScanner.DUMMY_KEY_END,
      startInclude = true, endInclude = true)
    assert(minmaxRead.analyse(intervalArray) == StaticsAnalysisResult.USE_INDEX)

    generateInterval(IndexScanner.DUMMY_KEY_START, rowGen(1),
      startInclude = true, endInclude = true)
    assert(minmaxRead.analyse(intervalArray) == StaticsAnalysisResult.USE_INDEX)

    generateInterval(IndexScanner.DUMMY_KEY_START, rowGen(0),
      startInclude = true, endInclude = true)
    assert(minmaxRead.analyse(intervalArray) == StaticsAnalysisResult.SKIP_INDEX)

    generateInterval(IndexScanner.DUMMY_KEY_START, rowGen(300),
      startInclude = true, endInclude = true)
    assert(minmaxRead.analyse(intervalArray) == StaticsAnalysisResult.USE_INDEX)

    generateInterval(rowGen(0), IndexScanner.DUMMY_KEY_END,
      startInclude = true, endInclude = true)
    assert(minmaxRead.analyse(intervalArray) == StaticsAnalysisResult.USE_INDEX)

    generateInterval(rowGen(1), IndexScanner.DUMMY_KEY_END,
      startInclude = true, endInclude = true)
    assert(minmaxRead.analyse(intervalArray) == StaticsAnalysisResult.USE_INDEX)

    generateInterval(rowGen(150), IndexScanner.DUMMY_KEY_END,
      startInclude = true, endInclude = true)
    assert(minmaxRead.analyse(intervalArray) == StaticsAnalysisResult.USE_INDEX)

    generateInterval(rowGen(300), IndexScanner.DUMMY_KEY_END,
      startInclude = true, endInclude = true)
    assert(minmaxRead.analyse(intervalArray) == StaticsAnalysisResult.USE_INDEX)

    generateInterval(rowGen(301), IndexScanner.DUMMY_KEY_END,
      startInclude = true, endInclude = true)
    assert(minmaxRead.analyse(intervalArray) == StaticsAnalysisResult.SKIP_INDEX)
  }
}
