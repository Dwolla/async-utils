import _root_.scalafix.sbt.ScalafixTestkitPlugin.autoImport.*
import _root_.scalafix.sbt.{ScalafixPlugin, ScalafixTestkitPlugin}
import com.typesafe.tools.mima.plugin.MimaPlugin
import com.typesafe.tools.mima.plugin.MimaPlugin.autoImport.*
import org.typelevel.sbt.TypelevelMimaPlugin.autoImport.*
import org.typelevel.sbt.gha.GenerativePlugin.autoImport.*
import org.typelevel.sbt.mergify.MergifyPlugin
import org.typelevel.sbt.mergify.MergifyPlugin.autoImport.*
import sbt.*
import sbt.Keys.*
import sbt.internal.ProjectMatrix
import sbt.librarymanagement.DependencyBuilders.OrganizationArtifactName
import sbtprojectmatrix.ProjectMatrixPlugin
import sbtprojectmatrix.ProjectMatrixPlugin.autoImport.*
import scalafix.sbt.ScalafixPlugin.autoImport.*

object AsyncUtilsBuildPlugin extends AutoPlugin {
  override def trigger = noTrigger

  override def requires: Plugins =
    ProjectMatrixPlugin && ScalafixPlugin && MimaPlugin && MergifyPlugin

  object autoImport {
    lazy val allProjects: Seq[Project] =
      `async-utils-core`.componentProjects ++
        `async-utils`.componentProjects ++
        examples.componentProjects ++
        List(
          `async-utils-twitter`,
          `async-utils-finagle`,
          `async-utils-finagle-natchez`,
          `scalafix-rules`,
          `scalafix-input`,
          `scalafix-output`,
          `scalafix-input-dependency`,
          `scalafix-output-dependency`,
          `scalafix-tests`,
        ).flatMap { pm =>
          if (Set("scalafix-input", "scalafix-output", "scalafix-input-dependency", "scalafix-output-dependency", "scalafix-tests").contains(pm.id)) List(pm)
          else List(pm, latestVersionAlias(pm))
        }
          .flatMap(_.componentProjects)
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

  private val SCALA_2_13: String = "2.13.11"
  private val SCALA_2_12 = "2.12.18"
  private val Scala2Versions: Seq[String] = Seq(SCALA_2_13, SCALA_2_12)

  private val CatsEffect3V = "3.5.0"
  private val CatsTaglessV: String = "0.15.0"
  private val libthriftV: String = "0.10.0"

  private val scala2CompilerPlugins: Seq[ModuleID] = Seq(
    compilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full)
  )

  private def dedupKindProjectorOptions(opts: Seq[String]): Seq[String] =
    if (opts.count(_.contains("-Ykind-projector")) > 1) opts.filterNot(_ == "-Ykind-projector") else opts

  private val moduleBase =
    Def.setting((Compile / scalaSource).value.getParentFile)

  private def projectMatrixForSupportedTwitterVersions(id: String,
                                                       path: String)
                                                      (s: Version => List[Setting[?]]): ProjectMatrix =
    supportedVersions.foldLeft(ProjectMatrix(id, file(path)))(addTwitterCustomRow(s))

  private def addTwitterCustomRow(s: Version => List[Setting[?]])
                                 (p: ProjectMatrix, v: Version): ProjectMatrix =
    p.customRow(
      scalaVersions = Scala2Versions,
      axisValues = List(TwitterVersion(v), VirtualAxis.jvm),
      _.settings(
        s(v)
      )
    )

  private def latestVersionAlias(p: ProjectMatrix): ProjectMatrix =
    ProjectMatrix(s"${p.id}-latest", file(s".${p.id}-latest"))
      .customRow(
        scalaVersions = Scala2Versions,
        axisValues = List(TwitterVersion(currentTwitterVersion), VirtualAxis.jvm),
        _.settings(
          moduleName := p.id,
          tlVersionIntroduced := Map("2.12" -> "1.1.0", "2.13" -> "1.1.0"),
        )
      )
      .dependsOn(p)

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
      List(
        moduleName := name.value + s"-$v",
        libraryDependencies ++= {
          Seq(
            "org.typelevel" %% "cats-effect" % CatsEffect3V,
            "com.twitter" %% "util-core" % v,
          ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
        },
        mimaPreviousArtifacts += organizationName.value %% name.value % "0.3.0",
        tlVersionIntroduced := Map("2.12" -> "1.1.0", "2.13" -> "1.1.0"),
      )
    }
      .dependsOn(`async-utils-core`)

