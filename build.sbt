ThisBuild / organization := "com.dwolla"
ThisBuild / homepage := Some(url("https://github.com/Dwolla/async-utils"))
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
ThisBuild / tlBaseVersion := "1.0"
ThisBuild / githubWorkflowScalaVersions := Seq("2.13", "2.12")
ThisBuild / tlCiReleaseBranches := Seq("main")
ThisBuild / mergifyRequiredJobs ++= Seq("validate-steward")
ThisBuild / mergifyStewardConfig ~= { _.map(_.copy(
  author = "dwolla-oss-scala-steward[bot]",
  mergeMinors = true,
))}

tpolecatScalacOptions += ScalacOptions.release("8")

lazy val CatsEffect2V = "2.5.5"
lazy val CatsEffect3V = "3.4.11"
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

lazy val `async-utils-core` = crossProject(JVMPlatform, JSPlatform)
  .in(file("core"))
  .settings(
    crossScalaVersions := Scala2Versions,
    description := "Safely convert final tagless-style algebras implemented in Future to cats-effect Async",
    libraryDependencies ++= {
      Seq(
        "org.typelevel" %% "cats-effect" % CatsEffect3V,
        "org.typelevel" %% "cats-tagless-core" % CatsTaglessV,
      ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
    },
  )
  .jsEnablePlugins(ScalaJSPlugin)

lazy val `async-utils` = crossProject(JVMPlatform, JSPlatform)
  .in(file("scala-futures"))
  .settings(
    crossScalaVersions := Scala2Versions,
    libraryDependencies ++= {
      Seq(
        "org.typelevel" %% "cats-effect" % CatsEffect3V,
      ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
    },
  )
  .jsEnablePlugins(ScalaJSPlugin)
  .dependsOn(`async-utils-core`)

lazy val `async-utils-twitter` = project
  .in(file("twitter-futures"))
  .settings(
    crossScalaVersions := Scala2Versions,
    libraryDependencies ++= {
      Seq(
        "org.typelevel" %% "cats-effect" % CatsEffect3V,
        "com.twitter" %% "util-core" % TwitterUtilsLatestV,
      ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
    }
  )
  .dependsOn(`async-utils-core`.jvm)

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

lazy val examples = crossProject(JVMPlatform)
  .in(file("examples"))
  .settings(
    crossScalaVersions := Scala2Versions,
    libraryDependencies ++= {
      Seq(
        "org.typelevel" %% "cats-tagless-macros" % CatsTaglessV,
      ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
    },
    publish / skip := true,
    publishArtifact := false,
  )
  .dependsOn(`async-utils`)

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
    `async-utils-core`.jvm,
    `async-utils-core`.js,
    `async-utils`.jvm,
    `async-utils`.js,
    `async-utils-twitter`,
    `async-utils-finagle`,
    `scalafix-rules`,
    `scalafix-tests`,
    examples.jvm,
  )
  .settings(
    publish / skip := true,
    publishArtifact := false,
  )
