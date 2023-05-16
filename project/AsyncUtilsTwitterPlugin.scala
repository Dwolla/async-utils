import com.typesafe.tools.mima.plugin.MimaPlugin
import com.typesafe.tools.mima.plugin.MimaPlugin.autoImport.*
import org.typelevel.sbt.gha.GenerativePlugin.autoImport.*
import org.typelevel.sbt.mergify.MergifyPlugin
import org.typelevel.sbt.mergify.MergifyPlugin.autoImport.*
import org.typelevel.sbt.TypelevelMimaPlugin.autoImport.*
import org.typelevel.sbt.TypelevelVersioningPlugin.autoImport.*
import sbt.Keys.*
import sbt.internal.{MatrixClasspathDep, ProjectMatrix}
import sbt.*
import sbt.internal.*
import sbtprojectmatrix.ProjectMatrixPlugin
import sbtprojectmatrix.ProjectMatrixPlugin.autoImport.*
import scalafix.sbt.ScalafixTestkitPlugin
import scalafix.sbt.ScalafixTestkitPlugin.autoImport.*
import scalafix.sbt.ScalafixPlugin
import scalafix.sbt.ScalafixPlugin.autoImport.*
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*

object AsyncUtilsTwitterPlugin extends AutoPlugin {
  override def trigger = noTrigger

  override def requires: Plugins =
    ProjectMatrixPlugin && ScalafixPlugin && MimaPlugin && MergifyPlugin

  object autoImport {
    lazy val allProjects: Seq[Project] =
      `async-utils-core`.componentProjects ++
        `async-utils`.componentProjects ++
        examples.componentProjects ++
        `async-utils-twitter`.componentProjects ++
        `async-utils-finagle`.componentProjects ++
        `async-utils-finagle-natchez`.componentProjects ++
        `scalafix-rules`.componentProjects ++
        `scalafix-input`.componentProjects ++
        `scalafix-output`.componentProjects ++
        `scalafix-input-dependency`.componentProjects ++
        `scalafix-output-dependency`.componentProjects ++
        `scalafix-tests`.componentProjects
  }

  private val currentTwitterVersion = Version("22.7.0").get

  // When a new version is released, move what was previously the current version into the list of old versions.
  // This plugin will automatically release a new suffixed artifact that can be used by users with bincompat issues.
  // Don't forget to regenerate the GitHub Actions workflow by running the `githubWorkflowGenerate` sbt task.
  private val oldVersions = List(
    "22.4.0",
  )
    .flatMap(Version(_))

  private val supportedVersions = (currentTwitterVersion :: oldVersions).sorted.reverse

  private val SCALA_2_13: String = "2.13.10"
  private val SCALA_2_12 = "2.12.17"
  private val Scala2Versions: Seq[String] = Seq(SCALA_2_13, SCALA_2_12)

  private val CatsEffect3V = "3.5.0"
  private val CatsTaglessV: String = "0.14.0"
  private val libthriftV: String = "0.10.0"

