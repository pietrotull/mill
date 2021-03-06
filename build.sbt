import java.io.File

parallelExecution := false

val sharedSettings = Seq(
  scalaVersion := "2.12.4",
  organization := "com.lihaoyi",
  libraryDependencies += "com.lihaoyi" %% "utest" % "0.6.0" % "test",

  testFrameworks += new TestFramework("mill.UTestFramework"),

  scalaSource in Compile := baseDirectory.value / "src",
  resourceDirectory in Compile := baseDirectory.value / "resources",

  scalaSource in Test := baseDirectory.value / "test" / "src",
  resourceDirectory in Test := baseDirectory.value / "test" / "resources",

  parallelExecution in Test := false,
  test in assembly := {},

  libraryDependencies += "com.lihaoyi" %% "acyclic" % "0.1.7" % "provided",
  resolvers += Resolver.sonatypeRepo("releases"),
  scalacOptions += "-P:acyclic:force",
  autoCompilerPlugins := true,
  addCompilerPlugin("com.lihaoyi" %% "acyclic" % "0.1.7"),

  libraryDependencies += "com.lihaoyi" % "ammonite" % "1.0.3-21-05b5d32" cross CrossVersion.full,
  mainClass in Test := Some("ammonite.Main")
)

val pluginSettings = Seq(
  scalacOptions in Test ++= {
    val jarFile = (packageBin in (moduledefs, Compile)).value
    val addPlugin = "-Xplugin:" + jarFile.getAbsolutePath
    // add plugin timestamp to compiler options to trigger recompile of
    // main after editing the plugin. (Otherwise a 'clean' is needed.)
    val dummy = "-Jdummy=" + jarFile.lastModified
    Seq(addPlugin, dummy)
  }
)

lazy val ammoniteRunner = project
  .in(file("target/ammoniteRunner"))
  .settings(
    scalaVersion := "2.12.4",
    target := baseDirectory.value,
    libraryDependencies +=
      "com.lihaoyi" % "ammonite" % "1.0.3-21-05b5d32" cross CrossVersion.full
  )


def ammoniteRun(hole: SettingKey[File], args: String => List[String], suffix: String = "") = Def.task{
  val target = hole.value / suffix
  if (!target.exists()) {
    IO.createDirectory(target)
    (runner in(ammoniteRunner, Compile)).value.run(
      "ammonite.Main",
      (dependencyClasspath in(ammoniteRunner, Compile)).value.files,
      args(target.toString),
      streams.value.log
    )
  }
  target
}


lazy val core = project
  .dependsOn(moduledefs)
  .settings(
    sharedSettings,
    pluginSettings,
    name := "mill-core",
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided",
      "com.lihaoyi" %% "sourcecode" % "0.1.4",
      "com.lihaoyi" %% "pprint" % "0.5.3",
      "com.lihaoyi" % "ammonite" % "1.0.3-21-05b5d32" cross CrossVersion.full
    ),
    sourceGenerators in Compile += {
      ammoniteRun(sourceManaged in Compile, List("shared.sc", "generateCoreSources", _))
        .taskValue
        .map(x => (x ** "*.scala").get)
    },

    sourceGenerators in Test += {
      ammoniteRun(sourceManaged in Test, List("shared.sc", "generateCoreTestSources", _))
        .taskValue
        .map(x => (x ** "*.scala").get)
    }
  )

lazy val moduledefs = project
  .settings(
    sharedSettings,
    name := "mill-moduledefs",
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      "com.lihaoyi" %% "sourcecode" % "0.1.4"
    ),
    publishArtifact in Compile := false
  )

lazy val scalaWorkerProps = Def.task{
  Seq("-DMILL_SCALA_WORKER=" + (fullClasspath in (scalaworker, Compile)).value.map(_.data).mkString(","))
}

lazy val scalalib = project
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    sharedSettings,
    pluginSettings,
    name := "mill-scalalib",
    fork := true,
    baseDirectory in Test := (baseDirectory in Test).value / "..",
    libraryDependencies ++= Seq(
      "org.scala-sbt" % "test-interface" % "1.0"
    )
  )

lazy val scalaworker: Project = project
  .dependsOn(core, scalalib)
  .settings(
    sharedSettings,
    pluginSettings,
    name := "mill-scalaworker",
    fork := true,
    libraryDependencies ++= Seq(
      "org.scala-sbt" %% "zinc" % "1.0.5"
    )
  )

def genTask(m: Project) = Def.task{
  Seq((packageBin in (m, Compile)).value, (packageSrc in (m, Compile)).value) ++
  (externalDependencyClasspath in (m, Compile)).value.map(_.data)
}

(javaOptions in scalalib) := {
  scalaWorkerProps.value ++
  Seq("-DMILL_BUILD_LIBRARIES=" +
    (
      genTask(moduledefs).value ++
      genTask(core).value ++
      genTask(scalalib).value ++
      genTask(scalajslib).value
    ).mkString(",")
  )
}
lazy val scalajslib = project
  .dependsOn(scalalib % "compile->compile;test->test")
  .settings(
    sharedSettings,
    name := "mill-scalajslib",
    fork in Test := true,
    baseDirectory in Test := (baseDirectory in Test).value / ".."
  )

