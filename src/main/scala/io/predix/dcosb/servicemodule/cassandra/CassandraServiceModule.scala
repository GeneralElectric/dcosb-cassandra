package io.predix.dcosb.servicemodule.cassandra

import akka.actor.ActorRef
import io.predix.dcosb.dcos.service.PlanApiClient
import io.predix.dcosb.dcos.{DCOSCommon, DCOSProxy}
import io.predix.dcosb.servicemodule.api.ServiceModule._
import io.predix.dcosb.servicemodule.api.util.BasicServiceModule
import io.predix.dcosb.servicemodule.api.util.BasicServiceModule.OpenServiceBrokerModelSupport.CommonBindResponse
import io.predix.dcosb.servicemodule.api.{
  ServiceModule,
  ServiceModuleConfiguration
}
import spray.json.{
  DefaultJsonProtocol,
  DeserializationException,
  JsObject,
  JsValue
}

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Random, Success, Try}

object CassandraServiceModule {
  import ServiceModuleConfiguration._

  /**
    * This is what we will be looking to receive in the parameters form field from the CF
    * Cloud Controller for create or update instance requests
    */
  case class CassandraProvisionInstanceParameters(nodes: Int,
                                                  cluster_name: String)
      extends OpenServiceBrokerApi.ProvisionInstanceParameters

  case class CassandraUpdateInstanceParameters(nodes: Option[Int])
      extends OpenServiceBrokerApi.UpdateInstanceParameters

  case class CassandraBindParameters(user_name: Option[String])
      extends OpenServiceBrokerApi.BindParameters

  /**
    * This is what we'll be sending along to Cosmos to create or update DC/OS service instances
    */
  case class CassandraPackageOptions(executor: Option[ExecutorInfo] = None,
                                     cassandra: Option[CassandraInfo] = None,
                                     nodes: Option[NodeInfo] = None,
                                     service: ServiceInfo)
      extends DCOSCommon.PackageOptions

  case class ServiceInfo(name: String) extends DCOSCommon.PackageOptionsService

  case class HeapInfo(size: Int, `new`: Int, gc: String)
  case class NodeInfo(count: Option[Int] = None,
                      cpus: Option[Double] = None,
                      mem: Option[Int] = None,
                      disk: Option[Int] = None,
                      heap: Option[HeapInfo] = None)

  case class CassandraInfo(jmx_port: Option[Int],
                           storage_port: Option[Int],
                           ssl_storage_port: Option[Int],
                           native_transport_port: Option[Int],
                           rpc_port: Option[Int],
                           listen_on_broadcast_address: Boolean = true)

  case class ExecutorInfo(api_port: Option[Int])

  trait JsonSupport extends DefaultJsonProtocol {

    val provisionInstanceParametersReader: (Option[JsValue] => Try[
      Option[CassandraProvisionInstanceParameters]]) = {
      case Some(js) =>
        try {
          Success(
            Some(jsonFormat2(CassandraProvisionInstanceParameters).read(js)))
        } catch {
          case e: Throwable => Failure(e)
        }
      case None =>
        Failure(new DeserializationException(
          "No default parameters are allowed when creating a Cassandra cluster"))
    }

    val updateInstanceParametersReader
      : (Option[JsValue] => Try[Option[CassandraUpdateInstanceParameters]]) = {
      case Some(js) =>
        try {
          Success(Some(jsonFormat1(CassandraUpdateInstanceParameters).read(js)))
        } catch {
          case e: Throwable => Failure(e)
        }
      case None =>
        Success(None)
    }

    val bindParametersReader
      : (Option[JsValue] => Try[Option[CassandraBindParameters]]) = {
      case Some(js) =>
        try {
          Success(Some(jsonFormat1(CassandraBindParameters).read(js)))
        } catch {
          case e: Throwable => Failure(e)
        }
      case None =>
        Success(None)
    }

