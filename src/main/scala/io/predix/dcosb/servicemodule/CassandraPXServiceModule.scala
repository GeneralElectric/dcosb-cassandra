package io.predix.dcosb.servicemodule

import java.nio.charset.Charset
import java.security.{PrivateKey, Security, Signature}

import com.datastax.driver.core.Cluster
import io.predix.dcosb.dcos.DCOSCommon
import io.predix.dcosb.dcos.service.PlanApiClient.ApiModel
import io.predix.dcosb.servicemodule.api.{ServiceModule, ServiceModuleConfiguration}
import io.predix.dcosb.servicemodule.CassandraPXServiceModuleConfiguration._
import io.predix.dcosb.servicemodule.api.ServiceModule.{PackageOptions, _}
import io.predix.dcosb.servicemodule.api.ServiceModuleConfiguration.OpenServiceBrokerApi
import io.predix.dcosb.servicemodule.api.util.BasicServiceModule
import io.predix.dcosb.servicemodule.cassandra.CQLUserManager

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Random, Success, Try}
import scala.collection.JavaConverters._
import akka.pattern.ask
import io.predix.dcosb.util.actor.ConfiguredActor
import io.predix.dcosb.util.encryption.AkkaKeyStoreManager
import java.util.Base64

import akka.actor.{Actor, ActorRef, ActorRefFactory, PoisonPill}
import com.typesafe.config.Config
import io.predix.dcosb.servicemodule.CassandraPXServiceModuleConfiguration.OSBModel.{BindCredentials, BindResponse, Node}
import io.predix.dcosb.util.AsyncUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider


object CassandraPXServiceModule {

