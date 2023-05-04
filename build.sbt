ThisBuild / organization := "com.dwolla"
ThisBuild / homepage := Some(url("https://github.com/Dwolla/async-utils-twitter"))
ThisBuild / licenses := Seq(License.MIT)
ThisBuild / developers := List(
  Developer(
    "bpholt",
    "Brian Holt",
    "bholt+async-utils@dwolla.com",
    url("https://dwolla.com")
  ),
)
ThisBuild / startYear := Option(2021)
ThisBuild / tlSonatypeUseLegacyHost := true
ThisBuild / tlBaseVersion := "0.3"
ThisBuild / githubWorkflowScalaVersions := Seq("2.13", "2.12")
ThisBuild / tlCiReleaseBranches := Seq("main")
ThisBuild / mergifyStewardConfig ~= { _.map(_.copy(
  author = "dwolla-oss-scala-steward[bot]",
  mergeMinors = true,
))}

tpolecatScalacOptions += ScalacOptions.release("8")

lazy val CatsEffect3V = "3.4.10"
lazy val TwitterUtilsLatestV = "22.7.0"
lazy val CatsTaglessV = "0.14.0"
lazy val libthriftV = "0.10.0"

lazy val SCALA_2_13 = "2.13.10"
lazy val SCALA_2_12 = "2.12.17"

lazy val Scala2Versions = Seq(SCALA_2_13, SCALA_2_12)
ThisBuild / scalaVersion := SCALA_2_13

lazy val scala2CompilerPlugins: Seq[ModuleID] = Seq(
  compilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full)
)

def dedupKindProjectorOptions(opts: Seq[String]): Seq[String] =
  if (opts.count(_.contains("-Ykind-projector")) > 1) opts.filterNot(_ == "-Ykind-projector") else opts

lazy val moduleBase =
  Def.setting((Compile / scalaSource).value.getParentFile)

