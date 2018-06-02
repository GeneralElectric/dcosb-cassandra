package io.predix.dcosb.servicemodule

import java.net.InetSocketAddress
import java.security.{KeyPairGenerator, PrivateKey}
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorRefFactory, Props}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.testkit.{CallingThreadDispatcher, TestActorRef, TestProbe}
import akka.util.Timeout
import akka.pattern._
import com.typesafe.config.ConfigFactory
import io.predix.dcosb.dcos.marathon.MarathonApiClient
import io.predix.dcosb.dcos.{DCOSCommon, DCOSProxy, DCOSProxyMock}
import io.predix.dcosb.mesos.MesosApiClient
import io.predix.dcosb.servicemodule.CassandraPXServiceModuleConfiguration.OSBModel
import io.predix.dcosb.servicemodule.CassandraPXServiceModuleConfiguration.OSBModel.BindResponse
import io.predix.dcosb.servicemodule.api.ServiceModule.MalformedRequest
import io.predix.dcosb.servicemodule.api.{ServiceModule, ServiceModuleConfiguration}
import io.predix.dcosb.servicemodule.cassandra.CQLUserManager
import io.predix.dcosb.util.ActorSuite
import io.predix.dcosb.util.actor.ConfiguredActor
import io.predix.dcosb.util.encryption.AkkaKeyStoreManager
import io.predix.dcosb.util.encryption.AkkaKeyStoreManager.GetPrivateKey
import org.scalatest.Tag

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}
import scala.collection.JavaConversions._

class CassandraPXServiceModuleTest extends ActorSuite {

