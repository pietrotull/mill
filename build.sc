import $file.shared
import $file.upload
import java.io.File

import ammonite.ops._
import coursier.maven.MavenRepository
import mill._
import mill.scalalib._, publish._
import mill.modules.Jvm.createAssembly
import upickle.Js
trait MillPublishModule extends PublishModule{
  def scalaVersion = "2.12.4"
  def artifactName = "mill-" + super.artifactName()
  def publishVersion = build.publishVersion()._2

  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "com.lihaoyi",
    url = "https://github.com/lihaoyi/mill",
    licenses = Seq(
      License("MIT license", "http://www.opensource.org/licenses/mit-license.php")
    ),
    scm = SCM(
      "git://github.com/lihaoyi/mill.git",
      "scm:git://github.com/lihaoyi/mill.git"
    ),
    developers = Seq(
      Developer("lihaoyi", "Li Haoyi","https://github.com/lihaoyi")
    )
  )
}
object moduledefs extends MillPublishModule{
  def ivyDeps = Agg(
    ivy"org.scala-lang:scala-compiler:${scalaVersion()}",
    ivy"com.lihaoyi::sourcecode:0.1.4"
  )
}

trait MillModule extends MillPublishModule{ outer =>

  def compileIvyDeps = Agg(ivy"com.lihaoyi::acyclic:0.1.7")
  def scalacOptions = Seq("-P:acyclic:force")
  def scalacPluginIvyDeps = Agg(ivy"com.lihaoyi::acyclic:0.1.7")

  def repositories = super.repositories ++ Seq(
    MavenRepository("https://oss.sonatype.org/content/repositories/releases")
  )

  def testArgs = T{ Seq.empty[String] }

  val test = new Tests(implicitly)
  class Tests(ctx0: mill.define.Ctx) extends mill.Module()(ctx0) with super.Tests{
    def forkArgs = T{ testArgs() }
    def moduleDeps =
      if (this == core.test) Seq(core)
      else Seq(outer, core.test)
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.6.0")
    def testFramework = "mill.UTestFramework"
    def scalacPluginClasspath = super.scalacPluginClasspath() ++ Seq(moduledefs.jar())
  }
}

object core extends MillModule {
  def moduleDeps = Seq(moduledefs)

  def compileIvyDeps = Agg(
    ivy"org.scala-lang:scala-reflect:${scalaVersion()}"
  )

  def ivyDeps = Agg(
    ivy"com.lihaoyi::sourcecode:0.1.4",
    ivy"com.lihaoyi::pprint:0.5.3",
    ivy"com.lihaoyi:::ammonite:1.0.3-21-05b5d32"
  )

  def generatedSources = T.sources {
    shared.generateCoreSources(T.ctx().dest)
  }

  val test = new Tests(implicitly)
  class Tests(ctx0: mill.define.Ctx) extends super.Tests(ctx0){
    def generatedSources = T.sources {
      shared.generateCoreTestSources(T.ctx().dest)
    }
  }
}


object scalaworker extends MillModule{
  def moduleDeps = Seq(core, scalalib)

  def ivyDeps = Agg(
    ivy"org.scala-sbt::zinc:1.0.5"
  )
  def testArgs = Seq(
    "-DMILL_SCALA_WORKER=" + runClasspath().map(_.path).mkString(",")
  )
}


object scalalib extends MillModule {
  def moduleDeps = Seq(core)

  def ivyDeps = Agg(
    ivy"org.scala-sbt:test-interface:1.0"
  )

  def genTask(m: ScalaModule) = T.task{
    Seq(m.jar(), m.sourcesJar()) ++
    m.externalCompileDepClasspath() ++
    m.externalCompileDepSources()
  }

  def testArgs = T{
    val genIdeaArgs =
      genTask(moduledefs)() ++
      genTask(core)() ++
      genTask(scalalib)() ++
      genTask(scalajslib)()

    scalaworker.testArgs() ++
    Seq("-DMILL_BUILD_LIBRARIES=" + genIdeaArgs.map(_.path).mkString(","))
  }
}


object scalajslib extends MillModule {

  def moduleDeps = Seq(scalalib)

  def testArgs = T{
    val mapping = Map(
      "MILL_SCALAJS_BRIDGE_0_6" -> jsbridges("0.6").compile().classes.path,
      "MILL_SCALAJS_BRIDGE_1_0" -> jsbridges("1.0").compile().classes.path
    )
    scalaworker.testArgs() ++ (for((k, v) <- mapping.toSeq) yield s"-D$k=$v")
  }

