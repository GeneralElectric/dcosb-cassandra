package io.predix.dcosb.servicemodule

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import io.predix.dcosb.servicemodule.api.ServiceModuleConfiguration
import io.predix.dcosb.dcos.DCOSCommon
import spray.json.{DefaultJsonProtocol, DeserializationException, JsValue}

import scala.util.{Failure, Success, Try}

object CassandraPXServiceModuleConfiguration {

  object OSBModel {
    case class ProvisionInstanceParameters(nodes: Int, cni: Option[Boolean])
      extends ServiceModuleConfiguration.OpenServiceBrokerApi.ProvisionInstanceParameters

    case class UpdateInstanceParameters(nodes: Int)
    extends ServiceModuleConfiguration.OpenServiceBrokerApi.UpdateInstanceParameters

    case class BindParameters()
      extends ServiceModuleConfiguration.OpenServiceBrokerApi.BindParameters

    case class BindResponse(credentials: Option[BindCredentials], nodes: List[Node])
      extends ServiceModuleConfiguration.OpenServiceBrokerApi.BindResponse

    case class BindCredentials(username: String, password: String)
    case class Node(host: String, port: Int)

    trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

      val provisionInstanceParametersReader
      : (Option[JsValue] => Try[Option[OSBModel.ProvisionInstanceParameters]]) = {
        case Some(js) =>
          try {
            Success(Some(jsonFormat2(OSBModel.ProvisionInstanceParameters).read(js)))
          } catch {
            case e: Throwable => Failure(e)
          }
        case None =>
          Failure(new DeserializationException(
            "No default parameters are allowed when creating an Elastic cluster"))
      }

      val updateInstanceParametersReader
      : (Option[JsValue] => Try[Option[OSBModel.UpdateInstanceParameters]]) = {
        case Some(js) =>
          try {
            Success(Some(jsonFormat1(OSBModel.UpdateInstanceParameters).read(js)))
          } catch {
            case e: Throwable => Failure(e)
          }
        case None =>
          Failure(new DeserializationException(
            "No parameters to update"))
      }

      val bindParametersReader
      : (Option[JsValue] => Try[Option[OSBModel.BindParameters]]) = {
        case Some(js) =>
          try {
            Success(Some(jsonFormat0(OSBModel.BindParameters).read(js)))
          } catch {
            case e: Throwable => Failure(e)
          }
        case None =>
          Success(None)
      }

      implicit val cassandraCredentialsFormat = jsonFormat2(OSBModel.BindCredentials)
      implicit val cassandraNodeFormat = jsonFormat2(OSBModel.Node)
      val bindResponseWriter: (BindResponse => JsValue) = {
        jsonFormat2(BindResponse).write(_)
      }

    }

  }

  object DCOSModel {

    case class PackageOptions(executor: Option[ExecutorInfo] = None,
                                       cassandra: Option[CassandraInfo] = None,
                                       nodes: Option[NodeInfo] = None,
                                       service: ServiceInfo)
      extends DCOSCommon.PackageOptions

    case class ServiceInfo(name: String, virtual_network_enabled: Option[Boolean], virtual_network_name: Option[String], virtual_network_plugin_labels: Option[String]) extends DCOSCommon.PackageOptionsService

    case class HeapInfo(size: Int, `new`: Int, gc: String)
    case class NodeInfo(count: Option[Int] = None,
                        cpus: Option[Double] = None,
                        mem: Option[Int] = None,
                        disk: Option[Int] = None,
                        heap: Option[HeapInfo] = None,
                        portworx_volume_name: Option[String],
                        portworx_volume_options: Option[String] = Some("repl=1"))

    case class CassandraInfo(jmx_port: Option[Int],
                             storage_port: Option[Int],
                             ssl_storage_port: Option[Int],
                             native_transport_port: Option[Int],
                             rpc_port: Option[Int],
                             listen_on_broadcast_address: Boolean = true,
                             authenticator: String = "PasswordAuthenticator",
                             authorizer: String = "CassandraAuthorizer")

    case class ExecutorInfo(api_port: Option[Int])


    trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

      implicit val cassandraInfoFormat = jsonFormat8(DCOSModel.CassandraInfo)
      implicit val executorInfoFormat = jsonFormat1(DCOSModel.ExecutorInfo)
      implicit val heapInfoFormat = jsonFormat3(DCOSModel.HeapInfo)
      implicit val serviceInfoFormat = jsonFormat4(DCOSModel.ServiceInfo)
      implicit val nodeInfoFormat = jsonFormat7(DCOSModel.NodeInfo)
      val packageOptionsWriter: (DCOSModel.PackageOptions => JsValue) = {
        jsonFormat4(DCOSModel.PackageOptions).write(_)
      }

      val packageOptionsReader
      : (Option[JsValue] => Try[DCOSModel.PackageOptions]) = {
        case Some(js) =>
          try {
            Success(jsonFormat4(DCOSModel.PackageOptions).read(js))
          } catch {
            case e: Throwable => Failure(e)
          }
        case None =>
          Failure(new DeserializationException(
            "No default package options are allowed when creating a Cassandra cluster"))
      }

    }

  }


}