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

package org.apache.spark.sql.execution.datasources.oap.index

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path

import org.apache.spark.sql.execution.datasources.OapException
import org.apache.spark.sql.execution.datasources.oap.filecache.{FiberCache, MemoryManager}
import org.apache.spark.sql.execution.datasources.oap.io.IndexFile
import org.apache.spark.unsafe.Platform

private[oap] case class BTreeIndexFileReader(
    configuration: Configuration,
    file: Path) {

  private val VERSION_SIZE = IndexFile.FILEHEADER_LENGTH
  private val FOOTER_LENGTH_SIZE = IndexUtils.INT_SIZE
  private val ROW_ID_LIST_LENGTH_SIZE = IndexUtils.INT_SIZE

  // Section ID for fiber cache reading.
  val footerSectionId: Int = 0
  val rowIdListSectionId: Int = 1
  val nodeSectionId: Int = 2

  private val (reader, fileLength) = {
    val fs = file.getFileSystem(configuration)
    (fs.open(file), fs.getFileStatus(file).getLen)
  }

  private val (footerLength, rowIdListLength) = {
    val sectionLengthIndex = fileLength - FOOTER_LENGTH_SIZE - ROW_ID_LIST_LENGTH_SIZE
    val sectionLengthBuffer = new Array[Byte](FOOTER_LENGTH_SIZE + ROW_ID_LIST_LENGTH_SIZE)
    reader.readFully(sectionLengthIndex, sectionLengthBuffer)
    val rowIdListSize = getIntFromBuffer(sectionLengthBuffer, 0)
    val footerSize = getIntFromBuffer(sectionLengthBuffer, ROW_ID_LIST_LENGTH_SIZE)
    (footerSize, rowIdListSize)
  }

  private def footerIndex = fileLength - FOOTER_LENGTH_SIZE - ROW_ID_LIST_LENGTH_SIZE - footerLength
  private def rowIdListIndex = footerIndex - rowIdListLength
  private def nodesIndex = VERSION_SIZE

  private def getIntFromBuffer(buffer: Array[Byte], offset: Int) =
    Platform.getInt(buffer, Platform.BYTE_ARRAY_OFFSET + offset)

  def checkVersion: Unit = {
    val fileVersion = new Array[Byte](VERSION_SIZE - IndexFile.HEADER_PREFIX.length)
    reader.readFully(IndexFile.HEADER_PREFIX.length, fileVersion)
    val versionData =
      Array(
        (IndexFile.INDEX_VERSION >> 8).toByte,
        (IndexFile.INDEX_VERSION & 0xFF).toByte)

    if (!versionData.sameElements(fileVersion)) {
      reader.close()
      throw new OapException("Btree Index File version is not compatible!")
    }
  }

  def readFooter(): FiberCache =
    MemoryManager.putToIndexFiberCache(reader, footerIndex, footerLength)

  def readRowIdList(): FiberCache =
    MemoryManager.putToIndexFiberCache(reader, rowIdListIndex, rowIdListLength)

  def readNode(offset: Int, size: Int): FiberCache =
    MemoryManager.putToIndexFiberCache(reader, nodesIndex + offset, size)

  def close(): Unit = reader.close()
}
