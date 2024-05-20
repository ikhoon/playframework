/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.api.db.evolutions

import java.sql.ResultSet
import java.sql.SQLException

import scala.util.Using

import org.specs2.mutable.After
import org.specs2.mutable.Specification
import play.api.db.Database
import play.api.db.Databases

// TODO: functional test with InvalidDatabaseRevision exception

class EvolutionsSpec extends Specification {
  sequential

  import TestEvolutions._

  "Evolutions" should {
    trait CreateSchema { this: WithEvolutions =>
      execute("create schema testschema")
    }

    trait UpScripts { this: WithEvolutions =>
      val scripts = evolutions.scripts(Seq(a1, a2, a3))

      (scripts must have).length(3)
      scripts must_== Seq(UpScript(a1), UpScript(a2), UpScript(a3))

      evolutions.evolve(scripts, autocommit = true)

      executeQuery("select * from test") { resultSet =>
        resultSet.next must beTrue
        resultSet.getLong(1) must_== 1L
        resultSet.getString(2) must_== "${username}" // escaped !${username} becomes ${username}
        resultSet.getInt(3) must_== 42
        resultSet.next must beFalse
      }
    }

    trait DownScripts { this: WithEvolutions =>
      val original = evolutions.scripts(Seq(a1, a2, a3))
      evolutions.evolve(original, autocommit = true)

      val scripts = evolutions.scripts(Seq(b1, a2, b3))

      (scripts must have).length(6)
      scripts must_== Seq(DownScript(a3), DownScript(a2), DownScript(a1), UpScript(b1), UpScript(a2), UpScript(b3))

      evolutions.evolve(scripts, autocommit = true)

      executeQuery("select * from test") { resultSet =>
        resultSet.next must beTrue
        resultSet.getLong(1) must_== 1L
        resultSet.getString(2) must_== "bob"
        resultSet.getInt(3) must_== 42
        resultSet.next must beFalse
      }
    }

    trait ReportInconsistentStateAndResolve { this: WithEvolutions =>
      val broken = evolutions.scripts(Seq(c1, a2, a3))
      val fixed  = evolutions.scripts(Seq(a1, a2, a3))

      evolutions.evolve(broken, autocommit = true) must throwAn[InconsistentDatabase]

      // inconsistent until resolved
      evolutions.evolve(fixed, autocommit = true) must throwAn[InconsistentDatabase]

      evolutions.resolve(1)

      evolutions.evolve(fixed, autocommit = true)
    }

    trait ResetDatabase { this: WithEvolutions =>
      val scripts = evolutions.scripts(Seq(a1, a2, a3))
      evolutions.evolve(scripts, autocommit = true)
      // Check that there's data in the database
      // Also checks that the variable ${table} was replaced with its substitution
      executeQuery("select * from test") { resultSet =>
        resultSet.next must beTrue
      }

      val resetScripts = evolutions.resetScripts()
      evolutions.evolve(resetScripts, autocommit = true)

      // Should be no table because all downs should have been executed
      // Also checks that the variable ${table} was replaced with its substitution in the down script
      executeQuery("select * from test")(_ => ()) must throwA[SQLException]
    }

    trait ProvideHelperForTesting { this: WithEvolutions =>
      Evolutions.withEvolutions(
        database,
        SimpleEvolutionsReader.forDefault(a1, a2, a3),
        substitutionsPrefix = "${",
        substitutionsSuffix = "}",
        substitutionsMappings = Map("table" -> "test"),
        substitutionsEscape = true
      ) {
        // Check that there's data in the database
        // Also checks that the variable ${table} was replaced with its substitution
        executeQuery("select * from test") { resultSet =>
          resultSet.next must beTrue
        }

        // Check that we save raw variables in the play meta table
        val metaResultSet = executeQuery("select * from play_evolutions where id = 1") { metaResultSet =>
          metaResultSet.next must beTrue
          metaResultSet.getString(
            "apply_script"
          ) mustEqual "create table ${table} (id bigint not null, name varchar(255));"
        }
      }

      // Check that cleanup was done afterwards
      executeQuery("select * from test")(_ => ()) must throwA[SQLException]
    }

    trait ProvideHelperForTestingSchemaAndMetaTable { this: WithEvolutions =>
      // Check if the play_evolutions table was created within the testschema with a custom table name
      executeQuery("select count(0) from testschema.sample_play_evolutions") { resultSet =>
        resultSet.next must beTrue
        resultSet.close()
      }
    }

    trait CheckSchemaString { this: WithEvolutions =>
      val scripts = evolutions.scripts(Seq(a1, a2, a3, a4))
      evolutions.evolve(scripts, autocommit = true)
      executeQuery("select name from test where id = 2") { resultSet =>
        resultSet.next must beTrue
        resultSet.getString(1) must_== "some string !${asdf} with ${schema}" // not escaping !${asdf} here
      }

      // Check that we save raw _escaped_ variables !${...} in the play meta table
      executeQuery("select * from testschema.sample_play_evolutions where id = 4") { metaResultSet =>
        metaResultSet.next must beTrue
        metaResultSet.getString(
          "apply_script"
        ) mustEqual "insert into test (id, name, age) values (2, 'some string !${asdf} with ${schema}', 87);"
      }
    }

    "apply up scripts" in new UpScripts with WithEvolutions
    "apply up scripts derby" in new UpScripts with WithDerbyEvolutions

    "apply down scripts" in new DownScripts with WithEvolutions
    "apply down scripts derby" in new DownScripts with WithDerbyEvolutions

    "report inconsistent state and resolve" in new ReportInconsistentStateAndResolve with WithEvolutions
    "report inconsistent state and resolve derby" in new ReportInconsistentStateAndResolve with WithDerbyEvolutions

    "reset the database" in new ResetDatabase with WithEvolutions
    "reset the database derby" in new ResetDatabase with WithDerbyEvolutions

    "provide a helper for testing" in new ProvideHelperForTesting with WithEvolutions
    "provide a helper for testing derby" in new ProvideHelperForTesting with WithDerbyEvolutions

    // Test if the play_evolutions table gets created within a schema
    "create test schema derby" in new CreateSchema with WithDerbyEvolutionsSchema
    "reset the database to trigger creation of the sample_play_evolutions table in the testschema derby" in new ResetDatabase
      with WithDerbyEvolutionsSchema
    "provide a helper for testing derby schema" in new ProvideHelperForTestingSchemaAndMetaTable
      with WithDerbyEvolutionsSchema
    "not replace the string ${schema} in an evolutions script" in new CheckSchemaString
      with WithDerbyEvolutionsSchemaUnescaped
  }

