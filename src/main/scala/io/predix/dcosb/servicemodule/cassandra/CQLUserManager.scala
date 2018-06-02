package io.predix.dcosb.servicemodule.cassandra

import akka.actor.{Actor, ActorLogging}
import com.datastax.driver.core.exceptions.NoHostAvailableException
import com.datastax.driver.core._
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import io.predix.dcosb.util.actor.ConfiguredActor

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}
import scala.collection.JavaConverters._

object CQLUserManager {

  case class Configuration(cluster: Cluster)

  // handled messages
  trait CQLOP
  case class CreateKeyspace(name: String)
  case class DeleteKeyspace(name: String)
  case class CreateUser(name: String, passwordClear: String, superUser: Boolean = false, login: Boolean = true) extends CQLOP
  case class UpdateUser(name: String, passwordClear: String) extends CQLOP
  case class DeleteUser(name: String) extends CQLOP
  case class AssignAllPermissionsOnKeyspaceToRole(keyspace: String, role: String) extends CQLOP

  // responses
  case class KeyspaceCreated(name: String)
  case class KeyspaceDeleted(name: String)
  case class UserCreated(name: String)
  case class UserUpdated(name: String)
  case class UserDeleted(name: String)
  case class AllPermissionsOnKeyspaceToRoleAssigned(keyspace: String, role: String)

  // exceptions
  case class PermissionDenied(op: CQLOP) extends Throwable
  case class ClusterUnavailable(cause: Throwable) extends Exception(cause) with ConfiguredActor.ActorConfigurationException

  /**
    * Failed to connect with either the default ( cassandra / cassandra ) credentials
    * or the AKSM hashed ( dcosb / h(super-user-for-$serviceInstanceId) ) one
    */
  object ClusterCompromised extends Throwable

  // http://chrisphelps.github.io/scala/2016/01/03/Refactoring-ListenableFutures-to-For-Comprehensions/
  implicit def toScalaFuture[T](lFuture: ListenableFuture[T]): Future[T] = {
    val p = Promise[T]
    Futures.addCallback(lFuture,
      new FutureCallback[T] {
        def onSuccess(result: T) = p.success(result)
        def onFailure(t: Throwable) = p.failure(t)
      })
    p.future
  }

  val name = "user-manager"

}

/**
  * IMPORTANT - make sure you this has it's own (queueing) dispatcher configured
  * to avoid blocking other actors.
  */
class CQLUserManager extends ConfiguredActor[CQLUserManager.Configuration] with Actor with ActorLogging  {
  import CQLUserManager._

  private var cluster:Option[Cluster] = None

  override def configure(configuration: CQLUserManager.Configuration): Future[ConfiguredActor.Configured] = {

    cluster = Some(configuration.cluster)

    try {
      cluster.get.init
      // ^ BLOCKING

      super.configure(configuration)
    }

    catch {
      case e: Throwable => Future.failed(ClusterUnavailable(e))
    }
  }


  override def configuredBehavior = {
    case CreateUser(name, passwordClear, superUser, login) => broadcastFuture(createUser(name, passwordClear, superUser, login), sender())
    case UpdateUser(name, passwordClear) => broadcastFuture(updateUser(name, passwordClear), sender())
    case DeleteUser(name) => broadcastFuture(deleteUser(name), sender())
    case CreateKeyspace(name) => broadcastFuture(createKeyspace(name), sender())
    case DeleteKeyspace(name) => broadcastFuture(deleteKeyspace(name), sender())
    case AssignAllPermissionsOnKeyspaceToRole(keyspace, role) => broadcastFuture(assignAllPermissionsOnKeyspaceToRole(keyspace, role), sender())
  }

  def createUser(name: String, passwordClear: String, superUser: Boolean, login: Boolean): Future[UserCreated] = {

    val promise = Promise[UserCreated]()

    val createUserStatement = new SimpleStatement(s"CREATE ROLE IF NOT EXISTS '$name' WITH PASSWORD = '$passwordClear' AND LOGIN = $login AND SUPERUSER = $superUser")
    executeAsyncOrFailPromise(createUserStatement, (rs:ResultSet) => {

      log.debug(s"User created, result was $rs")
      promise.success(UserCreated(name))

    }, promise)

    promise.future

  }

  def updateUser(name: String, passwordClear: String): Future[UserUpdated] = {

    val promise = Promise[UserUpdated]()

    val updateUserStatement = new SimpleStatement(s"ALTER ROLE '$name' WITH PASSWORD = '$passwordClear'")
    executeAsyncOrFailPromise(updateUserStatement, (rs:ResultSet) => {

      log.debug(s"User updated, result was $rs")
      promise.success(UserUpdated(name))

    }, promise)

    promise.future
  }

