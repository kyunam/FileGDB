package com.esri.gdb

import com.esri.gdb.GDBTable.getClass
import org.apache.hadoop.conf.Configuration
import org.apache.spark.sql.Row
import org.apache.spark.sql.types.StructType
import org.slf4j.LoggerFactory


class FileGDB(index: GDBIndex, table: GDBTable) extends AutoCloseable with Serializable {
  def schema(): StructType = {
    table.schema
  }

  def rows(numRowsToRead: Int = -1, startAtRow: Int = 0): Iterator[Row] = table.rows(index, numRowsToRead, startAtRow)

  override def close(): Unit = {
    table.close()
    index.close()
  }
}

object FileGDB extends Serializable {

  private val logger = LoggerFactory.getLogger(getClass)

  def listTables(conf: Configuration, pathName: String, wkid: Int): Array[NameIndex] = {
    val gdbIndex = GDBIndex(conf, pathName, "a00000001")
    try {
      val gdbTable = GDBTable(conf, pathName, "a00000001", wkid)
      try {
        val indexID = gdbTable.schema.fieldIndex("ID")
        val indexName = gdbTable.schema.fieldIndex("Name")
        val indexFileFormat = gdbTable.schema.fieldIndex("FileFormat")
        gdbTable
          .rows(gdbIndex, gdbTable.maxRows)
          .map(row => {
            val id = row.getInt(indexID)
            val name = row.getString(indexName)
            val fileFormat = row.getInt(indexFileFormat)
            logger.debug(s"listTables::id=$id name=$name fileFormat=$fileFormat")
            NameIndex(name, id)
          })
          .filterNot(row => {
            row.name.startsWith("GDB_")
          })
          .toArray
      } finally {
        gdbTable.close()
      }
    } finally {
      gdbIndex.close()
    }
  }

  // So it can be used from PySpark
  def listTables(pathName: String): Array[NameIndex] = listTables(new Configuration(), pathName, 4326): Array[NameIndex]

  def findTable(conf: Configuration, pathName: String, tableName: String, wkid: Int): Option[NameIndex] = {
    listTables(conf, pathName, wkid).find(_.name == tableName)
  }

  def schema(conf: Configuration, pathName: String, tableName: String, wkid: Int): Option[StructType] = {
    findTable(conf, pathName, tableName, wkid) match {
      case Some(catRow) => {
        val table = GDBTable(conf, pathName, catRow.toTableName, wkid)
        try {
          Some(table.schema)
        }
        finally {
          table.close()
        }
      }
      case _ => None
    }
  }

  // So it can be user by PySpark
  def schema(pathName: String, tableName: String, wkid: Int): StructType = {
    schema(new Configuration(), pathName, tableName, wkid) match {
      case Some(schema) => schema
      case _ => StructType(Seq.empty)
    }
  }

  def schema(pathName: String, tableName: String): StructType = {
    schema(pathName, tableName, 4326)
  }

  def rows(pathName: String, tableName: String): Array[Row] = {
    rows(new Configuration(), pathName, tableName, -1)
  }

  def rows(conf: Configuration, pathName: String, tableName: String, wkid: Int): Array[Row] = {
    findTable(conf, pathName, tableName, wkid) match {
      case Some(catRow) => {
        val internalName = catRow.toTableName
        val table = GDBTable(conf, pathName, internalName, wkid)
        try {
          val index = GDBIndex(conf, pathName, internalName)
          try {
            table.rows(index).toArray
          } finally {
            index.close()
          }
        }
        finally {
          table.close()
        }
      }
      case _ => Array.empty
    }
  }

  def apply(pathName: String, tableName: String, conf: Configuration = new Configuration(), wkid: Int = 4326): Option[FileGDB] = {
    findTable(conf, pathName, tableName, wkid) match {
      case Some(catRow) => {
        val internalName = catRow.toTableName
        val index = GDBIndex(conf, pathName, internalName)
        val table = GDBTable(conf, pathName, internalName, wkid)
        Some(new FileGDB(index, table))
      }
      case _ => None
    }
  }
}