lazy val `async-utils-twitter` = project
  .in(file("twitter-futures"))
  .settings(
    crossScalaVersions := Scala2Versions,
    libraryDependencies ++= {
      Seq(
        "com.dwolla" %% "async-utils-core" % "1.0.0",
        "org.typelevel" %% "cats-effect" % CatsEffect3V,
        "com.twitter" %% "util-core" % TwitterUtilsLatestV,
      ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
    }
  )

lazy val `async-utils-finagle` = project
  .in(file("twitter-finagle"))
  .settings(
    crossScalaVersions := Scala2Versions,
    libraryDependencies ++= {
      Seq(
        "com.twitter" %% "finagle-thrift" % TwitterUtilsLatestV,
      ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
    }
  )
  .dependsOn(`async-utils-twitter`)

lazy val `async-utils-finagle-natchez` = project
  .in(file("finagle-natchez"))
  .settings(
    crossScalaVersions := Scala2Versions,
    libraryDependencies ++= {
      Seq(
        "org.tpolecat" %% "natchez-core" % "0.3.1",
        "org.tpolecat" %% "natchez-mtl" % "0.3.1",
        "com.comcast" %% "ip4s-core" % "3.3.0",
        "org.typelevel" %% "cats-mtl" % "1.3.1",
        "io.zipkin.finagle2" %% "zipkin-finagle-http" % "22.4.0",
      ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
    }
  )
  .dependsOn(`async-utils-finagle`)

lazy val `scalafix-rules` = project
  .in(file("scalafix/rules"))
  .settings(
    crossScalaVersions := Scala2Versions,
    moduleName := "finagle-tagless-scalafix",
    libraryDependencies ++= Seq(
      "ch.epfl.scala" %% "scalafix-core" % _root_.scalafix.sbt.BuildInfo.scalafixVersion,
      "org.scalameta" %% "munit" % "0.7.29" % Test,
      "com.eed3si9n.expecty" %% "expecty" % "0.16.0" % Test,
    ),
    scalacOptions ~= { _.filterNot(_ == "-Xfatal-warnings") },
  )

lazy val `scalafix-input` = project
  .in(file("scalafix/input"))
  .settings(
    publish / skip := true,
    publishArtifact := false,
    crossScalaVersions := Scala2Versions,
    libraryDependencies ++= Seq(
      "com.twitter" %% "scrooge-core" % TwitterUtilsLatestV,
      "com.twitter" %% "finagle-thrift" % TwitterUtilsLatestV,
      "org.apache.thrift" % "libthrift" % libthriftV,
    ),
    scalacOptions += "-nowarn",
    scalacOptions ~= { _.filterNot(_ == "-Xfatal-warnings") },
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    Compile / unmanagedSourceDirectories += baseDirectory.value / "src_managed" / "main" / "scala",
  )
  .dependsOn(`scalafix-input-dependency`)
  .disablePlugins(ScalafixPlugin)

lazy val `scalafix-output` = project
  .in(file("scalafix/output"))
  .settings(
    publish / skip := true,
    publishArtifact := false,
    crossScalaVersions := Scala2Versions,
    libraryDependencies ++= Seq(
      "com.twitter" %% "scrooge-core" % TwitterUtilsLatestV,
      "com.twitter" %% "finagle-thrift" % TwitterUtilsLatestV,
      "org.apache.thrift" % "libthrift" % libthriftV,
      "org.typelevel" %% "cats-tagless-core" % CatsTaglessV,
      "org.typelevel" %% "cats-tagless-macros" % CatsTaglessV,
    ),
    scalacOptions += "-nowarn",
    scalacOptions ~= { _.filterNot(_ == "-Xfatal-warnings") },
    Compile / unmanagedSourceDirectories += baseDirectory.value / "src_managed" / "main" / "scala",
  )
  .dependsOn(`scalafix-output-dependency`)
  .disablePlugins(ScalafixPlugin)

lazy val `scalafix-tests` = project
  .in(file("scalafix/tests"))
  .settings(
    crossScalaVersions := Scala2Versions,
    publish / skip := true,
    publishArtifact := false,
    libraryDependencies += "ch.epfl.scala" % "scalafix-testkit" % _root_.scalafix.sbt.BuildInfo.scalafixVersion % Test cross CrossVersion.full,
    scalafixTestkitOutputSourceDirectories := (`scalafix-output` / Compile / unmanagedSourceDirectories).value,
    scalafixTestkitInputSourceDirectories := (`scalafix-input` / Compile / unmanagedSourceDirectories).value,
    scalafixTestkitInputClasspath := (`scalafix-input` / Compile / fullClasspath).value,
    scalafixTestkitInputScalacOptions := (`scalafix-input` / Compile / scalacOptions).value,
    scalafixTestkitInputScalaVersion := (`scalafix-input` / Compile / scalaVersion).value,
  )
  .dependsOn(`scalafix-rules`)
  .enablePlugins(ScalafixTestkitPlugin)

lazy val `scalafix-input-dependency` = project
  .in(file("scalafix/input-dependency"))
  .settings(
    publish / skip := true,
    publishArtifact := false,
    crossScalaVersions := Scala2Versions,
    libraryDependencies ++= {
      Seq(
        "com.twitter" %% "finagle-thrift" % TwitterUtilsLatestV,
        "org.typelevel" %% "cats-tagless-core" % CatsTaglessV,
        "org.typelevel" %% "cats-tagless-macros" % CatsTaglessV,
      )
    },
  )

lazy val `scalafix-output-dependency` = project
  .in(file("scalafix/output-dependency"))
  .settings(
    publish / skip := true,
    publishArtifact := false,
    crossScalaVersions := Scala2Versions,
    libraryDependencies ++= {
      Seq(
        "com.twitter" %% "util-core" % TwitterUtilsLatestV,
        "org.typelevel" %% "cats-tagless-core" % CatsTaglessV,
        "org.typelevel" %% "cats-tagless-macros" % CatsTaglessV,
      )
    },
  )
  .dependsOn(`async-utils-finagle`)

lazy val `async-utils-root` = (project in file("."))
  .aggregate(
    `async-utils-twitter`,
    `async-utils-finagle`,
    `async-utils-finagle-natchez`,
    `scalafix-rules`,
    `scalafix-tests`,
  )
  .settings(
    publish / skip := true,
    publishArtifact := false,
  )