  private val scala2CompilerPlugins: Seq[ModuleID] = Seq(
    compilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full)
  )

  private def dedupKindProjectorOptions(opts: Seq[String]): Seq[String] =
    if (opts.count(_.contains("-Ykind-projector")) > 1) opts.filterNot(_ == "-Ykind-projector") else opts

  private val moduleBase =
    Def.setting((Compile / scalaSource).value.getParentFile)

  private def moduleNameSuffix(v: Version): String =
    if (v == currentTwitterVersion) "" else s"-$v"

  private def projectMatrixForSupportedTwitterVersions(id: String,
                                                       path: String)
                                                      (s: Version => Seq[Setting[?]]): ProjectMatrix =
    supportedVersions.foldLeft(ProjectMatrix(id, file(path)))(addTwitterCustomRow(s))

  private def addTwitterCustomRow(s: Version => Seq[Setting[?]])
                                 (p: ProjectMatrix, v: Version): ProjectMatrix =
    p.customRow(
      scalaVersions = Scala2Versions,
      axisValues = List(TwitterVersion(v.toString, v == currentTwitterVersion), VirtualAxis.jvm),
      settings = {
        val versionIntroduced = tlVersionIntroduced := githubWorkflowScalaVersions.value.map(_ -> "1.0.1").toMap
        val noMima = mimaPreviousArtifacts := Set.empty
        val previousVersions = tlMimaPreviousVersions += "0.3.0"

        if (v == currentTwitterVersion) versionIntroduced :: previousVersions :: s(v).toList else noMima :: s(v).toList
      }
    )

  private lazy val `async-utils-core` = projectMatrix
    .in(file("core"))
    .settings(
      description := "Safely convert final tagless-style algebras implemented in Future to cats-effect Async",
      libraryDependencies ++= {
        Seq(
          "org.typelevel" %% "cats-effect" % CatsEffect3V,
          "org.typelevel" %% "cats-tagless-core" % CatsTaglessV,
        ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
      },
    )
    .jvmPlatform(Scala2Versions)
    .jsPlatform(Scala2Versions)

  private lazy val `async-utils` = projectMatrix
    .jvmPlatform(Scala2Versions)
    .jsPlatform(Scala2Versions)
    .in(file("scala-futures"))
    .settings(
      libraryDependencies ++= {
        Seq(
          "org.typelevel" %% "cats-effect" % CatsEffect3V,
        ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
      },
    )
    .dependsOn(`async-utils-core`)

  private lazy val examples = projectMatrix
    .jvmPlatform(Scala2Versions)
    .in(file("examples"))
    .settings(
      libraryDependencies ++= {
        Seq(
          "org.typelevel" %% "cats-tagless-macros" % CatsTaglessV,
        ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
      },
      publish / skip := true,
      publishArtifact := false,
    )
    .dependsOn(`async-utils`)

  private lazy val `async-utils-twitter` =
    projectMatrixForSupportedTwitterVersions("async-utils-twitter", "twitter-futures") { v =>
      Seq(
        moduleName := name.value + moduleNameSuffix(v),
        libraryDependencies ++= {
          Seq(
            "org.typelevel" %% "cats-effect" % CatsEffect3V,
            "com.twitter" %% "util-core" % v.toString,
          ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
        },
      )
    }
      .dependsOn(`async-utils-core`)

  private lazy val `async-utils-finagle` =
    projectMatrixForSupportedTwitterVersions("async-utils-finagle", "twitter-finagle") { v =>
      Seq(
        moduleName := name.value + moduleNameSuffix(v),
        libraryDependencies ++= {
          Seq(
            "com.twitter" %% "finagle-thrift" % v.toString,
          ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
        },
      )
    }
      .dependsOn(`async-utils-twitter`)

  private lazy val `async-utils-finagle-natchez` =
    projectMatrixForSupportedTwitterVersions("async-utils-finagle-natchez", "finagle-natchez") { v =>
      Seq(
        moduleName := name.value + moduleNameSuffix(v),
        libraryDependencies ++= {
          Seq(
            "org.tpolecat" %% "natchez-core" % "0.3.1",
            "org.tpolecat" %% "natchez-mtl" % "0.3.1",
            "com.comcast" %% "ip4s-core" % "3.3.0",
            "org.typelevel" %% "cats-mtl" % "1.3.1",
            "io.zipkin.finagle2" %% "zipkin-finagle-http" % "22.4.0",
          ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
        },
      )
    }
      .dependsOn(`async-utils-finagle`)

  private lazy val `scalafix-rules` =
    projectMatrixForSupportedTwitterVersions("scalafix-rules", "scalafix/rules") { v =>
      Seq(
        name := "finagle-tagless-scalafix",
        moduleName := name.value + moduleNameSuffix(v),
        libraryDependencies ++= Seq(
          "ch.epfl.scala" %% "scalafix-core" % _root_.scalafix.sbt.BuildInfo.scalafixVersion,
          "org.scalameta" %% "munit" % "0.7.29" % Test,
          "com.eed3si9n.expecty" %% "expecty" % "0.16.0" % Test,
        ),
        scalacOptions ~= {
          _.filterNot(_ == "-Xfatal-warnings")
        },
      )
    }

  private lazy val `scalafix-input` =
    projectMatrixForSupportedTwitterVersions("scalafix-input", "scalafix/input") { v =>
      Seq(
        publish / skip := true,
        publishArtifact := false,
        libraryDependencies ++= Seq(
          "com.twitter" %% "scrooge-core" % v.toString,
          "com.twitter" %% "finagle-thrift" % v.toString,
          "org.apache.thrift" % "libthrift" % libthriftV,
        ),
        scalacOptions += "-nowarn",
        scalacOptions ~= {
          _.filterNot(_ == "-Xfatal-warnings")
        },
        semanticdbEnabled := true,
        semanticdbVersion := scalafixSemanticdb.revision,
        Compile / unmanagedSourceDirectories += baseDirectory.value / "src_managed" / "main" / "scala",
      )
    }
      .dependsOn(`scalafix-input-dependency`)
      .disablePlugins(ScalafixPlugin)

  private lazy val `scalafix-output` =
    projectMatrixForSupportedTwitterVersions("scalafix-output", "scalafix/output") { v =>
      Seq(
        publish / skip := true,
        publishArtifact := false,
        libraryDependencies ++= Seq(
          "com.twitter" %% "scrooge-core" % v.toString,
          "com.twitter" %% "finagle-thrift" % v.toString,
          "org.apache.thrift" % "libthrift" % libthriftV,
          "org.typelevel" %% "cats-tagless-core" % CatsTaglessV,
          "org.typelevel" %% "cats-tagless-macros" % CatsTaglessV,
        ),
        scalacOptions += "-nowarn",
        scalacOptions ~= {
          _.filterNot(_ == "-Xfatal-warnings")
        },
        Compile / unmanagedSourceDirectories += baseDirectory.value / "src_managed" / "main" / "scala",
      )
    }
      .dependsOn(`scalafix-output-dependency`)
      .disablePlugins(ScalafixPlugin)

  private lazy val `scalafix-tests` =
    projectMatrixForSupportedTwitterVersions("scalafix-tests", "scalafix/tests") { v =>
      Seq(
        publish / skip := true,
        publishArtifact := false,
        libraryDependencies += "ch.epfl.scala" % "scalafix-testkit" % _root_.scalafix.sbt.BuildInfo.scalafixVersion % Test cross CrossVersion.full,
        scalafixTestkitOutputSourceDirectories := TwitterVersion.resolve(`scalafix-output`, Compile / unmanagedSourceDirectories).value,
        scalafixTestkitInputSourceDirectories := TwitterVersion.resolve(`scalafix-input`, Compile / unmanagedSourceDirectories).value,
        scalafixTestkitInputClasspath := TwitterVersion.resolve(`scalafix-input`, Compile / fullClasspath).value,
        scalafixTestkitInputScalacOptions := TwitterVersion.resolve(`scalafix-input`, Compile / scalacOptions).value,
        scalafixTestkitInputScalaVersion := TwitterVersion.resolve(`scalafix-input`, Compile / scalaVersion).value,
      )
    }
      .dependsOn(`scalafix-rules`)
      .enablePlugins(ScalafixTestkitPlugin)

  lazy val `scalafix-input-dependency` =
    projectMatrixForSupportedTwitterVersions("scalafix-input-dependency", "scalafix/input-dependency") { v =>
      Seq(
        publish / skip := true,
        publishArtifact := false,
        libraryDependencies ++= {
          Seq(
            "com.twitter" %% "finagle-thrift" % v.toString,
            "org.typelevel" %% "cats-tagless-core" % CatsTaglessV,
            "org.typelevel" %% "cats-tagless-macros" % CatsTaglessV,
          )
        },
      )
    }

  private lazy val `scalafix-output-dependency` =
    projectMatrixForSupportedTwitterVersions("scalafix-output-dependency", "scalafix/output-dependency") { v =>
      Seq(
        publish / skip := true,
        publishArtifact := false,
        libraryDependencies ++= {
          Seq(
            "com.twitter" %% "util-core" % v.toString,
            "org.typelevel" %% "cats-tagless-core" % CatsTaglessV,
            "org.typelevel" %% "cats-tagless-macros" % CatsTaglessV,
          )
        },
      )
    }
      .dependsOn(`async-utils-finagle`)

  override def buildSettings: Seq[Def.Setting[?]] = Seq(
    scalaVersion := SCALA_2_13,
    mergifyLabelPaths :=
      List(
        "core",
        "examples",
        "scala-futures",
        "scalafix",
        "twitter-finagle",
        "twitter-futures",
        "finagle-natchez",
      )
        .map(x => x -> file(x))
        .toMap,

    /* this is misleading, because we're actually running the build for all supported
     * scala versions, but unfortunately this seems to be our best option until
     * sbt-typelevel 0.5.
     *
     * sbt-projectmatrix creates separate projects for each crossed Scala version
     * setting githubWorkflowScalaVersions to a single (ignored) version minimizes
     * the build matrix, and setting githubWorkflowBuildSbtStepPreamble to an empty
     * list ensures that the build phase ignores the scala version set in
     * githubWorkflowScalaVersions.
     *
     * '++ ${{ matrix.scala }}' will still be used in the Publish stage, but it
     * sounds like the tlCiRelease will do the right thing anyway.
     */
    githubWorkflowScalaVersions := Seq("2.13"),
    githubWorkflowBuildSbtStepPreamble := Nil,
  )

  override def extraProjects: Seq[Project] = autoImport.allProjects
}