def jsbridge(binary: String, version: String) =
  Project(
    id = "scalajsbridge_" + binary.replace('.', '_'),
    base = file("scalajslib/jsbridges/" + binary)
  ).dependsOn(scalajslib)
  .settings(
    sharedSettings,
    organization := "com.lihaoyi",
    scalaVersion := "2.12.4",
    name := "mill-js-bridge",
    libraryDependencies ++= Seq(
      "org.scala-js" %% "scalajs-tools"            % version,
      "org.scala-js" %% "scalajs-sbt-test-adapter" % version
    )
  )

lazy val scalajsbridge_0_6 = jsbridge("0.6", "0.6.22")
  .settings(
    libraryDependencies ++= Seq(
      "org.scala-js" %% "scalajs-js-envs" % "0.6.22"
    )
  )

lazy val scalajsbridge_1_0 = jsbridge("1.0", "1.0.0-M2")
  .settings(
    libraryDependencies ++= Seq(
      "org.scala-js" %% "scalajs-env-nodejs" % "1.0.0-M2"
    )
  )

javaOptions in (scalajslib, Test) := jsbridgeProps.value.toSeq ++ scalaWorkerProps.value

val jsbridgeProps = Def.task{
  val mapping = Map(
    "MILL_SCALAJS_BRIDGE_0_6" ->
      (packageBin in (scalajsbridge_0_6, Compile)).value.absolutePath.toString,
    "MILL_SCALAJS_BRIDGE_1_0" ->
      (packageBin in (scalajsbridge_1_0, Compile)).value.absolutePath.toString
  )
  for((k, v) <- mapping) yield s"-D$k=$v"
}

val testRepos = Map(
  "MILL_ACYCLIC_REPO" -> ammoniteRun(
    resourceManaged in test,
    List("shared.sc", "downloadTestRepo", "lihaoyi/acyclic", "bc41cd09a287e2c270271e27ccdb3066173a8598", _),
    suffix = "acyclic"
  ),
  "MILL_JAWN_REPO" -> ammoniteRun(
    resourceManaged in test,
    List("shared.sc", "downloadTestRepo", "non/jawn", "fd8dc2b41ce70269889320aeabf8614fe1e8fbcb", _),
    suffix = "jawn"
  ),
  "MILL_BETTERFILES_REPO" -> ammoniteRun(
    resourceManaged in test,
    List("shared.sc", "downloadTestRepo", "pathikrit/better-files", "ba74ae9ef784dcf37f1b22c3990037a4fcc6b5f8", _),
    suffix = "better-files"
  ),
  "MILL_AMMONITE_REPO" -> ammoniteRun(
    resourceManaged in test,
    List("shared.sc", "downloadTestRepo", "lihaoyi/ammonite", "96ea548d5e3b72ab6ad4d9765e205bf6cc1c82ac", _),
    suffix = "ammonite"
  )
)

lazy val integration = project
  .dependsOn(core % "compile->compile;test->test", scalalib, scalajslib)
  .settings(
    sharedSettings,
    name := "integration",
    fork := true,
    baseDirectory in Test := (baseDirectory in Test).value / "..",
    javaOptions in Test := {
      val kvs = Seq(
        "MILL_ACYCLIC_REPO" -> testRepos("MILL_ACYCLIC_REPO").value,
        "MILL_AMMONITE_REPO" -> testRepos("MILL_AMMONITE_REPO").value,
        "MILL_JAWN_REPO" -> testRepos("MILL_JAWN_REPO").value,
        "MILL_BETTERFILES_REPO" -> testRepos("MILL_BETTERFILES_REPO").value
      )
      scalaWorkerProps.value ++ (for((k, v) <- kvs) yield s"-D$k=$v")
    }
  )

lazy val bin = project
  .in(file("target/bin"))
  .dependsOn(scalalib, scalajslib)
  .settings(
    sharedSettings,
    target := baseDirectory.value,
    fork := true,
    connectInput in (Test, run) := true,
    outputStrategy in (Test, run) := Some(StdoutOutput),
    mainClass in (Test, run) := Some("mill.Main"),
    baseDirectory in (Test, run) := (baseDirectory in (Compile, run)).value / ".." / "..",
    javaOptions in (Test, run) := {
      (javaOptions in (scalalib, Compile)).value ++
      jsbridgeProps.value.toSeq ++
      scalaWorkerProps.value
    },
    assemblyOption in assembly := {
      val extraArgs = (javaOptions in (Test, run)).value.mkString(" ")
      (assemblyOption in assembly).value.copy(
        prependShellScript = Some(
          Seq(
            "#!/usr/bin/env sh",
            s"""exec java $extraArgs $$JAVA_OPTS -cp "$$0" mill.Main "$$@" """
          )
        )
      )
    },
    assembly in Test := {
      val dest = target.value/"mill"
      IO.copyFile(assembly.value, dest)
      import sys.process._
      Seq("chmod", "+x", dest.getAbsolutePath).!
      dest
    }
  )