  def deleteUser(name: String): Future[UserDeleted] = {

    val promise = Promise[UserDeleted]()

    // clear away permissions
    val revokeStatement = new SimpleStatement(s"REVOKE ALL PERMISSIONS ON ALL KEYSPACES FROM '$name'")
    executeAsyncOrFailPromise(revokeStatement, (rs:ResultSet) => {
      // clear away user
      val dropUserStatement = new SimpleStatement(s"DROP ROLE IF EXISTS '$name'")
      executeAsyncOrFailPromise(dropUserStatement, (rs:ResultSet) => {

        log.debug(s"User deleted, result was $rs")
        promise.success(UserDeleted(name))

      }, promise)

    }, promise)



    promise.future

  }

  def createKeyspace(name: String): Future[KeyspaceCreated] = {
    val promise = Promise[KeyspaceCreated]()

     val createKeyspaceStatement = new SimpleStatement(s"CREATE KEYSPACE IF NOT EXISTS ${keyspaceName(name)} WITH REPLICATION = {" +
       s"'class' : 'SimpleStrategy', 'replication_factor' : 1 }")
     executeAsyncOrFailPromise(createKeyspaceStatement, (rs:ResultSet) => {

       log.debug(s"Keyspace created, result was $rs")
       promise.success(KeyspaceCreated(keyspaceName(name)))

    }, promise)

    promise.future
  }

  def deleteKeyspace(name: String): Future[KeyspaceDeleted] = {
    val promise = Promise[KeyspaceDeleted]()

    val dropKeyspaceStatement = new SimpleStatement(s"DROP KEYSPACE IF EXISTS ${keyspaceName(name)}")
    executeAsyncOrFailPromise(dropKeyspaceStatement, (rs:ResultSet) => {

      log.debug(s"Keyspace dropped, result was $rs")
      promise.success(KeyspaceDeleted(keyspaceName(name)))

    }, promise)

    promise.future

  }

  def assignAllPermissionsOnKeyspaceToRole(keyspace: String, role: String): Future[AllPermissionsOnKeyspaceToRoleAssigned] = {

    val promise = Promise[AllPermissionsOnKeyspaceToRoleAssigned]()

    val createRoleStatement = new SimpleStatement(s"GRANT ALL PERMISSIONS ON KEYSPACE ${keyspaceName(keyspace)} TO '$role'")
    executeAsyncOrFailPromise(createRoleStatement, (rs:ResultSet) => {

        log.debug(s"Role $role was granted all permissions on keyspace $keyspace, result was $rs")
        promise.success(AllPermissionsOnKeyspaceToRoleAssigned(keyspace, role))

      }, promise)

    promise.future

  }

  private def executeAsyncOrFailPromise(statement: Statement, f: (ResultSet => _), promise: Promise[_], closeOnComplete: Boolean = false) = {
    withSessionOrFailPromise((session:Session) => {

      try {
        session.executeAsync(statement) onComplete {
          case Success(rs: ResultSet) =>
            f(rs)
          case Failure(e: NoHostAvailableException) =>
            promise.failure(ClusterUnavailable(e))
          case Failure(e: Throwable) =>
            promise.failure(e)
        }

      } catch {
        case e: Throwable => promise.failure(e)
      }

    }, promise)
  }

  private def withSessionOrFailPromise(f: (Session => _), promise: Promise[_]) = {
    withClusterOrFailPromise((cluster:Cluster) => {
      cluster.connectAsync() onComplete {
        case Success(session) =>
          log.debug(s"Obtained session $session")
          f(session)
        case Failure(e: NoHostAvailableException) => promise.failure(ClusterUnavailable(e))
        case Failure(e: Throwable) => promise.failure(e)
      }
    }, promise)
  }

  private def withClusterOrFailPromise(f: (Cluster => _), promise: Promise[_]) = cluster match {
    case Some(cluster) => f(cluster)
    case None => promise.failure(CQLUserManager.ClusterUnavailable(new IllegalStateException("No Cluster object was present on CQLUserManager")))
  }

  private def keyspaceName(name: String): String = name.replaceAll("[^a-zA-Z0-9]", "_")

  override def postStop(): Unit = {
    cluster match {
      case Some(cluster) =>
        log.info(s"Closing cluster $cluster")
        cluster.closeAsync()
      case _ =>
    }
    super.postStop()
  }

}