  case class ServiceModuleConfigurationError() extends Throwable

}
class CassandraPXServiceModule
    extends ServiceModule[BasicServiceModule.CommonServiceConfiguration[
      OSBModel.ProvisionInstanceParameters,
      OSBModel.UpdateInstanceParameters,
      OSBModel.BindParameters,
      OSBModel.BindResponse,
      DCOSModel.PackageOptions]]
    with BasicServiceModule
    with DCOSModel.JsonSupport
    with OSBModel.JsonSupport {

  import CassandraPXServiceModule._

  Security.addProvider(new BouncyCastleProvider)

  override def getConfiguration(serviceId: String) = {

    loadFromTypeSafeConfig[OSBModel.ProvisionInstanceParameters,
                           OSBModel.UpdateInstanceParameters,
                           OSBModel.BindParameters,
                           OSBModel.BindResponse,
                           DCOSModel.PackageOptions](
      s"dcosb.services.$serviceId",
      provisionInstanceParametersReader,
      updateInstanceParametersReader,
      bindParametersReader,
      bindResponseWriter,
      packageOptionsWriter,
      packageOptionsReader
    )
  }

  override def createServiceInstance(
      organizationGuid: String,
      plan: OpenServiceBrokerApi.ServicePlan,
      serviceId: String,
      spaceGuid: String,
      serviceInstanceId: String,
      parameters: Option[_ <: ProvisionInstanceParameters])
    : Future[DCOSModel.PackageOptions] = {

    val promise = Promise[DCOSModel.PackageOptions]()

    withActorConfigurationOrFailPromise({ aC:ServiceModule.ActorConfiguration =>

      parameters match {

        case Some(
        provisionInstanceParameters: OSBModel.ProvisionInstanceParameters) =>

          // TODO (much) more robust validation
          if (provisionInstanceParameters.nodes <= 0) {

            promise.failure(MalformedRequest("Node count must be greater than 0"))

          } else {

            plan.resources match {
              case Some(resources) =>
                resources.get("node") match {
                  case Some(
                  nodeResources: ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlanResources) =>
                    val ports = Random.shuffle(20000.to(30000)).take(7)

                    // configure cassandra&service info objects w/ CNI or w/o CNI
                    val (cassandraInfo, serviceInfo) = provisionInstanceParameters.cni match {
                      case Some(true) | None =>
                        (Some(CassandraPXServiceModuleConfiguration.DCOSModel.CassandraInfo(None, None, None, None, None)),
                          CassandraPXServiceModuleConfiguration.DCOSModel
                            .ServiceInfo(serviceInstanceId, Some(true), Some(tConfig.getString(s"dcosb.services.${aC.serviceId}.dcos.package-info.network-name")), None))
                      // TODO configure virtual network name and plugin options

                      case Some(false) =>
                        (Some(
                          CassandraPXServiceModuleConfiguration.DCOSModel
                            .CassandraInfo(Some(ports(0)),
                              Some(ports(1)),
                              Some(ports(2)),
                              Some(ports(3)),
                              Some(ports(4)))),
                          CassandraPXServiceModuleConfiguration.DCOSModel
                            .ServiceInfo(serviceInstanceId, Some(false), None, None))

                    }

                    val pkgOptions =
                      new CassandraPXServiceModuleConfiguration.DCOSModel.PackageOptions(
                        executor = None,
                        cassandra = cassandraInfo,
                        nodes = Some(
                          CassandraPXServiceModuleConfiguration.DCOSModel.NodeInfo(
                            count = Some(provisionInstanceParameters.nodes),
                            cpus = Some(nodeResources.cpu),
                            mem = Some(nodeResources.memoryMB),
                            disk = Some(nodeResources.diskMB),
                            portworx_volume_name = Some(normalize(s"${aC.serviceId}-$serviceInstanceId"))
                          )),
                        service = serviceInfo
                      )

                    promise.success(pkgOptions)

                  case None =>
                    log.error(
                      s"Configuration error - plan ${plan.id} did not contain resource group 'node'")
                    promise.failure(ServiceModuleConfigurationError())
                }
              case None =>
                log.error(
                  s"Configuration error - plan ${plan.id} did not contain any resources")
                promise.failure(ServiceModuleConfigurationError())

            }
          }

        case None =>
      }

    }, promise)


    promise.future

  }

  override def updateServiceInstance(
      serviceId: String,
      serviceInstanceId: String,
      plan: Option[OpenServiceBrokerApi.ServicePlan],
      previousValues: Option[OSB.PreviousValues],
      parameters: Option[_ <: UpdateInstanceParameters],
      endpoints: List[ServiceModule.Endpoint],
      scheduler: Option[DCOSCommon.Scheduler]): Future[_ <: PackageOptions] = {

    import CassandraPXServiceModuleConfiguration._

    val promise = Promise[DCOSModel.PackageOptions]

    withActorConfigurationOrFailPromise({ aC: ServiceModule.ActorConfiguration =>

      parameters match {

        case Some(
        updateInstanceParameters: OSBModel.UpdateInstanceParameters) =>
          log.debug(s"Processing update of instance $serviceInstanceId($scheduler, $endpoints) with parameters $parameters")
          scheduler match {
            case Some(DCOSCommon.Scheduler(env, labels)) =>
              (env.get("NODES"), updateInstanceParameters.nodes) match {
                case (Some(currentNodes), newNodes) if newNodes < currentNodes.toInt =>
                  promise.failure(ServiceModule.MalformedRequest(s"New node count $newNodes is lower than current node count of ${currentNodes.toInt}. Scaling in is not currently supported."))
                case (Some(currentNodes), newNodes) if newNodes == currentNodes.toInt =>
                  promise.failure(ServiceModule.MalformedRequest(s"New node count $newNodes matches current node count of ${currentNodes.toInt}. No changes will be made."))
                case (Some(currentNodes), newNodes) =>
                  // write pkgOptions with new nodecount
                  val pkgOptions =
                    new CassandraPXServiceModuleConfiguration.DCOSModel.PackageOptions(
                      executor = None,
                      cassandra = None,
                      nodes = Some(
                        CassandraPXServiceModuleConfiguration.DCOSModel.NodeInfo(
                          count = Some(newNodes),
                          cpus = None,
                          mem = None,
                          disk = None,
                          portworx_volume_name = None,
                          portworx_volume_options = None
                        )),
                      service = CassandraPXServiceModuleConfiguration.DCOSModel
                        .ServiceInfo(serviceInstanceId, None, None, None)
                    )

                  promise.success(pkgOptions)
              }
            case None =>
              // uh, no scheduler..
              promise.failure(ServiceModule.ServiceInstanceNotFound(serviceInstanceId))
          }

        case p =>
          log.error(s"Received unrecognized update instance parameters $p")
          promise.failure(ServiceModuleConfigurationError())


      }

    }, promise)


    promise.future


  }

  override def bindApplicationToServiceInstance(
      serviceId: String,
      plan: OpenServiceBrokerApi.ServicePlan,
      bindResource: Option[OSB.BindResource],
      bindingId: String,
      serviceInstanceId: String,
      parameters: Option[_ <: BindParameters],
      endpoints: List[ServiceModule.Endpoint],
      scheduler: Option[DCOSCommon.Scheduler]) = {

    val promise = Promise[BindResponse]

    (endpoints, scheduler) match {

      case (e, _) if e.size < 1 =>
        promise.failure(ServiceInstanceNotFound("No Endpoints were found for this instance, framework is either still starting or was not created with this id, please monitor via last operation"))

      case (e, s) =>
        withActorConfigurationOrFailPromise({ aC: ServiceModule.ActorConfiguration =>

          withSuperuserCredentialsOrFailPromise(aC.aksm, tConfig, aC.serviceId, serviceInstanceId, {(defaultCredentials:(String, String), credentials:(String, String)) =>

            withUserManagerOrFailPromise(aC.childMaker, serviceInstanceId, endpoints, defaultCredentials, credentials, {userManager: ActorRef =>

              val(pkAlias, pkStoreId, pkPassword) = boundAccountsKeyLocatorFromConfiguration(tConfig, aC.serviceId)
              withHashedPasswordOrFailPromise(aC.aksm, s"binding:$bindingId,on:$serviceInstanceId", pkAlias, pkStoreId, pkPassword, {passwordClear:String =>
                val boundAccountName = bindingId

                log.debug(s"Received password for $boundAccountName")

                // send create keyspace to user manager
                (userManager ? CQLUserManager.CreateKeyspace(boundAccountName)) onComplete {
                  case Success(Success(CQLUserManager.KeyspaceCreated(keyspace))) =>
                    // send create user message to user manager
                    (userManager ? CQLUserManager.CreateUser(
                      name = boundAccountName,
                      passwordClear = passwordClear,
                      superUser = false,
                      login = true)) onComplete {

                      case Success(Success(CQLUserManager.UserCreated(_))) =>
                        log.info(s"User $boundAccountName created in Cassandra")
                        (userManager ? CQLUserManager.AssignAllPermissionsOnKeyspaceToRole(boundAccountName, boundAccountName)) onComplete {
                          case Success(Success(CQLUserManager.AllPermissionsOnKeyspaceToRoleAssigned(_, _))) =>
                            val nodes = (endpoints filter {_.name.endsWith("server") } flatMap { _.ports filter { port => {(port.name.nonEmpty && port.name.get == "native-client")} } map { port => Node(port.address.getHostName, port.address.getPort) } })
                            userManager ! PoisonPill
                            promise.success(BindResponse(Some(BindCredentials(boundAccountName, passwordClear)), nodes))
                          case Success(Failure(e)) =>
                            log.error(s"Failed to assign all permissions on keyspace $boundAccountName to $boundAccountName: $e")
                            userManager ! PoisonPill
                            promise.failure(e)
                          case Failure(e) =>
                            log.error(s"Exception while trying to assign all permissions on keyspace $boundAccountName to $boundAccountName: $e")
                            userManager ! PoisonPill
                            promise.failure(e)
                        }

                      case Success(Failure(e)) =>
                        log.error(s"Failed to create user $boundAccountName in Cassandra: $e")
                        userManager ! PoisonPill
                        promise.failure(e)

                      case Failure(e) =>
                        log.error(s"Exception while waiting for response to CreateUser for $boundAccountName in Cassandra: $e")
                        userManager ! PoisonPill
                        promise.failure(e)

                    }

                  case Success(Failure(e)) =>
                    userManager ! PoisonPill
                    promise.failure(e)
                  case Failure(e) =>
                    userManager ! PoisonPill
                    promise.failure(e)
                }



              }, promise)

            }, promise)


          }, promise)


        }, promise)

    }

    promise.future
  }

  override def unbindApplicationFromServiceInstance(
      serviceId: String,
      plan: OpenServiceBrokerApi.ServicePlan,
      bindingId: String,
      serviceInstanceId: String,
      endpoints: List[ServiceModule.Endpoint],
      scheduler: Option[DCOSCommon.Scheduler]): Future[ApplicationUnboundFromServiceInstance] = {

    val promise = Promise[ApplicationUnboundFromServiceInstance]()

    (endpoints, scheduler) match {

      case (e, _) if e.size < 1 =>
        promise.failure(ServiceInstanceNotFound("No Endpoints were found for this instance, framework is either still starting or was not created with this id, please monitor via last operation"))

      case (e, s) =>
        withActorConfigurationOrFailPromise({ aC: ServiceModule.ActorConfiguration =>

          withSuperuserCredentialsOrFailPromise(aC.aksm, tConfig, aC.serviceId, serviceInstanceId, { (defaultCredentials:(String, String), credentials:(String, String)) =>

            withUserManagerOrFailPromise(aC.childMaker, serviceInstanceId, endpoints, defaultCredentials, credentials, { userManager: ActorRef =>

              val boundAccountName = bindingId
              (userManager ? CQLUserManager.DeleteUser(boundAccountName)) onComplete {

                case Success(Success(CQLUserManager.UserDeleted(_))) =>
                  userManager ! PoisonPill
                  promise.success(ApplicationUnboundFromServiceInstance(serviceInstanceId, bindingId))
                case Success(Failure(e)) =>
                  userManager ! PoisonPill
                  promise.failure(e)
                case Failure(e) =>
                  userManager ! PoisonPill
                  promise.failure(e)

              }

            }, promise)

          }, promise)


        }, promise)

    }

      promise.future

  }

  override def destroyServiceInstance(serviceId: String,
                                      plan: OpenServiceBrokerApi.ServicePlan,
                                      serviceInstanceId: String,
                                      endpoints: List[ServiceModule.Endpoint],
                                      scheduler: Option[DCOSCommon.Scheduler]) = {

    Future.successful(ServiceInstanceDestroyed(serviceInstanceId))

  }

  override def lastOperation(
      serviceId: Option[String],
      plan: Option[OpenServiceBrokerApi.ServicePlan],
      serviceInstanceId: String,
      operation: OSB.Operation.Value,
      endpoints: List[ServiceModule.Endpoint],
      scheduler: Option[DCOSCommon.Scheduler],
      deployPlan: Try[ApiModel.Plan]): Future[LastOperationStatus] = {

    operationStatusFrom(
      serviceInstanceId,
      operation,
      scheduler,
      deployPlan,
      msgDestroyPending = { phases: Seq[ApiModel.Phase] => s"Tasks pending shutdown: ${PlanProcessors.countIncompleteSteps(phases, "kill-tasks")} of ${PlanProcessors.countSteps(phases, "kill-tasks")}, resources pending pending un-reserve: ${PlanProcessors.countIncompleteSteps(phases, "unreserve-resources")} of ${PlanProcessors.countSteps(phases, "unreserve-resources")}, de-registration: ${PlanProcessors.stepMessages(phases, "deregister-phase")
        .mkString(", ")}" },
      msgCreateUpdatePending = { phases: Seq[ApiModel.Phase] => PlanProcessors.stepMessages(phases, "node-deploy").mkString(", ") },
      msgCreateUpdateComplete = { phases: Seq[ApiModel.Phase] => PlanProcessors.stepMessages(phases, "node-deploy").mkString(", ") }) match {

      case Some(status) => processOperationStatus(
        serviceInstanceId,
        plan,
        status)

    }

  }

  /*
   * Helpers to service methods above
   * ---------------------------------
   *
   */

  /**
    * Get default cassandra super user from configuration, and hash
    * the desired super user's password with private key defined also in configuration
    * @param aksm
    * @param configuration
    * @param serviceId
    * @param serviceInstanceId
    * @param f
    * @param promise
    */
  def withSuperuserCredentialsOrFailPromise(aksm: ActorRef, configuration: Config, serviceId: String, serviceInstanceId: String, f: ((String, String),(String, String)) => _, promise: Promise[_]) = {
    val umPrefix = s"dcosb.services.$serviceId.user-management"
    val defaultCredentials:(String, String) = (configuration.getString(s"$umPrefix.superuser-default.login"), configuration.getString(s"$umPrefix.superuser-default.password"))

    val (pkAlias, pkStoreId, pkPassword) = adminAccountKeyLocatorFromConfiguration(configuration, serviceId)
    withHashedPasswordOrFailPromise(aksm, s"admin,on:$serviceInstanceId", pkAlias, pkStoreId, pkPassword, (passwordClear:String) => {

      f(defaultCredentials, ("admin", passwordClear))

    }, promise)

  }

  def adminAccountKeyLocatorFromConfiguration(configuration: Config, serviceId: String): (String, Option[String], Option[String]) = {
    userManagementKeyLocatorFromConfiguration(configuration, serviceId, "admin-account-pk")
  }

  def boundAccountsKeyLocatorFromConfiguration(configuration: Config, serviceId: String): (String, Option[String], Option[String]) = {
    userManagementKeyLocatorFromConfiguration(configuration, serviceId, "bound-accounts-pk")
  }

  def userManagementKeyLocatorFromConfiguration(configuration: Config, serviceId: String, prefix: String): (String, Option[String], Option[String]) = {
    val pkConfigPrefix = s"dcosb.services.$serviceId.user-management.$prefix"
    (configuration.getString(s"$pkConfigPrefix.alias"),
      configuration.hasPath(s"$pkConfigPrefix.store-id") match { case true => Some(configuration.getString(s"$pkConfigPrefix.store-id")) case _ => None},
      configuration.hasPath(s"$pkConfigPrefix.password") match { case true => Some(configuration.getString(s"$pkConfigPrefix.password")) case _ => None})

  }

  /**
    * Do status processing, i.e. billing, logging, etc..
    * MUST be idempotent
    * @param status
    * @return
    */
  def processOperationStatus(serviceInstanceId: String, plan: Option[OpenServiceBrokerApi.ServicePlan], status: LastOperationStatus): Future[LastOperationStatus] = {

    Future.successful(status)

  }

  /**
    * Read super user credentials from configuration, attempt to connect,
    * if necessary, update credentials in Cassandra from default to actual. If no connection
    * is possible, fail the provided promise
    * @param f
    * @param promise
    */
  def withUserManagerOrFailPromise(childMaker: (ActorRefFactory, Class[_ <: Actor], String) => ActorRef, serviceInstanceId: String, endpoints: List[Endpoint], defaultCredentials: (String, String), credentials: (String, String), f:(ActorRef) => _, promise: Promise[_]) {

    // let's see which of these credentials will yield a CQLUserManager
    AsyncUtils.waitAll(List(credentials, defaultCredentials) map { c =>
      AsyncUtils.contextualize(attemptConnect(childMaker, serviceInstanceId, endpoints, c._1, c._2), c)
    }) onComplete {
      case Success(connectionAttempts) =>
        log.debug(s"Connection attempts completed")
        // find the first successful one..
        connectionAttempts find { _.isSuccess } match {
          case Some(Success((userManager, c))) =>
            if (c == defaultCredentials) {
              // lets create the configured super user and switch to it
              log.info("Default credentials yielded a working connection..")
              createAndConnectSuperuser(childMaker, serviceInstanceId, endpoints, userManager, credentials._1, credentials._2) onComplete {
                case Success(uM) => f(uM)
                case Failure(e) => promise.failure(e)
              }
            } else {
              log.info("Configured credentials yielded a working connection, returning it")
              f(userManager)
            }

          case None =>
            log.error("Failed to establish a connection with default or configured credentials")
            promise.failure(ServiceModuleConfigurationError())
        }
    }


  }

  /**
    * Retrieve the specified private key from AKSM to
    * hash a password for binding/admin accounts
    * @param aksm [[ActorRef]] to the AKSM that holds our keys for hashing
    * @param passwordClear password to hash
    * @param pkAlias
    * @param pkStoreId
    * @param pkPassword
    * @param f
    * @param promise
    */
  def withHashedPasswordOrFailPromise(aksm: ActorRef, passwordClear: String, pkAlias: String, pkStoreId: Option[String], pkPassword: Option[String], f:(String) => _, promise: Promise[_]): Unit = {

    (aksm ? AkkaKeyStoreManager.GetPrivateKey(alias = pkAlias, password = pkPassword, storeId = pkStoreId)) onComplete {
      case Success(Success(pk: PrivateKey)) =>
        log.debug(s"Retrieved $pk from AKSM")
        val signature = Signature.getInstance("SHA256withRSA", "BC")
        signature.initSign(pk)
        signature.update(passwordClear.getBytes(Charset.forName("UTF-8")))

        log.debug(s"Signed $passwordClear with SHA256 over $pk ")
        f(Base64.getEncoder.encodeToString(signature.sign()))

      case Success(Failure(e)) =>
        log.error(s"Failed to retrieve private key $pkAlias: $e")
        promise.failure(ServiceModuleConfigurationError())

      case Failure(e) =>
        log.error(s"Exception while waiting for response to GetPrivateKey $pkAlias: $e")
        promise.failure(ServiceModuleConfigurationError())
    }

  }

  /**
    * Create a datastax [[Cluster]] object and try to configure a [[CQLUserManager]] with it
    * @param childMaker
    * @param endpoints
    * @param username
    * @param passwordClear
    * @return
    */
  def attemptConnect(childMaker: (ActorRefFactory, Class[_ <: Actor], String) => ActorRef, serviceInstanceId: String, endpoints: List[Endpoint], username: String, passwordClear: String): Future[ActorRef] = {

    val promise = Promise[ActorRef]()

    (endpoints filter {_.name.endsWith("server") } flatMap { _.ports filter { port => {(port.name.nonEmpty && port.name.get == "native-client")} } map { _.address } }) match {
      case contactPoints if contactPoints.size > 0 =>
        // prep datastax Cluster object for connection..
        val cluster = Cluster.builder().addContactPoints((contactPoints map {
          _.getAddress
        }).asJavaCollection).withPort(contactPoints(0).getPort)
            .withCredentials(username, passwordClear).build()
        log.info(s"Built Cluster to $contactPoints with credentials $username: $cluster")

        val userManager = childMaker(context, classOf[CQLUserManager], s"${CQLUserManager.name}-${normalize(serviceInstanceId)}-$username")
        log.debug(s"Created CQLUserManager $userManager")
        (userManager ? CQLUserManager.Configuration(cluster)) onComplete {
          case Success(Success(ConfiguredActor.Configured())) =>
            log.info(s"Configured CQLUserManager $userManager")
            promise.success(userManager)
          case Success(Failure(e)) =>
            log.error(s"Failed to configure CQLUserManager: $e")
            userManager ! PoisonPill
            promise.failure(e)
          case Failure(e) =>
            // clear away user manager
            log.error(s"Exception while trying to configure CQLUserManager $e")
            userManager ! PoisonPill
            promise.failure(e)
        }

      case _ =>
        log.error(s"Endpoints returned did not contain any cql ports: $endpoints")
        promise.failure(ServiceModuleConfigurationError())

    }

    promise.future

  }

  /**
    * Create a new super user with the provided credentials,
    * then tear down the user manager, build a new one with [[attemptConnect()]], and return it
    * @param userManager
    * @param name
    * @param passwordClear
    * @return
    */
  def createAndConnectSuperuser(childMaker: (ActorRefFactory, Class[_ <: Actor], String) => ActorRef, serviceInstanceId: String, endpoints: List[Endpoint], defaultCredsUserManager: ActorRef, name: String, passwordClear: String): Future[ActorRef] = {
    log.info(s"Creating and connecting new super user $name")
    val promise = Promise[ActorRef]()
    // run a new userManager
    (defaultCredsUserManager ? CQLUserManager.CreateUser(name = name, passwordClear = passwordClear, superUser = true, login = true)) onComplete {
      case Success(Success(CQLUserManager.UserCreated(_))) =>
        attemptConnect(childMaker, serviceInstanceId, endpoints, name, passwordClear) onComplete {
          case Success(userManager: ActorRef) =>
            // scramble password for cassandra user
            (userManager ? CQLUserManager.UpdateUser("cassandra", Random.alphanumeric.take(10).mkString)) onComplete {
              case Success(Success(CQLUserManager.UserUpdated(_))) =>
                defaultCredsUserManager ! PoisonPill
                promise.success(userManager)

              case Success(Failure(e)) =>
                log.error(s"Failed to scramble cassandra user's password: $e")
                promise.failure(e)
              case Failure(e) =>
                log.error(s"Exception while trying to scramble cassandra user's password: $e")
                promise.failure(e)
            }
          case Failure(e) =>
            log.error(s"Failed to connect with newly created super user credentials: $e")
            promise.failure(e)
        }
      case Success(Failure(e)) =>
        log.error(s"Failed to create new super user $name: $e")
        promise.failure(e)
      case Failure(e) =>
        log.error(s"Exception while trying to create new super user: $e")
        promise.failure(e)

    }

    promise.future
  }

  private def normalize(serviceInstanceId: String): String = {
    """[^a-zA-Z0-9.-]+""".r.replaceAllIn(serviceInstanceId, "__")
  }

  private def hostname(serviceInstanceId: String): String = {
    serviceInstanceId.replaceAll("/","")
  }


}
