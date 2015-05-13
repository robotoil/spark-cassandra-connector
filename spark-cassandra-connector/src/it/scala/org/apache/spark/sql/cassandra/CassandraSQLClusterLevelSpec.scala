package org.apache.spark.sql.cassandra

import com.datastax.spark.connector.SparkCassandraITFlatSpecBase
import com.datastax.spark.connector.cql.{CassandraConnector, CassandraConnectorConf}
import com.datastax.spark.connector.embedded.EmbeddedCassandra._
import com.datastax.spark.connector.rdd.ReadConf
import com.datastax.spark.connector.writer.WriteConf
import org.apache.spark.SparkConf

class CassandraSQLClusterLevelSpec extends SparkCassandraITFlatSpecBase {
  useCassandraConfig(Seq("cassandra-default.yaml.template", "cassandra-default.yaml.template"))
  useSparkConf(defaultSparkConf)

  val conn = CassandraConnector(Set(getHost(0)))

  conn.withSessionDo { session =>
    session.execute("CREATE KEYSPACE IF NOT EXISTS sql_cluster_test1 WITH REPLICATION = { 'class': 'SimpleStrategy', 'replication_factor': 1 }")

    session.execute("CREATE TABLE IF NOT EXISTS sql_cluster_test1.test1 (a INT PRIMARY KEY, b INT, c INT)")
    session.execute("USE sql_cluster_test1")
    session.execute("INSERT INTO sql_cluster_test1.test1 (a, b, c) VALUES (1, 1, 1)")
    session.execute("INSERT INTO sql_cluster_test1.test1 (a, b, c) VALUES (2, 1, 2)")
    session.execute("INSERT INTO sql_cluster_test1.test1 (a, b, c) VALUES (3, 1, 3)")
    session.execute("INSERT INTO sql_cluster_test1.test1 (a, b, c) VALUES (4, 1, 4)")
    session.execute("INSERT INTO sql_cluster_test1.test1 (a, b, c) VALUES (5, 1, 5)")
  }

  val conn2 = CassandraConnector(Set(getHost(1)), getNativePort(1))
  conn2.withSessionDo { session =>
    session.execute("CREATE KEYSPACE IF NOT EXISTS sql_cluster_test2 WITH REPLICATION = { 'class': 'SimpleStrategy', 'replication_factor': 1 }")

    session.execute("CREATE TABLE IF NOT EXISTS sql_cluster_test2.test2 (a INT PRIMARY KEY, d INT, e INT)")
    session.execute("USE sql_cluster_test2")
    session.execute("INSERT INTO sql_cluster_test2.test2 (a, d, e) VALUES (8, 1, 8)")
    session.execute("INSERT INTO sql_cluster_test2.test2 (a, d, e) VALUES (7, 1, 7)")
    session.execute("INSERT INTO sql_cluster_test2.test2 (a, d, e) VALUES (6, 1, 6)")
    session.execute("INSERT INTO sql_cluster_test2.test2 (a, d, e) VALUES (4, 1, 4)")
    session.execute("INSERT INTO sql_cluster_test2.test2 (a, d, e) VALUES (5, 1, 5)")
    session.execute("CREATE TABLE IF NOT EXISTS sql_cluster_test2.test3 (a INT PRIMARY KEY, d INT, e INT)")
  }

  val cc: CassandraSQLContext = new CassandraSQLContext(sc)

  override def beforeAll() {
    val conf1 = new SparkConf(true)
      .set("spark.cassandra.connection.host", getHost(0).getHostAddress)
      .set("spark.cassandra.connection.native.port", getNativePort(0).toString)
      .set("spark.cassandra.connection.rpc.port", getRpcPort(0).toString)
    val conf2 = new SparkConf(true)
      .set("spark.cassandra.connection.host", getHost(1).getHostAddress)
      .set("spark.cassandra.connection.native.port", getNativePort(1).toString)
      .set("spark.cassandra.connection.rpc.port", getRpcPort(1).toString)
    cc.addCassandraConnConf(CassandraConnectorConf(conf1), "cluster1")
    cc.addCassandraConnConf(CassandraConnectorConf(conf2), "cluster2")
    cc.addClusterLevelReadConf(ReadConf.fromSparkConf(sc.getConf), "cluster1")
    cc.addClusterLevelWriteConf(WriteConf.fromSparkConf(sc.getConf), "cluster1")
    cc.addClusterLevelReadConf(ReadConf.fromSparkConf(sc.getConf), "cluster2")
    cc.addClusterLevelWriteConf(WriteConf.fromSparkConf(sc.getConf), "cluster2")
  }

  override def afterAll() {
    super.afterAll()
    conn.withSessionDo { session =>
      session.execute("DROP KEYSPACE sql_cluster_test1")
    }
    conn2.withSessionDo { session =>
      session.execute("DROP KEYSPACE sql_cluster_test2")
    }
  }

  it should "allow to join tables from different clusters" in {
    val result = cc.sql("SELECT * FROM cluster1.sql_cluster_test1.test1 AS test1 Join cluster2.sql_cluster_test2.test2 AS test2 where test1.a=test2.a").collect()
    result should have length 2
  }

  it should "allow to write data to another cluster" in {
    val insert = cc.sql("INSERT INTO TABLE cluster2.sql_cluster_test2.test3 SELECT * FROM cluster1.sql_cluster_test1.test1 AS t1").collect()
    val result = cc.sql("SELECT * FROM cluster2.sql_cluster_test2.test3 AS test3").collect()
    result should have length 5
  }
}