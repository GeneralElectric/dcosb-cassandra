import sbt._

object Utils {

  def copyResourceFromJarOnClasspath(classpath: Seq[Attributed[java.io.File]], jarName: String, resourceName: String, dest: File): File = {
    classpath find { _.data.name startsWith jarName } match {
      case Some(Attributed(sbJar: File)) =>
        IO.withTemporaryDirectory[File] { tmpDir =>
          IO.unzip(sbJar, tmpDir)
          // copy to project's target directory
          // Instead of copying you can do any other stuff here
          IO.copyFile(
            tmpDir / resourceName,
            dest / resourceName
          )

          dest / resourceName
        }
      case None =>
        sys.error(s"Could not find $jarName jar on compile classpath..")
    }
  }

}
