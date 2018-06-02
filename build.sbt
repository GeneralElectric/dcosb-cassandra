import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.io.File

import org.fusesource.scalate.TemplateEngine
import sbt.Keys.name

import Utils._


enablePlugins(DockerPlugin)

// DC/OS connection
lazy val dcosHost = settingKey[String]("DC/OS API host")
lazy val dcosPort = settingKey[Int]("DC/OS API port")
lazy val dcosPrincipal = settingKey[String]("DC/OS principal for the service broker to invoke APIs with")
lazy val dcosPrincipalPrivateKeyAlias = settingKey[String]("DC/OS principal's private key's alias in the store")
lazy val dcosPrincipalPrivateKeyPw = settingKey[String]("DC/OS principal's private key's password ")

// OSB API Authentication
lazy val apiUser = settingKey[String]("Username configured for access to the Service Broker API")
lazy val apiPassword = settingKey[String]("Password hash configured for access to the Service Broker API")

// Broker config
lazy val brokerListenAddress = settingKey[String]("Address the broker should listen on")
lazy val brokerListenPort = settingKey[Int]("Port the broker should bind to")

lazy val brokerKeyStore = settingKey[File]("Broker key store file")
lazy val brokerKeyStoreType = settingKey[String]("Broker key store type ( currently only \"local\" is implemented )")
lazy val brokerKeyStoreUri = settingKey[String]("Broker key store URI")
lazy val brokerKeyStorePw = settingKey[String]("Broker key store password")
lazy val brokerTrustStore = settingKey[File]("Broker trust store file")
lazy val brokerTrustStoreType = settingKey[String]("Broker trust store type")
lazy val brokerTrustStoreClasspath = settingKey[Boolean]("If the trust store is on the classpath or not")
lazy val brokerTrustStorePw = settingKey[String]("Broker trust store password")
lazy val brokerTlsPrivateKeyAndCertStore = settingKey[String]("Store id that contains the private/public certs for TLS")
lazy val brokerTlsPrivateKeyAndCertAlias = settingKey[String]("Alias of the private key & cert used for TLS")
lazy val brokerTlsPrivateKeyPw = settingKey[String]("Password of the private key used for TLS")

// Marathon descriptor parsing
lazy val dockerRegistry = settingKey[String]("Docker registry Marathon should pull the image from")
lazy val dockerOrg = settingKey[String]("Organization in the Docker registry Marathon should pull the image from")
lazy val marathonTemplatePath = settingKey[String]("Location of the Marathon json ssp template which is used to submit cassandra-dcos to Marathon")
lazy val marathonJsonPath = settingKey[String]("Path (including file name) where the marathon descriptor will be written to")

lazy val parseMarathon = taskKey[Path]("Parse a Marathon json ssp template with settings from the build")

// Misc
lazy val additionalServiceBrokerOpts = settingKey[String]("Any addition options to the Service Broker daemon process")
lazy val applicationConfigurationPath = settingKey[File]("application.conf overrides")
lazy val serviceVhost = settingKey[String]("Cassandra broker service vhost to include in marathon json")
lazy val userManagementPrivateKeyAlias = settingKey[String]("")
lazy val userManagementPrivateKeyPw = settingKey[String]("")

// Tasks
lazy val placeTrustStore = taskKey[File]("Copies trust store file from either the setting key \"brokerTrustStore\" or the io.predix.dcosb:service-broker artifact for distribution")
lazy val placeKeyStore = taskKey[File]("Copies key store file ( w/ TLS cert/priv key ) from either the setting key \"brokerKeyStore\" or the io.predix.dcosb:service-broker artifact for distribution")
lazy val dumpDeployInfo = taskKey[Unit]("Dumps some basic build information in a parseable format for the deploy script to load")

dcosHost := sys.env.getOrElse("DCOS_HOST", "master.mesos")
dcosPort := sys.env.getOrElse("DCOS_PORT", "443").toInt
dcosPrincipal := sys.env.getOrElse("DCOS_PRINCIPAL", "cassandra-broker")
dcosPrincipalPrivateKeyAlias := sys.env.getOrElse("DCOS_PRINCIPAL_PK_ALIAS", "dcos-principal")
dcosPrincipalPrivateKeyPw := sys.env.getOrElse("DCOS_PRINCIPAL_PK_PSW", "8BWTL0ie")

apiUser := sys.env.getOrElse("OSB_API_USR", "apiuser")
apiPassword := sys.env.getOrElse("OSB_API_PSW", "2cd8a28b6e8b1547f58861f77dba1f72f38a54b1a97a9faaad34424b57a96aad")

brokerListenAddress := sys.env.getOrElse("BROKER_LISTEN_ADDRESS", "0.0.0.0")
brokerListenPort := sys.env.getOrElse("BROKER_LISTEN_PORT", "8080").toInt

