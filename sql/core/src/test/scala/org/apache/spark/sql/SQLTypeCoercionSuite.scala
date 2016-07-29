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

import java.math.BigDecimal

import org.apache.spark.sql.test.SharedSQLContext

/**
 * End-to-end tests for type coercion.
 */
class SQLTypeCoercionSuite extends QueryTest with SharedSQLContext {

  test("SPARK-16714 decimal widening") {
    val v1 = new BigDecimal(1).divide(new BigDecimal(1000))
    val v2 = new BigDecimal(1).divide(new BigDecimal(10)).setScale(3)

    checkAnswer(
      sql("select map(0.001, 0.001, 0.1, 0.1)"),
      Row(Map(v1 -> v1, v2 -> v2))
    )

    checkAnswer(
      sql("select array(0.001, 0.1)"),
      Row(Seq(v1, v2))
    )

    checkAnswer(
      sql("select greatest(0.001, 0.1), least(0.001, 0.1)"),
      Row(v2, v1)
    )

    checkAnswer(
      sql(
        """
          |select ifnull(0.001, 0.1), nullif(0.001, 0.1), nvl2(0.001, 0.001, 0.1), nvl(0.001, 0.1),
          |       if(true, 0.001, 0.1)
        """.stripMargin),
      Row(v1, v1, v1, v1, v1)
    )
  }

}