  private lazy val `async-utils-finagle` =
    projectMatrixForSupportedTwitterVersions("async-utils-finagle", "twitter-finagle") { v =>
      List(
        moduleName := name.value + s"-$v",
        libraryDependencies ++= {
          Seq(
            "com.twitter" %% "finagle-thrift" % v,
          ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
        },
        mimaPreviousArtifacts += organizationName.value %% name.value % "0.3.0",
        tlVersionIntroduced := Map("2.12" -> "1.1.0", "2.13" -> "1.1.0"),
      )
    }
      .dependsOn(`async-utils-twitter`)

  private lazy val `async-utils-finagle-natchez` =
    projectMatrixForSupportedTwitterVersions("async-utils-finagle-natchez", "finagle-natchez") { v =>
      List(
        moduleName := name.value + s"-$v",
        libraryDependencies ++= {
          Seq(
            "org.tpolecat" %% "natchez-core" % "0.3.2",
            "org.tpolecat" %% "natchez-mtl" % "0.3.2",
            "com.comcast" %% "ip4s-core" % "3.3.0",
            "org.typelevel" %% "cats-mtl" % "1.3.1",
            "io.zipkin.finagle2" %% "zipkin-finagle-http" % "22.4.0",
          ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
        },
        mimaPreviousArtifacts += organizationName.value %% name.value % "0.3.0",
        tlVersionIntroduced := Map("2.12" -> "1.1.0", "2.13" -> "1.1.0"),
      )
    }
      .dependsOn(`async-utils-finagle`)

  private lazy val `scalafix-rules` =
    projectMatrixForSupportedTwitterVersions("finagle-tagless-scalafix", "scalafix/rules") { v =>
      List(
        moduleName := name.value + s"-$v",
        libraryDependencies ++= Seq(
          "ch.epfl.scala" %% "scalafix-core" % _root_.scalafix.sbt.BuildInfo.scalafixVersion,
          "org.scalameta" %% "munit" % "0.7.29" % Test,
          "com.eed3si9n.expecty" %% "expecty" % "0.16.0" % Test,
        ),
        scalacOptions ~= {
          _.filterNot(_ == "-Xfatal-warnings")
        },
        mimaPreviousArtifacts += organizationName.value %% name.value % "0.3.0",
        tlVersionIntroduced := Map("2.12" -> "1.1.0", "2.13" -> "1.1.0"),
      )
    }

  private lazy val `scalafix-input` =
    projectMatrixForSupportedTwitterVersions("scalafix-input", "scalafix/input") { v =>
      List(
        publish / skip := true,
        publishArtifact := false,
        libraryDependencies ++= Seq(
          "com.twitter" %% "scrooge-core" % v,
          "com.twitter" %% "finagle-thrift" % v,
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
      List(
        publish / skip := true,
        publishArtifact := false,
        libraryDependencies ++= Seq(
          "com.twitter" %% "scrooge-core" % v,
          "com.twitter" %% "finagle-thrift" % v,
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
      List(
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
      List(
        publish / skip := true,
        publishArtifact := false,
        libraryDependencies ++= {
          Seq(
            "com.twitter" %% "finagle-thrift" % v,
            "org.typelevel" %% "cats-tagless-core" % CatsTaglessV,
            "org.typelevel" %% "cats-tagless-macros" % CatsTaglessV,
          )
        },
      )
    }

  private lazy val `scalafix-output-dependency` =
    projectMatrixForSupportedTwitterVersions("scalafix-output-dependency", "scalafix/output-dependency") { v =>
      List(
        publish / skip := true,
        publishArtifact := false,
        libraryDependencies ++= {
          Seq(
            "com.twitter" %% "util-core" % v,
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

  private implicit class OrganizationArtifactNameOps(val oan: OrganizationArtifactName) extends AnyVal {
    def %(vav: Version): ModuleID =
      oan % vav.toString
  }
}