  trait WithEvolutions extends After {
    lazy val database =
      Databases.inMemory("default", Map.empty, Map("hikaricp.maximumPoolSize" -> 1))

    lazy val evolutions = new DatabaseEvolutions(
      database = database,
      substitutionsPrefix = "${",
      substitutionsSuffix = "}",
      substitutionsMappings = Map("table" -> "test"),
      substitutionsEscape = true
    )

    private def connection() = database.getConnection()

    def executeQuery(sql: String)(withResult: ResultSet => Unit): Unit = Using(database.getConnection()) { connection =>
      Using(connection.createStatement.executeQuery(sql))(withResult).get
    }.get

    def execute(sql: String): Boolean = Using(database.getConnection()) { connection =>
      connection.createStatement.execute(sql)
    }.get

    def after: Any = {
      database.shutdown()
    }
  }

  trait WithDerbyEvolutions extends WithEvolutions {
    override lazy val database: Database = Databases(
      driver = "org.apache.derby.jdbc.EmbeddedDriver",
      url = "jdbc:derby:memory:default;create=true"
    )
  }

  trait WithDerbyEvolutionsSchema extends WithDerbyEvolutions {
    override lazy val evolutions: DatabaseEvolutions = new DatabaseEvolutions(
      database = database,
      schema = "testschema",
      metaTable = "sample_play_evolutions",
      substitutionsPrefix = "${",
      substitutionsSuffix = "}",
      substitutionsMappings = Map("table" -> "test"),
      substitutionsEscape = true
    )
  }

  trait WithDerbyEvolutionsSchemaUnescaped extends WithDerbyEvolutions {
    override lazy val evolutions: DatabaseEvolutions = new DatabaseEvolutions(
      database = database,
      schema = "testschema",
      metaTable = "sample_play_evolutions",
      substitutionsPrefix = "${",
      substitutionsSuffix = "}",
      substitutionsMappings = Map("table" -> "test"),
      substitutionsEscape = false
    )
  }

  object TestEvolutions {
    val a1 = Evolution(
      1,
      "create table ${table} (id bigint not null, name varchar(255));",
      "drop table ${table};"
    )

    val a2 = Evolution(
      2,
      "alter table ${table} add column age int;",
      "alter table ${table} drop age;"
    )

    val a3 = Evolution(
      3,
      "insert into test (id, name, age) values (1, '!${username}', 42);",
      "delete from test;"
    )

    val a4 = Evolution(
      4,
      "insert into test (id, name, age) values (2, 'some string !${asdf} with ${schema}', 87);",
      "delete from test where id=2;"
    )

    val b1 = Evolution(
      1,
      "create table test (id bigint not null, content varchar(255));",
      "drop table test;"
    )

    val b3 = Evolution(
      3,
      "insert into test (id, content, age) values (1, 'bob', 42);",
      "delete from test;"
    )

    val c1 = Evolution(
      1,
      "creaTYPOe table test (id bigint not null, name varchar(255));"
    )
  }
}