brokerKeyStore := new File(sys.env.getOrElse("BROKER_KEY_STORE", "src/main/resources/cassandra.p12"))
brokerKeyStoreType := sys.env.getOrElse("BROKER_KEY_STORE_TYPE", "local")
brokerKeyStoreUri := sys.env.getOrElse("BROKER_KEY_STORE_URI", "pkcs12:cassandra.p12")
brokerKeyStorePw := sys.env.getOrElse("BROKER_KEY_STORE_PSW", "8BWTL0ie")
brokerTrustStore := new File(sys.env.getOrElse("BROKER_TRUST_STORE", ""))
brokerTrustStoreType := sys.env.getOrElse("BROKER_TRUST_STORE_TYPE", "JKS")
brokerTrustStoreClasspath := sys.env.getOrElse("BROKER_TRUST_STORE_CLASSPATH", "true").toBoolean
brokerTrustStorePw := sys.env.getOrElse("BROKER_TRUST_STORE_PSW", "swim87'bleat")
brokerTlsPrivateKeyAndCertStore := sys.env.getOrElse("BROKER_TLS_PK_CERT_STORE", "broker")
brokerTlsPrivateKeyAndCertAlias := sys.env.getOrElse("BROKER_TLS_PK_CERT_ALIAS", "dcosb.marathon.mesos")
brokerTlsPrivateKeyPw := sys.env.getOrElse("BROKER_TLS_PK_PSW", "swim87'bleat")

dockerRegistry := sys.env.getOrElse("DTR", "dtr.predix.io")
dockerOrg := sys.env.getOrElse("DTR_ORG", "dataservices-beta")
marathonTemplatePath := s"${baseDirectory.value}/src/main/resources/cassandra-broker.json.ssp"
marathonJsonPath := s"${target.value}/marathon.json"

serviceVhost := sys.env.getOrElse("SERVICE_VHOST", "cassandra.dcosb.run.dcos.aws-usw02-dev.ice.predix.io")

additionalServiceBrokerOpts := sys.env.getOrElse("ADDITIONAL_OPTS", "")
applicationConfigurationPath := new File(sys.env.getOrElse("APPLICATION_CONFIGURATION_PATH", s"${baseDirectory.value}/src/main/resources/application.conf"))

userManagementPrivateKeyAlias := sys.env.getOrElse("USER_MGMT_PK_ALIAS", "dcos-principal")
userManagementPrivateKeyPw := sys.env.getOrElse("USER_MGMT_PK_PSW", "8BWTL0ie")

placeTrustStore := {
  val dest = (target in Compile).value
  val classpath = (dependencyClasspath in Compile).value
  val log = sLog.value

  brokerTrustStore.value match {
    case f:File if (f.exists == false || f.canRead == false) =>
      log.warn(s"Trust store $f in setting key brokerTrustStore does not exist or is not readable, falling back to one provided in io.predix.dcosb:service-broker. This may not be what you want packaged up")
      copyResourceFromJarOnClasspath(classpath, "service-broker", "broker-trust.jks", dest)
    case f:File =>
      f
  }

}

placeKeyStore := {
  val dest = (target in Compile).value
  val classpath = (dependencyClasspath in Compile).value
  val log = sLog.value

  brokerKeyStore.value match {
    case f:File if (f.exists == false || f.canRead == false) =>
      log.warn(s"Key store $f in setting key brokerKeyStore does not exist or is not readable, falling back to one provided in io.predix.dcosb:service-broker. This may not be what you want packaged up")
      copyResourceFromJarOnClasspath(classpath, "service-broker", "broker.p12", dest)
    case f:File =>
      f
  }
}

name := "cassandra-dcos"
organization := "io.predix.dcosb"
version := "1.0-SNAPSHOT"
sbtVersion := "0.13.16"
scalaVersion := "2.12.3"

scalacOptions := Seq("-target:jvm-1.8", "-unchecked", "-deprecation", "-encoding", "utf8")

resolvers += "DC/OS Open Service Broker Snapshots" at "https://devcloud.swcoe.ge.com/artifactory/FGGPD-SNAPSHOT"
resolvers += "DC/OS Open Service Broker Releases" at "https://devcloud.swcoe.ge.com/artifactory/FGGPD"

lazy val akkaDependencies: Seq[sbt.ModuleID] = Seq(
  "com.typesafe.akka" %% "akka-testkit" % "2.5.1" % "test"
)

libraryDependencies := akkaDependencies ++ Seq(
  "com.typesafe" %% "ssl-config-core" % "0.2.2-classpath-keystore" force,
  "com.typesafe.akka" %% "akka-actor" % "2.5.1",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.0.5",
  "org.bouncycastle" % "bcprov-jdk15on" % "1.59",

  "io.predix.dcosb" %% "service-module-api" % "1.0-SNAPSHOT" changing(),
  "io.predix.dcosb" %% "dcos-utils" % "1.0-SNAPSHOT" changing(),
  "io.predix.dcosb" %% "utils" % "1.0-SNAPSHOT" changing(),
  "io.predix.dcosb" %% "service-broker" % "1.0-SNAPSHOT" changing(),

  "com.datastax.cassandra" % "cassandra-driver-core" % "3.3.2",

  "io.predix.dcosb" %% "utils" % "1.0-SNAPSHOT" % "test" classifier "tests" changing(),
  "io.predix.dcosb" %% "dcos-utils" % "1.0-SNAPSHOT" % "test" classifier "tests" changing(),
  "org.scalatest" %% "scalatest" % "3.0.1" % "test",
  "org.scalamock" %% "scalamock-scalatest-support" % "3.6.0" % "test"
)