    implicit val cassandraInfoFormat = jsonFormat6(CassandraInfo)
    implicit val executorInfoFormat = jsonFormat1(ExecutorInfo)
    implicit val heapInfoFormat = jsonFormat3(HeapInfo)
    implicit val serviceInfoFormat = jsonFormat1(ServiceInfo)
    implicit val nodeInfoFormat = jsonFormat5(NodeInfo)
    val packageOptionsWriter: (CassandraPackageOptions => JsValue) = {
      jsonFormat4(CassandraPackageOptions).write(_)
    }

    val packageOptionsReader
      : (Option[JsValue] => Try[CassandraPackageOptions]) = {
      case Some(js) =>
        try {
          Success(jsonFormat4(CassandraPackageOptions).read(js))
        } catch {
          case e: Throwable => Failure(e)
        }
      case None =>
        Failure(new DeserializationException(
          "No default package options are allowed when creating a Cassandra cluster"))
    }

  }

  case class ComingSoon() extends Throwable
  case class InvalidRequest() extends Throwable

}

/**
  * Can manage a very basic Cassandra cluster.
  * For learning purposes only.. find the real deal at [[https://github.build.ge.com/predix-data-services/cassandra-dcos cassandra-dcos]]
  */
class CassandraServiceModule
    extends ServiceModule[BasicServiceModule.CommonServiceConfiguration[
      CassandraServiceModule.CassandraProvisionInstanceParameters,
      CassandraServiceModule.CassandraUpdateInstanceParameters,
      CassandraServiceModule.CassandraBindParameters,
      BasicServiceModule.OpenServiceBrokerModelSupport.CommonBindResponse,
      CassandraServiceModule.CassandraPackageOptions]]
    with BasicServiceModule
    with BasicServiceModule.OpenServiceBrokerModelSupport.JsonSupport
    with CassandraServiceModule.JsonSupport {
  import CassandraServiceModule._

  override def getConfiguration(
      serviceId: String): Try[BasicServiceModule.CommonServiceConfiguration[
    CassandraServiceModule.CassandraProvisionInstanceParameters,
    CassandraServiceModule.CassandraUpdateInstanceParameters,
    CassandraServiceModule.CassandraBindParameters,
    BasicServiceModule.OpenServiceBrokerModelSupport.CommonBindResponse,
    CassandraServiceModule.CassandraPackageOptions]] = {

    loadFromTypeSafeConfig[
      CassandraProvisionInstanceParameters,
      CassandraUpdateInstanceParameters,
      CassandraBindParameters,
      BasicServiceModule.OpenServiceBrokerModelSupport.CommonBindResponse,
      CassandraPackageOptions](
      s"dcosb.services.$serviceId",
      provisionInstanceParametersReader,
      updateInstanceParametersReader,
      bindParametersReader,
      commonBindResponseWriter,
      packageOptionsWriter,
      packageOptionsReader
    )

  }

  /**
    * Handle a service instance creation request. For field documentation, see [[https://docs.cloudfoundry.org/services/api.html the CloudFoundry Service Broker API D]]
    *
    * @param organizationGuid
    * @param planId
    * @param serviceId
    * @param spaceGuid
    * @param parameters
    * @return A [[Future]] of an object that will be serialized using [[ServiceModuleConfiguration.dcosService.pkgOptionsWriter]] and sent to DC/OS Cosmos
    */
  override def createServiceInstance(organizationGuid: String,
                                     plan: ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
                                     serviceId: String,
                                     spaceGuid: String,
                                     serviceInstanceId: String,
                                     parameters: Option[T] forSome {
                                       type T <: ProvisionInstanceParameters
                                     }): Future[_ <: PackageOptions] = {

    // our extremely advanced port selection algorithm
    // remove when moving to per task IP addresses
    val ports = Random.shuffle(20000.to(30000)).take(7)

    parameters match {
      case Some(p: CassandraProvisionInstanceParameters) =>
        // TODO verify instance parameters
        Future.successful(
          CassandraPackageOptions(
            nodes = Some(NodeInfo(
              count = Some(p.nodes),
              cpus = Some(2), // << consider plan here...
              mem = Some(8192), // << consider plan here...
              disk = Some(25600), // << consider plan here...
              heap = Some(HeapInfo(`new` = 100, gc = "CMS", size = 7168))
            )),
            service = ServiceInfo(serviceInstanceId),
            cassandra = Some(CassandraInfo(jmx_port = Some(ports(0)),
                                      storage_port = Some(ports(1)),
                                      ssl_storage_port = Some(ports(2)),
                                      native_transport_port = Some(ports(3)),
                                      rpc_port = Some(ports(4)))),
            executor = Some(ExecutorInfo(Some(ports(5))))
          ))

    }

  }

  override def updateServiceInstance(serviceId: String,
                                     serviceInstanceId: String,
                                     plan: Option[ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan],
                                     previousValues: Option[OSB.PreviousValues],
                                     parameters: Option[T] forSome {
                                       type T <: UpdateInstanceParameters
                                     } = None,
                                     endpoints: List[Endpoint],
                                     scheduler: Option[DCOSCommon.Scheduler])
    : Future[_ <: PackageOptions] = {

    parameters match {

      case Some(p: CassandraUpdateInstanceParameters) =>
        Future.successful(
          CassandraPackageOptions(
            service = ServiceInfo(serviceInstanceId),
            nodes = Some(NodeInfo(
              count = p.nodes
            ))
          )
        )

      case None =>
        log.error("Received an update request with a parameters object. This should've been stopped in OpenServiceBrokerApi, as we do not allow a plan update and requests without either a plan id or parameters object are non-updates")
        Future.failed(InvalidRequest())
    }

  }

  override def bindApplicationToServiceInstance(
      serviceId: String,
      plan: ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
      bindResource: Option[OSB.BindResource],
      bindingId: String,
      serviceInstanceId: String,
      parameters: Option[T] forSome {
        type T <: BindParameters
      } = None,
      endpoints: List[Endpoint],
      scheduler: Option[DCOSCommon.Scheduler]): Future[_ <: BindResponse] = {
    import BasicServiceModule.OpenServiceBrokerModelSupport.{
      CommonBindResponse,
      Credentials,
      Node
    }

    log.debug(
      s"Creating binding $bindingId to $serviceInstanceId for application ${for (br <- bindResource) br.appGuid}")
    def endpointsToNodes() = {
      endpoints filter { _.name contains "node" } flatMap { endpoint =>
        for (port <- endpoint.ports; if port.name == Some("node"))
          yield Node(port.address.getHostName, port.address.getPort)

      }
    }

    // TODO: connect to the cassandra cluster and create a functional user
    Future.successful(
      CommonBindResponse(credentials =
                           Some(Credentials("cassandra", "cassandra", endpointsToNodes()))))

  }

  override def unbindApplicationFromServiceInstance(serviceId: String,
                                                    plan: ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
                                                    bindingId: String,
                                                    serviceInstanceId: String,
                                                    endpoints: List[Endpoint],
                                                    scheduler: Option[DCOSCommon.Scheduler])
    : Future[ServiceModule.ApplicationUnboundFromServiceInstance] = {

    log.debug(s"Removing binding $bindingId from $serviceInstanceId")
    // TODO remove application's functional user from cluster, clean up database..
    Future.successful(
      ServiceModule.ApplicationUnboundFromServiceInstance(serviceInstanceId,
                                                          bindingId))
  }

  override def destroyServiceInstance(serviceId: String,
                                      plan: ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
                                      serviceInstanceId: String,
                                      endpoints: List[Endpoint],
                                      scheduler: Option[DCOSCommon.Scheduler])
    : Future[ServiceModule.ServiceInstanceDestroyed] = {

    log.debug(s"Destroying $endpoints")
    // TODO clean up cluster resources
    Future.successful(ServiceModule.ServiceInstanceDestroyed(serviceInstanceId))
  }

  override def lastOperation(serviceId: Option[String],
                             plan: Option[ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan],
                             serviceInstanceId: String,
                             operation: OSB.Operation.Value,
                             endpoints: List[Endpoint],
                             scheduler: Option[DCOSCommon.Scheduler],
                             deployPlan: Try[PlanApiClient.ApiModel.Plan])
    : Future[LastOperationStatus] = {

    val promise = Promise[LastOperationStatus]()

    operation match {

      case OSB.Operation.CREATE | OSB.Operation.DESTROY |
          OSB.Operation.UPDATE =>
        // all 3 of these ^ operations are covered by the "deploy" plan

        log.debug(s"Retrieved deployment plan: $deployPlan")

        deployPlan match {
          case Success(PlanApiClient.ApiModel.Plan("COMPLETE", _, phases)) =>
            promise.success(
              LastOperationStatus(OperationState.SUCCEEDED,
                                  Some(stepMessages(phases).mkString(", "))))

          case Success(PlanApiClient.ApiModel.Plan(status, _, phases))
              if status == "WAITING" || status == "PENDING" || status == "STARTING" || status == "IN_PROGRESS" =>
            operation match {
              case OSB.Operation.DESTROY =>
                promise.success(
                  LastOperationStatus(
                    OperationState.IN_PROGRESS,
                    Some(
                      s"Resources pending clean up: ${countIncompleteSteps(phases, "unreserve-resources")} of ${countSteps(phases, "unreserve-resources")}, tasks pending shutdown: ${countIncompleteSteps(phases, "kill-tasks")} of ${countSteps(phases, "kill-tasks")}, ${stepMessages(phases, "deregister-service")
                        .mkString(", ")}")
                  ))
              case _ =>
                promise.success(
                  LastOperationStatus(
                    OperationState.IN_PROGRESS,
                    Some(stepMessages(phases).mkString(", "))))
            }

          case Success(PlanApiClient.ApiModel.Plan(status, errors, phases))
              if status == "FAILED" =>
            log.error(
              s"Operation $operation failed, errors in plan were: $errors")
            promise.success(
              LastOperationStatus(OperationState.FAILED,
                                  Some(errors.mkString(", "))))

          case Failure(e: PlanApiClient.ServiceNotFound)
              if operation == OSB.Operation.DESTROY =>
            promise.success(
              LastOperationStatus(
                OperationState.SUCCEEDED,
                Some(
                  s"Cluster with id $serviceInstanceId not found (it may have never existed)")))
          case Failure(e: PlanApiClient.SchedulerGone) =>
            promise.success(
              LastOperationStatus(
                OperationState.IN_PROGRESS,
                Some(
                  s"Scheduler for cluster with id $serviceInstanceId is being re/started. Please re-try later for operation details."))
            )

          case Failure(e: Throwable) => promise.failure(e)
        }

        // small helper to extract step messages from node-deploy phases
        def stepMessages(phases: Iterable[PlanApiClient.ApiModel.Phase],
                         phaseName: String = "node-deploy") = {
          phases.filter(_.name == phaseName) flatMap { phase =>
            for (step <- phase.steps)
              yield s"${step.name} -> ${step.status}"

          }
        }

        def countIncompleteSteps(phases: Iterable[PlanApiClient.ApiModel.Phase],
                                 phaseName: String): Int = {
          (phases.filter(_.name == phaseName) flatMap { phase =>
            for (step <- phase.steps; if step.status != "COMPLETE")
              yield step

          }).size
        }

        def countSteps(phases: Iterable[PlanApiClient.ApiModel.Phase],
                       phaseName: String): Int = {
          ((phases.filter(_.name == phaseName)) flatMap { _.steps }).size
        }

    }

    promise.future

  }
}
