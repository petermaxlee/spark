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

package org.apache.spark.sql

import java.io.File
import java.util.{Locale, TimeZone}

import org.apache.spark.sql.catalyst.rules.RuleExecutor
import org.apache.spark.sql.catalyst.util.{fileToString, stringToFile}
import org.apache.spark.sql.test.SharedSQLContext

/**
 * End-to-end test cases for SQL queries.
 *
 * Each case is loaded from a file in "spark/sql/core/src/test/resources/sql-tests/inputs".
 * Each case has a golden result file in "spark/sql/core/src/test/resources/sql-tests/results".
 *
 * To re-generate golden files, run:
 * {{{
 *   SPARK_GENERATE_GOLDEN_FILES=1 build/sbt "sql/test-only *SQLQueryTestSuite"
 * }}}
 *
 * The format for input files is simple:
 *  1. A list of SQL queries separated by semicolon.
 *  2. Lines starting with -- are treated as comments and ignored.
 *
 * For example:
 * {{{
 *   -- this is a comment
 *   select 1, -1;
 *   select current_date;
 * }}}
 *
 * The format for golden result files look roughly like:
 * {{{
 *   -- some header information
 *
 *   -- !query 0
 *   select 1, -1
 *   -- !query 0 schema
 *   struct<...schema...>
 *   -- !query 0 output
 *   ... data row 1 ...
 *   ... data row 2 ...
 *   ...
 *
 *   -- !query 1
 *   ...
 * }}}
 */
class SQLQueryTestSuite extends QueryTest with SharedSQLContext {

  private val regenerateGoldenFiles: Boolean = System.getenv("SPARK_GENERATE_GOLDEN_FILES") == "1"

  private val baseResourcePath = {
    // If regenerateGoldenFiles is true, we must be running this in SBT and we use hard-coded
    // relative path. Otherwise, we use classloader's getResource to find the location.
    if (regenerateGoldenFiles) {
      java.nio.file.Paths.get("src", "test", "resources", "sql-tests").toFile
    } else {
      val res = getClass.getClassLoader.getResource("sql-tests")
      new File(res.getFile)
    }
  }

  private val inputFilePath = new File(baseResourcePath, "inputs").getAbsolutePath
  private val goldenFilePath = new File(baseResourcePath, "results").getAbsolutePath

  /** List of test cases to ignore, in lower cases. */
  private val blackList = Set(
    "blacklist.sql"  // Do NOT remove this one. It is here to test the blacklist functionality.
  )

  // Create all the test cases.
  listTestCases().foreach(createScalaTestCase)

  /** A test case. */
  private case class TestCase(name: String, inputFile: String, resultFile: String)

  /** A single SQL query's output. */
  private case class QueryOutput(sql: String, schema: String, output: String) {
    def toString(queryIndex: Int): String = {
      // We are explicitly not using multi-line string due to stripMargin removing "|" in output.
      s"-- !query $queryIndex\n" +
        sql + "\n" +
        s"-- !query $queryIndex schema\n" +
        schema + "\n" +
         s"-- !query $queryIndex output\n" +
        output
    }
  }

  private def createScalaTestCase(testCase: TestCase): Unit = {
    if (blackList.contains(testCase.name.toLowerCase)) {
      // Create a test case to ignore this case.
      ignore(testCase.name) { /* Do nothing */ }
    } else {
      // Create a test case to run this case.
      test(testCase.name) { runTest(testCase) }
    }
  }

  /** Run a test case. */
  private def runTest(testCase: TestCase): Unit = {
    val input = fileToString(new File(testCase.inputFile))

    // List of SQL queries to run
    val queries: Seq[String] = {
      // val cleaned = input.split("\n").filterNot(_.matches("--.*(?<=[^\\\\]);")).mkString("\n")
      val cleaned = input.split("\n").filterNot(_.startsWith("--")).mkString("\n")
      // note: this is not a robust way to split queries using semicolon, but works for now.
      cleaned.split("(?<=[^\\\\]);").map(_.trim).filter(_ != "").toSeq
    }

    // Run the SQL queries preparing them for comparison.
    val outputs: Seq[QueryOutput] = queries.map { sql =>
      val df = spark.sql(sql)
      // We might need to do some query canonicalization in the future.
      QueryOutput(
        sql = sql,
        schema = df.schema.catalogString,
        output = df.queryExecution.hiveResultString().mkString("\n"))
    }

    if (regenerateGoldenFiles) {
      // Again, we are explicitly not using multi-line string due to stripMargin removing "|".
      val goldenOutput = {
        s"-- Automatically generated by ${getClass.getName}\n" +
        s"-- Number of queries: ${outputs.size}\n\n\n" +
        outputs.zipWithIndex.map{case (qr, i) => qr.toString(i)}.mkString("\n\n\n") + "\n"
      }
      stringToFile(new File(testCase.resultFile), goldenOutput)
    }

    // Read back the golden file.
    val expectedOutputs: Seq[QueryOutput] = {
      val goldenOutput = fileToString(new File(testCase.resultFile))
      val segments = goldenOutput.split("-- !query.+\n")
      assert(segments.size == outputs.size * 3 + 1)  // each query has 3 segments, plus the header
      Seq.tabulate(outputs.size) { i =>
        QueryOutput(
          sql = segments(i * 3 + 1).trim,
          schema = segments(i * 3 + 2).trim,
          output = segments(i * 3 + 3).trim
        )
      }
    }

    // Compare results.
    assertResult(expectedOutputs.size, s"Number of queries should be ${expectedOutputs.size}") {
      outputs.size
    }

    outputs.zip(expectedOutputs).zipWithIndex.foreach { case ((output, expected), i) =>
      assertResult(expected.sql, s"SQL query should match for query #$i") { output.sql }
      assertResult(expected.schema, s"Schema should match for query #$i") { output.schema }
      assertResult(expected.output, s"Result should match for query #$i") { output.output }
    }
  }

  private def listTestCases(): Seq[TestCase] = {
    listFilesRecursively(new File(inputFilePath)).map { file =>
      val resultFile = file.getAbsolutePath.replace(inputFilePath, goldenFilePath) + ".out"
      TestCase(file.getName, file.getAbsolutePath, resultFile)
    }
  }

  /** Returns all the files (not directories) in a directory, recursively. */
  private def listFilesRecursively(path: File): Seq[File] = {
    val (dirs, files) = path.listFiles().partition(_.isDirectory)
    files ++ dirs.flatMap(listFilesRecursively)
  }

  private val originalTimeZone = TimeZone.getDefault
  private val originalLocale = Locale.getDefault

  override def beforeAll(): Unit = {
    super.beforeAll()
    // Timezone is fixed to America/Los_Angeles for those timezone sensitive tests (timestamp_*)
    TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    // Add Locale setting
    Locale.setDefault(Locale.US)
    RuleExecutor.resetTime()
  }

  override def afterAll(): Unit = {
    try {
      TimeZone.setDefault(originalTimeZone)
      Locale.setDefault(originalLocale)

      // For debugging dump some statistics about how much time was spent in various optimizer rules
      logWarning(RuleExecutor.dumpTimeSpent())
    } finally {
      super.afterAll()
    }
  }
}