  object jsbridges extends Cross[JsBridgeModule]("0.6", "1.0")
  class JsBridgeModule(scalajsBinary: String) extends MillModule{
    def moduleDeps = Seq(scalajslib)
    def ivyDeps = scalajsBinary match {
      case "0.6" =>
        Agg(
          ivy"org.scala-js::scalajs-tools:0.6.22",
          ivy"org.scala-js::scalajs-sbt-test-adapter:0.6.22",
          ivy"org.scala-js::scalajs-js-envs:0.6.22"
        )
      case "1.0" =>
        Agg(
          ivy"org.scala-js::scalajs-tools:1.0.0-M2",
          ivy"org.scala-js::scalajs-sbt-test-adapter:1.0.0-M2",
          ivy"org.scala-js::scalajs-env-nodejs:1.0.0-M2"
        )
    }
  }
}
def testRepos = T{
  Seq(
    "MILL_ACYCLIC_REPO" ->
      shared.downloadTestRepo("lihaoyi/acyclic", "bc41cd09a287e2c270271e27ccdb3066173a8598", T.ctx().dest/"acyclic"),
    "MILL_JAWN_REPO" ->
      shared.downloadTestRepo("non/jawn", "fd8dc2b41ce70269889320aeabf8614fe1e8fbcb", T.ctx().dest/"jawn"),
    "MILL_BETTERFILES_REPO" ->
      shared.downloadTestRepo("pathikrit/better-files", "ba74ae9ef784dcf37f1b22c3990037a4fcc6b5f8", T.ctx().dest/"better-files"),
    "MILL_AMMONITE_REPO" ->
      shared.downloadTestRepo("lihaoyi/ammonite", "96ea548d5e3b72ab6ad4d9765e205bf6cc1c82ac", T.ctx().dest/"ammonite")
  )
}

object integration extends MillModule{
  def moduleDeps = Seq(moduledefs, scalalib, scalajslib)
  def testArgs = T{
    scalaworker.testArgs() ++ (for((k, v) <- testRepos()) yield s"-D$k=$v")
  }
  def forkArgs() = testArgs()
}

val assemblyProjects = Seq(scalalib, scalajslib)

def assemblyClasspath = mill.define.Task.traverse(assemblyProjects)(_.runClasspath)

def assemblyBase(classpath: Agg[Path], extraArgs: String)
                (implicit ctx: mill.util.Ctx.Dest) = {
  createAssembly(
    classpath,
    prependShellScript =
      "#!/usr/bin/env sh\n" +
      s"""exec java $extraArgs $$JAVA_OPTS -cp "$$0" mill.Main "$$@" """
  )
}

def devAssembly = T{
  assemblyBase(
    Agg.from(assemblyClasspath().flatten.map(_.path)),
    (scalalib.testArgs() ++ scalajslib.testArgs() ++ scalaworker.testArgs()).mkString(" ")
  )
}

def releaseAssembly = T{
  assemblyBase(Agg.from(assemblyClasspath().flatten.map(_.path)), "-DMILL_VERSION=" + publishVersion()._2)
}

val isMasterCommit = {
  sys.env.get("TRAVIS_PULL_REQUEST") == Some("false") &&
  (sys.env.get("TRAVIS_BRANCH") == Some("master") || sys.env("TRAVIS_TAG") != "")
}

def gitHead = T.input{
  sys.env.get("TRAVIS_COMMIT").getOrElse(
    %%('git, "rev-parse", "head")(pwd).out.string.trim()
  )
}
def publishVersion = T.input{
  val tag =
    try Option(
      %%('git, 'describe, "--exact-match", "--tags", gitHead())(pwd).out.string.trim()
    )
    catch{case e => None}

  tag match{
    case Some(t) => (t, t)
    case None =>
      val timestamp = java.time.Instant.now().toString.replaceAll(":|\\.", "-")
      ("unstable", timestamp + "-" + gitHead())
  }
}

def uploadToGithub(assembly: Path, authKey: String, release: String, label: String) = {
  if (release != "unstable"){
    scalaj.http.Http("https://api.github.com/repos/lihaoyi/mill/releases")
      .postData(
        upickle.json.write(
          Js.Obj(
            "tag_name" -> Js.Str(release),
            "name" -> Js.Str(release)
          )
        )
      )
      .header("Authorization", "token " + authKey)
      .asString
  }

  upload.apply(assembly, release, label, authKey)
}

def releaseCI(githubAuthKey: String,
              sonatypeCreds: String,
              gpgPassphrase: String,
              gpgPrivateKey: String) =
  if (!isMasterCommit) T.command()
  else {
    write(home / "gpg.key", java.util.Base64.getDecoder.decode(gpgPrivateKey))
    %('gpg, "--import", home / "gpg.key")(pwd)
    T.command{
      releaseManual(githubAuthKey, sonatypeCreds, gpgPassphrase)()
    }
  }


def releaseManual(githubAuthKey: String,
                  sonatypeCreds: String,
                  gpgPassphrase: String) = T.command{
  mill.scalalib.PublishModule.publishAll(
    sonatypeCreds = sonatypeCreds,
    gpgPassphrase = gpgPassphrase,
    modules = Seq(
      moduledefs,
      core,
      scalalib,
      scalajslib,
      scalaworker,
      scalajslib.jsbridges("0.6"),
      scalajslib.jsbridges("1.0")
    )
  )()

  val (release, label) = publishVersion()
  uploadToGithub(releaseAssembly().path, githubAuthKey, release, label)
  ()
}
