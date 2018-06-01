import java.io.File
import dcosb.DcosbBuild._

commonSettings

name := "service-module-cassandra"
version := "1.0-SNAPSHOT"

libraryDependencies := commonDependencies ++ Seq()

enablePlugins(DockerPlugin)

mainClass in (Compile, run) := Some("io.predix.dcosb.servicebroker.Daemon")
mainClass in assembly := Some("io.predix.dcosb.servicebroker.Daemon")
// Docker config below
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
    add(artifact, artifactTargetPath)
    cmdRaw(
      s"""java
         |$$ADDITIONAL_OPTS
         |-jar $artifactTargetPath""".stripMargin.replaceAll("\n", " "))
  }
}