dependencyOverrides += "com.typesafe" % "config" % "1.3.2-ge-fix-332-SNAPSHOT" changing()


parseMarathon := {
  val templateEngine = new TemplateEngine
  val marathonJsonOut = Paths.get(marathonJsonPath.value)
  val marathonJson = templateEngine.layout(marathonTemplatePath.value, Map(
    "dockerImage" -> ( (if (dockerRegistry.value == "") "" else (dockerRegistry.value+"/") ) + dockerOrg.value + "/" + name.value + ":" + version.value),
    "serviceVhost" -> serviceVhost.value))

  Files.write(marathonJsonOut, marathonJson.getBytes(StandardCharsets.UTF_8))

}

dumpDeployInfo := {
  println(s"SBT_BUILD_VERSION=${version.value}")
  println(s"SBT_BUILD_ORG=${organization.value}")
  println(s"SBT_BUILD_NAME=${name.value}")
}


mainClass in (Compile, run) := Some("io.predix.dcosb.servicebroker.Daemon")
mainClass in assembly := Some("io.predix.dcosb.servicebroker.Daemon")

assemblyShadeRules in assembly := Seq(
  ShadeRule.zap("com.typesafe.sslconfig.akka.**").inLibrary("com.typesafe.akka" % "akka-stream_2.12" % "2.5.7")
)

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", xs @ _*)      => MergeStrategy.discard
  case x                                  =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}


imageNames in docker := Seq(
  ImageName(
    namespace = Some(organization.value),
    repository = name.value,
    tag = Some(version.value)
  )
)

dockerfile in docker := {
  // The assembly task generates a fat JAR file
  val artifact: File = assembly.value
  val artifactTargetPath = s"/app/${artifact.name}"

  new Dockerfile {
    from("java")

    env("DCOS_HOST" -> dcosHost.value)
    env("DCOS_PORT" -> dcosPort.value.toString)
    env("DCOS_PRINCIPAL" -> dcosPrincipal.value)
    env("DCOS_PRINCIPAL_PK_ALIAS" -> dcosPrincipalPrivateKeyAlias.value)
    env("DCOS_PRINCIPAL_PK_PSW" -> dcosPrincipalPrivateKeyPw.value)
    env("OSB_API_USR" -> apiUser.value)
    env("OSB_API_PSW" -> apiPassword.value)
    env("BROKER_LISTEN_ADDRESS" -> brokerListenAddress.value)
    env("BROKER_LISTEN_PORT" -> brokerListenPort.value.toString)
    env("BROKER_KEY_STORE" -> "/broker-key")
    env("BROKER_KEY_STORE_TYPE" -> brokerKeyStoreType.value)
    env("BROKER_KEY_STORE_URI" -> """\:.*""".r.replaceFirstIn(brokerKeyStoreUri.value, ":/broker-key"))
    env("BROKER_KEY_STORE_PSW" -> brokerKeyStorePw.value)
    env("BROKER_TRUST_STORE" -> "/broker-trust")
    env("BROKER_TRUST_STORE_TYPE" -> brokerTrustStoreType.value)
    env("BROKER_TRUST_STORE_PSW" -> brokerTrustStorePw.value)
    env("BROKER_TRUST_STORE_CLASSPATH" -> "false")
    env("BROKER_TLS_PK_CERT_STORE" -> brokerTlsPrivateKeyAndCertStore.value)
    env("BROKER_TLS_PK_CERT_ALIAS" -> brokerTlsPrivateKeyAndCertAlias.value)
    env("BROKER_TLS_PK_PSW" -> brokerTlsPrivateKeyPw.value)
    env("USER_MGMT_PK_ALIAS" -> userManagementPrivateKeyAlias.value)
    env("USER_MGMT_PK_PSW" -> userManagementPrivateKeyPw.value)

    env("ADDITIONAL_OPTS" -> additionalServiceBrokerOpts.value)
    add(artifact, artifactTargetPath)
    add(placeTrustStore.value, "/broker-trust")
    add(placeKeyStore.value, "/broker-key")
    add(applicationConfigurationPath.value, "/application.conf")

    cmdRaw(s"""java
              |-Dconfig.file=/application.conf
              |$$ADDITIONAL_OPTS
              |-jar $artifactTargetPath""".stripMargin.replaceAll("\n", " "))
  }
}



