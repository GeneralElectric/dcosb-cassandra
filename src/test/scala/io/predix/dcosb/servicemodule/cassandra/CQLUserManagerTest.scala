package io.predix.dcosb.servicemodule.cassandra

import java.net.InetSocketAddress
import java.util.concurrent.{Executor, TimeUnit}

import akka.actor.{Actor, Props}
import akka.testkit.{CallingThreadDispatcher, TestActorRef}
import akka.util.Timeout
import akka.pattern.ask
import com.datastax.driver.core._
import com.datastax.driver.core.exceptions.{AuthenticationException, NoHostAvailableException}
import com.google.common.util.concurrent.{Futures, ListenableFuture}
import io.predix.dcosb.util.ActorSuite
import io.predix.dcosb.util.actor.ConfiguredActor

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

class CQLUserManagerTest extends ActorSuite {
  implicit val executionContext =
    system.dispatchers.lookup(CallingThreadDispatcher.Id)
  implicit val timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))


  trait ClusterMock {

    val cluster = mock[Cluster]

  }

  trait UserManager {

    val unconfiguredUserManager:TestActorRef[CQLUserManager] = TestActorRef(Props(new CQLUserManager {
      override def postStop(): Unit = {}
    }).withDispatcher(CallingThreadDispatcher.Id))

  }

  "A CQLUserManager" - {


    "on receiving configuration with a Cluster object" - {

      "invokes init on the Cluster object" in new ClusterMock with UserManager {

        (cluster.init _) expects() once()
        Await.result(unconfiguredUserManager ? CQLUserManager.Configuration(cluster), timeout.duration)

      }

      "with accessible contact nodes and correct credentials" - {

        "replies with ConfiguredActor.Configured" in new ClusterMock with UserManager {

          (cluster.init _) expects() once()
          Await.result(unconfiguredUserManager ? CQLUserManager.Configuration(cluster), timeout.duration) shouldEqual Success(ConfiguredActor.Configured())


        }

      }

      "with inaccessible contact nodes" - {

        "replies with a Failure of NoHostAvailableException wrapped in ClusterUnavailable" in new ClusterMock with UserManager {

          (cluster.init _) expects() throwing new NoHostAvailableException(stub[java.util.Map[InetSocketAddress, Throwable]]) once()
          Await.result(unconfiguredUserManager ? CQLUserManager.Configuration(cluster), timeout.duration) should matchPattern {
            case Failure(CQLUserManager.ClusterUnavailable(_: NoHostAvailableException)) =>
          }

        }

      }

      "with accessible contact nodes but invalid credentials" - {

        "replies with a Failure of AuthenticationException wrapped in ClusterUnavailable" in new ClusterMock with UserManager {

          (cluster.init _) expects() throwing new AuthenticationException(stub[InetSocketAddress], "") once()
          Await.result(unconfiguredUserManager ? CQLUserManager.Configuration(cluster), timeout.duration) should matchPattern {
            case Failure(CQLUserManager.ClusterUnavailable(_: AuthenticationException)) =>
          }

        }

      }


    }

  }

  "A Configured CQLUserManager" - {

    trait ConfiguredCQLUserManager extends UserManager {

      def configuredUserManager(configuration: CQLUserManager.Configuration): TestActorRef[CQLUserManager] = {
        Await.result(unconfiguredUserManager ? configuration, timeout.duration)

        unconfiguredUserManager
      }


    }

    trait SessionMock extends ClusterMock {

      val session = mock[Session]
      (cluster.init _) expects() once()
      (cluster.connectAsync _) expects() returning Futures.immediateFuture[Session](session) once()

    }

    trait ExecuteAsyncMock extends SessionMock {

      def expectToExecuteAsync(statement: Statement): ResultSetFuture = {

        val rsFuture = stub[ResultSetFuture]
        (rsFuture.get _) when() returns stub[ResultSet]
                (rsFuture.addListener _) when(*, *) onCall { (listener: Runnable, executor: Executor) =>
                  listener.run()
                } once()
        (session.executeAsync(_: Statement)) expects(where { (s:Statement) => s.toString == statement.toString }) returning rsFuture once()

        rsFuture

      }

      def expectToExecuteAsync(): ResultSetFuture = {

        val rsFuture = stub[ResultSetFuture]
        (rsFuture.get _) when() returns stub[ResultSet]
        (rsFuture.addListener _) when(*, *) onCall { (listener: Runnable, executor: Executor) =>
          listener.run()
        } once()
        (session.executeAsync(_: Statement)) expects(*) returning rsFuture once()

        rsFuture

      }

    }

    "on receiving a CreateUser message" - {

      "it creates a session on the Cluster" in new ConfiguredCQLUserManager with SessionMock {

        val userManager = configuredUserManager(CQLUserManager.Configuration(cluster))

        Await.result(userManager ? CQLUserManager.CreateUser("foo", "bar"), timeout.duration)

      }

      "it sends the CQL CREATE ROLE command to create the Cassandra user" in new ConfiguredCQLUserManager with ExecuteAsyncMock {

        val expectedStatement = new SimpleStatement("CREATE ROLE IF NOT EXISTS 'foo' WITH PASSWORD = 'bar' AND LOGIN = true AND SUPERUSER = true")
        val rsFuture = expectToExecuteAsync(expectedStatement)

        val userManager = configuredUserManager(CQLUserManager.Configuration(cluster))
        Await.result(userManager ? CQLUserManager.CreateUser("foo", "bar", true, true), timeout.duration)

      }

      "with a CQL user configured for the Cluster connection, which" - {

        "has permissions to create a user" - {

          "responds with Success(UserCreated)" in new ConfiguredCQLUserManager with ExecuteAsyncMock {

            val expectedStatement = new SimpleStatement("CREATE ROLE IF NOT EXISTS  'foo' WITH PASSWORD = 'bar' AND LOGIN = true AND SUPERUSER = true")
            val rsFuture = expectToExecuteAsync()

            val userManager = configuredUserManager(CQLUserManager.Configuration(cluster))
            Await.result(userManager ? CQLUserManager.CreateUser("foo", "bar", true, true), timeout.duration) shouldEqual Success(CQLUserManager.UserCreated("foo"))

          }

        }

        "has no permissions to create a user" - {

//          "responds with a Failure of the CreateUser wrapped in a PermissionDenied" in new ConfiguredCQLUserManager with ClusterMock {
//
//          }

        }

      }

    }

    "on receiving an AssignAllPermissionsOnKeyspaceToRole message" - {

    }

    "on receiving a CreateKeyspace message" - {

    }


  }

}