  // how long we'll wait for any async method to return
  implicit val timeout: Timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))
  val noopActor:ActorRef = TestActorRef(Props(new Actor {
    override def receive = {
      case _ =>
    }
  }).withDispatcher(CallingThreadDispatcher.Id))


  trait HttpClientMock {

    val httpClient = mockFunction[HttpRequest, String, Future[(HttpResponse, String)]]

    val httpClientFactory
    : (DCOSCommon.Connection => (HttpRequest, String) => Future[(HttpResponse,
      String)]) = {
      address =>
        httpClient

    }
  }

  "A Configured CassandraPXServiceModule" - {

    trait CassandraPXTestServiceModule extends HttpClientMock {
      val serviceId:String = "cassandra-test"
      val dcosProxy:ActorRef
      val aksm:ActorRef
      val userManager:ActorRef

      def cassandraServiceModule: TestActorRef[CassandraPXServiceModule] = {

        val cassandraServiceModule:TestActorRef[CassandraPXServiceModule] = TestActorRef(
          Props(classOf[CassandraPXServiceModule])
            .withDispatcher(CallingThreadDispatcher.Id))

        val childMaker: (ActorRefFactory, Class[_ <: Actor], String) => ActorRef = {
          (factory, actorClass, name) =>
            actorClass match {
              case c: Class[_] if c == classOf[DCOSProxy] => dcosProxy
              case c: Class[_] if c == classOf[CQLUserManager] => userManager
              case _ => noopActor

            }
        }

        // send actor configuration

        Await.result(cassandraServiceModule ? ServiceModule.ActorConfiguration(
          childMaker,
          httpClientFactory,
          aksm,
          serviceId), timeout.duration) shouldEqual Success(ConfiguredActor.Configured())

        cassandraServiceModule.unwatch(dcosProxy)
        cassandraServiceModule

      }


    }

    "upon invocation of it's underlying actor's" - {

      trait PlanStub {

        val planStub =
          new ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan(
            id = "some-plan",
            name = "Some Plan",
            description = "This is Some Plan",
            metadata = ServiceModuleConfiguration.OpenServiceBrokerApi
              .ServicePlanMetadata(bullets = List.empty,
                                   costs = List.empty,
                                   displayName = "Some Plan"),
            resources = Some(
              Map("node" -> ServiceModuleConfiguration.OpenServiceBrokerApi
                .ServicePlanResources(cpu = 2, memoryMB = 1024, diskMB = 2048)))
          )

      }


      "createServiceInstance method" - {

        "with valid parameters" - {

          "it translates an incoming node count in ProvisionInstanceParameters to a node count in PackageOptions" in new CassandraPXTestServiceModule
          with PlanStub {
            override val dcosProxy = DCOSProxyMock()
            override val userManager = noopActor
            override val aksm = noopActor

            val sm = cassandraServiceModule.underlyingActor
              .asInstanceOf[CassandraPXServiceModule]

            Await.result(
              sm.createServiceInstance(
                organizationGuid = "someOrgGuid",
                plan = planStub,
                serviceId = "cassandra",
                spaceGuid = "someSpaceGuid",
                serviceInstanceId = "test-instance",
                parameters = Some(CassandraPXServiceModuleConfiguration.OSBModel
                  .ProvisionInstanceParameters(nodes = 9, cni = None))
              ),
              timeout.duration
            ) should matchPattern {
              case CassandraPXServiceModuleConfiguration.DCOSModel
                    .PackageOptions(
                    _,
                    _,
                    Some(CassandraPXServiceModuleConfiguration.DCOSModel
                      .NodeInfo(Some(9), _, _, _, _, _, _)),
                    _) =>
            }

          }

          "it translates an incoming service instance id to a service name in PackageOptions" in new CassandraPXTestServiceModule with PlanStub {
            override val dcosProxy = DCOSProxyMock()
            override val userManager = noopActor
            override val aksm = noopActor

            val sm = cassandraServiceModule.underlyingActor
              .asInstanceOf[CassandraPXServiceModule]

            Await.result(
              sm.createServiceInstance(
                organizationGuid = "someOrgGuid",
                plan = planStub,
                serviceId = "cassandra",
                spaceGuid = "someSpaceGuid",
                serviceInstanceId = "test-instance",
                parameters = Some(CassandraPXServiceModuleConfiguration.OSBModel
                  .ProvisionInstanceParameters(nodes = 9, cni = None))
              ),
              timeout.duration
            ) should matchPattern {
              case CassandraPXServiceModuleConfiguration.DCOSModel
              .PackageOptions(
              _,
              _,
              _,
              CassandraPXServiceModuleConfiguration.DCOSModel.ServiceInfo("test-instance", _, _, _)) =>
            }

          }

          "it configures resources (cpu, memory, disk) in PackageOptions according to the Plan object received" in new CassandraPXTestServiceModule with PlanStub {
            override val dcosProxy = DCOSProxyMock()
            override val userManager = noopActor
            override val aksm = noopActor

            val sm = cassandraServiceModule.underlyingActor
              .asInstanceOf[CassandraPXServiceModule]

            Await.result(
              sm.createServiceInstance(
                organizationGuid = "someOrgGuid",
                plan = planStub,
                serviceId = "cassandra",
                spaceGuid = "someSpaceGuid",
                serviceInstanceId = "test-instance",
                parameters = Some(CassandraPXServiceModuleConfiguration.OSBModel
                  .ProvisionInstanceParameters(nodes = 9, cni = None))
              ),
              timeout.duration
            ) should matchPattern {
              case CassandraPXServiceModuleConfiguration.DCOSModel
              .PackageOptions(
              _,
              _,
              Some(CassandraPXServiceModuleConfiguration.DCOSModel
              .NodeInfo(_, Some(2), Some(1024), Some(2048), _, _, _)),
              _) =>
            }

          }

          "it configures port numbers in PackageOptions when cni is set to false in ProvisionInstanceParameters" in new CassandraPXTestServiceModule with PlanStub {
            override val dcosProxy = DCOSProxyMock()
            override val userManager = noopActor
            override val aksm = noopActor

            val sm = cassandraServiceModule.underlyingActor
              .asInstanceOf[CassandraPXServiceModule]

            Await.result(
              sm.createServiceInstance(
                organizationGuid = "someOrgGuid",
                plan = planStub,
                serviceId = "cassandra",
                spaceGuid = "someSpaceGuid",
                serviceInstanceId = "test-instance",
                parameters = Some(CassandraPXServiceModuleConfiguration.OSBModel
                  .ProvisionInstanceParameters(nodes = 9, cni = Some(false)))
              ),
              timeout.duration
            ) should matchPattern {
              case CassandraPXServiceModuleConfiguration.DCOSModel
              .PackageOptions(
              _,
              Some(CassandraPXServiceModuleConfiguration.DCOSModel
                .CassandraInfo(Some(_: Int), Some(_: Int), Some(_: Int), Some(_: Int), Some(_: Int), _, _, _)),
              _,
              _) =>
            }

          }

          "it configures no port numbers in PackageOptions when cni is set to true in ProvisionInstanceParameters" in new CassandraPXTestServiceModule with PlanStub {
            override val dcosProxy = DCOSProxyMock()
            override val userManager = noopActor
            override val aksm = noopActor

            val sm = cassandraServiceModule.underlyingActor
              .asInstanceOf[CassandraPXServiceModule]

            Await.result(
              sm.createServiceInstance(
                organizationGuid = "someOrgGuid",
                plan = planStub,
                serviceId = "cassandra",
                spaceGuid = "someSpaceGuid",
                serviceInstanceId = "test-instance",
                parameters = Some(CassandraPXServiceModuleConfiguration.OSBModel
                  .ProvisionInstanceParameters(nodes = 9, cni = Some(true)))
              ),
              timeout.duration
            ) should matchPattern {
              case CassandraPXServiceModuleConfiguration.DCOSModel
              .PackageOptions(
              _,
              Some(CassandraPXServiceModuleConfiguration.DCOSModel
                .CassandraInfo(None, None, None, None, None, _, _, _)),
              _,
              _) =>
              case CassandraPXServiceModuleConfiguration.DCOSModel
              .PackageOptions(
              _,
              None,
              _,
              _) =>
            }

          }

          "it sets virtual_network_enabled to true in PackageOptions when cni is set to true in ProvisionInstanceParameters" in new CassandraPXTestServiceModule with PlanStub {
            override val dcosProxy = DCOSProxyMock()
            override val userManager = noopActor
            override val aksm = noopActor

            val sm = cassandraServiceModule.underlyingActor
              .asInstanceOf[CassandraPXServiceModule]

            Await.result(
              sm.createServiceInstance(
                organizationGuid = "someOrgGuid",
                plan = planStub,
                serviceId = "cassandra",
                spaceGuid = "someSpaceGuid",
                serviceInstanceId = "test-instance",
                parameters = Some(CassandraPXServiceModuleConfiguration.OSBModel
                  .ProvisionInstanceParameters(nodes = 9, cni = Some(true)))
              ),
              timeout.duration
            ) should matchPattern {
              case CassandraPXServiceModuleConfiguration.DCOSModel
              .PackageOptions(
              _,
              _,
              _,
              CassandraPXServiceModuleConfiguration.DCOSModel.ServiceInfo(_, Some(true), _, _)) =>
              case CassandraPXServiceModuleConfiguration.DCOSModel
              .PackageOptions(
              _,
              None,
              _,
              _) =>
            }

          }

          "from additional (non-object mapped) typesafe configuration, it" - {

            "sets portworx_volume_name on PackageOptions" in new CassandraPXTestServiceModule with PlanStub {
              override val dcosProxy = DCOSProxyMock()
              override val userManager = noopActor
              override val aksm = noopActor

              val sm = cassandraServiceModule.underlyingActor
                .asInstanceOf[CassandraPXServiceModule]

              Await.result(
                sm.createServiceInstance(
                  organizationGuid = "someOrgGuid",
                  plan = planStub,
                  serviceId = "cassandra",
                  spaceGuid = "someSpaceGuid",
                  serviceInstanceId = "test-instance",
                  parameters = Some(CassandraPXServiceModuleConfiguration.OSBModel
                    .ProvisionInstanceParameters(nodes = 9, cni = Some(true)))
                ),
                timeout.duration
              ) should matchPattern {
                case CassandraPXServiceModuleConfiguration.DCOSModel
                .PackageOptions(
                _,
                _,
                _,
                CassandraPXServiceModuleConfiguration.DCOSModel.ServiceInfo(_, _, Some("cassandra-volume"), _)) =>
                case CassandraPXServiceModuleConfiguration.DCOSModel
                .PackageOptions(
                _,
                _,
                _,
                _) =>
              }

            }

          }

        }

        "with parameters invalid in that" - {
          import CassandraPXServiceModuleConfiguration._

          "provision instance parameters are invalid" - {

            trait MalformedProvisionInstanceParameters extends CassandraPXTestServiceModule with PlanStub {
              override val dcosProxy = DCOSProxyMock()
              override val userManager = noopActor
              override val aksm = noopActor

              val params: OSBModel.ProvisionInstanceParameters
              val message: String

              def validateMalformedRequestExceptionThrown(cassandraServiceModule: TestActorRef[CassandraPXServiceModule]) = {
                val sm = cassandraServiceModule.underlyingActor
                  .asInstanceOf[CassandraPXServiceModule]

                val malformedRequestEx: ServiceModule.MalformedRequest = the[ServiceModule.MalformedRequest] thrownBy {

                  Await.result(
                    sm.createServiceInstance(
                      organizationGuid = "someOrgGuid",
                      plan = planStub,
                      serviceId = "cassandra",
                      spaceGuid = "someSpaceGuid",
                      serviceInstanceId = "test-instance",
                      parameters = Some(params)
                    ),
                    timeout.duration
                  )

                }

                malformedRequestEx.message shouldEqual message

              }

            }

            "node count on ProvisionInstanceParameters is not > 0, specifically" - {

              "node count is < 0" - {

                "it returns a MalformedRequestException in a failed Future, describing the problem" in new MalformedProvisionInstanceParameters {
                  override val params = OSBModel.ProvisionInstanceParameters(nodes = -1, cni = None)
                  override val message = "Node count must be greater than 0"

                  validateMalformedRequestExceptionThrown(cassandraServiceModule)

                }

              }

              "node count is 0" - {

                "it returns a MalformedRequestException in a failed Future, describing the problem" in new MalformedProvisionInstanceParameters {
                  override val params = OSBModel.ProvisionInstanceParameters(nodes = 0, cni = None)
                  override val message = "Node count must be greater than 0"

                  validateMalformedRequestExceptionThrown(cassandraServiceModule)

                }

              }

            }

          }



        }

      }

      "updateServiceInstance method" - {

        "with valid parameters" - {

          "it translates an incoming node count in ProvisionInstanceParameters to a node count in PackageOptions" in new CassandraPXTestServiceModule
            with PlanStub {
            override val dcosProxy = DCOSProxyMock()
            override val userManager = noopActor
            override val aksm = noopActor

            val sm = cassandraServiceModule.underlyingActor
              .asInstanceOf[CassandraPXServiceModule]

            Await.result(
              sm.updateServiceInstance(
                plan = None,
                previousValues = None,
                serviceId = "cassandra",
                serviceInstanceId = "test-instance",
                parameters = Some(OSBModel.UpdateInstanceParameters(nodes = 9)),
                endpoints = List.empty,
                scheduler = Some(DCOSCommon.Scheduler(Map("NODES" -> "3"), Map.empty))
              ),
              timeout.duration
            ) should matchPattern {
              case CassandraPXServiceModuleConfiguration.DCOSModel
              .PackageOptions(
              _,
              _,
              Some(CassandraPXServiceModuleConfiguration.DCOSModel
              .NodeInfo(Some(9), _, _, _, _, _, _)),
              _) =>
            }

          }

        }

        "with parameters invalid in that" - {
          import CassandraPXServiceModuleConfiguration._

          "the service instance id refers to a service instance without a scheduler" - {

            "it returns ServiceInstanceNotFound in a failed Future" in new CassandraPXTestServiceModule with PlanStub {
              override val dcosProxy = DCOSProxyMock()
              val userManager = noopActor
              override val aksm = noopActor

              val sm = cassandraServiceModule.underlyingActor
                .asInstanceOf[CassandraPXServiceModule]

              val serviceInstanceNotFoundEx: ServiceModule.ServiceInstanceNotFound = the[ServiceModule.ServiceInstanceNotFound] thrownBy {

                Await.result(

                  sm.updateServiceInstance(
                    plan = None,
                    previousValues = None,
                    serviceId = "cassandra",
                    serviceInstanceId = "test-instance",
                    parameters = Some(OSBModel.UpdateInstanceParameters(nodes = 9)),
                    endpoints = List.empty,
                    scheduler = None
                  ),
                  timeout.duration
                )

              }

              serviceInstanceNotFoundEx.serviceInstanceId shouldEqual "test-instance"

            }

          }

          "the update service instance parameters object" - {

            trait MalformedUpdateInstanceParameters extends CassandraPXTestServiceModule with PlanStub {

              val params: OSBModel.UpdateInstanceParameters
              val message: String

              def validateMalformedRequestExceptionThrown(cassandraServiceModule: TestActorRef[CassandraPXServiceModule], scheduler: Option[DCOSCommon.Scheduler] = None) = {
                val sm = cassandraServiceModule.underlyingActor
                  .asInstanceOf[CassandraPXServiceModule]

                val malformedRequestEx: ServiceModule.MalformedRequest = the[ServiceModule.MalformedRequest] thrownBy {

                  Await.result(

                    sm.updateServiceInstance(
                      plan = None,
                      previousValues = None,
                      serviceId = "cassandra",
                      serviceInstanceId = "test-instance",
                      parameters = Some(params),
                      endpoints = List.empty,
                      scheduler = scheduler
                    ),
                    timeout.duration
                  )

                }

                malformedRequestEx.message shouldEqual message

              }

            }

            "has a node count lower than the current node count ( scale in attempt )" - {

              "it returns a MalformedRequestException in a failed Future, describing the problem" in new MalformedUpdateInstanceParameters {
                override val dcosProxy = DCOSProxyMock()
                val userManager = noopActor
                override val aksm = noopActor

                override val params = OSBModel.UpdateInstanceParameters(nodes = 1)
                override val message = "New node count 1 is lower than current node count of 3. Scaling in is not currently supported."

                validateMalformedRequestExceptionThrown(cassandraServiceModule, Some(DCOSCommon.Scheduler(Map("NODES" -> "3"), Map.empty)))



              }

            }

          }

        }


      }

      "bindApplicationToServiceInstance method" - {

        trait BindingStubs {

          val dcosProxy = DCOSProxyMock()

          val testPrivateKey: PrivateKey = {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.generateKeyPair().getPrivate

          }

          val aksm = TestActorRef(Props(new Actor {
            override def receive: Receive = {
              case _: AkkaKeyStoreManager.Configuration.ActorConfiguration => sender() ! Success(ConfiguredActor.Configured())
              case _: AkkaKeyStoreManager.GetPrivateKey => sender() ! Success(testPrivateKey)
            }
          }).withDispatcher(CallingThreadDispatcher.Id))
        }

        "with valid parameters" - {


          "with the configured default credentials yielding a successful connection" - new CassandraPXTestServiceModule with BindingStubs  {

            override val userManager = TestActorRef(Props(new Actor {
              override def receive: Receive = {
                case CQLUserManager.Configuration(cluster) => sender() ! Success(ConfiguredActor.Configured())
                case CQLUserManager.CreateUser(name, _, _, _) => sender() ! Success(CQLUserManager.UserCreated(name))
              }
            }).withDispatcher(CallingThreadDispatcher.Id))

            "it creates and configures a CQLUserManager actor to update credentials from default to desired" +
              "and uses them to create the binding user" taggedAs(Tag("bind")) in {

              // TODO it is not currently possible to determine what credentials were set in a Cluster.Builder..


            }

          }

          "with the configured credentials yielding a successful connection" - new CassandraPXTestServiceModule with BindingStubs {

            // TODO it is not currently possible to determine what credentials were set in a Cluster.Builder..

            override val userManager = TestActorRef(Props(new Actor {
              override def receive: Receive = {
                case CQLUserManager.Configuration(cluster) => sender() ! Success(ConfiguredActor.Configured())
                case CQLUserManager.CreateUser(name, _, _, _) => sender() ! Success(CQLUserManager.UserCreated(name))
                case CQLUserManager.CreateKeyspace(name) => sender() ! Success(CQLUserManager.KeyspaceCreated(name))
                case CQLUserManager.AssignAllPermissionsOnKeyspaceToRole(keyspace, name) => sender() ! Success(CQLUserManager.AllPermissionsOnKeyspaceToRoleAssigned(keyspace, name))
              }
            }).withDispatcher(CallingThreadDispatcher.Id))

            "it creates and configures a CQLUserManager actor and uses it to create the binding user" taggedAs(Tag("bind")) in {

              val sm = cassandraServiceModule.underlyingActor
                .asInstanceOf[CassandraPXServiceModule]

              Await.result(sm.bindApplicationToServiceInstance(
                "cassandra",
                stub[ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan],
                Some(ServiceModule.OSB.BindResource("app-foo")),
                "bindingId",
                "test-instance",
                None,
                List(ServiceModule.Endpoint("node-0-server", List(ServiceModule.Port(Some("native-client"), new InetSocketAddress(9042))))),
                None
              ), timeout.duration) should matchPattern {
                case _: BindResponse =>
              }

            }

          }


        }

        "with parameters invalid in that" - {

          "the endpoints list" - {

            "is empty" - {

              "throws ServiceInstanceNotFound" in new CassandraPXTestServiceModule {

                override val dcosProxy = DCOSProxyMock()
                override val userManager = noopActor
                override val aksm = noopActor

                val sm = cassandraServiceModule.underlyingActor
                  .asInstanceOf[CassandraPXServiceModule]

                val malformedRequestEx: ServiceModule.ServiceInstanceNotFound = the[ServiceModule.ServiceInstanceNotFound] thrownBy {

                  Await.result(sm.bindApplicationToServiceInstance(
                    "cassandra",
                    stub[ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan],
                    Some(ServiceModule.OSB.BindResource("app-foo")),
                    "bindingId",
                    "test-instance",
                    None,
                    List.empty,
                    None
                  ), timeout.duration)

                }


              }

            }

            trait VerifyServiceModuleConfigurationError extends CassandraPXTestServiceModule with BindingStubs {
              override val userManager = noopActor

              val sm = cassandraServiceModule.underlyingActor
                .asInstanceOf[CassandraPXServiceModule]

              def serviceModuleConfigurationErrorThrownFor(endpoints: List[ServiceModule.Endpoint]) = {

                the[CassandraPXServiceModule.ServiceModuleConfigurationError] thrownBy {

                  Await.result(sm.bindApplicationToServiceInstance(
                    "cassandra",
                    stub[ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan],
                    Some(ServiceModule.OSB.BindResource("app-foo")),
                    "bindingId",
                    "test-instance",
                    None,
                    endpoints,
                    None
                  ), timeout.duration)

                }

              }

            }

            "does not contain any '*server' named endpoints" - {

              "throws ServiceModuleConfigurationError" in new VerifyServiceModuleConfigurationError {

                serviceModuleConfigurationErrorThrownFor(List(ServiceModule.Endpoint("foo", List.empty)))

              }

            }

            "contains '*server' named endpoints but does not contain 'node' named ports" - {

              "throws ServiceModuleConfigurationError" in new VerifyServiceModuleConfigurationError {

                serviceModuleConfigurationErrorThrownFor(List(ServiceModule.Endpoint("node-0-server", List(ServiceModule.Port(Some("jmx"), stub[InetSocketAddress])))))

              }

            }

          }

        }

      }

    }

  }

}
