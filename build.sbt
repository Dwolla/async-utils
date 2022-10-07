import ConfigAxes._

inThisBuild(List(
  organization := "com.dwolla",
  description := "Safely convert final tagless-style algebras implemented in Future to cats-effect Async",
  homepage := Some(url("https://github.com/Dwolla/")),
  licenses += ("MIT", url("https://opensource.org/licenses/MIT")),
  developers := List(
    Developer(
      "bpholt",
      "Brian Holt",
      "bholt+async-utils@dwolla.com",
      url("https://dwolla.com")
    ),
  ),
  startYear := Option(2021),
))

lazy val CatsEffect2V = "2.5.5"
lazy val CatsEffect3V = "3.3.14"
lazy val TwitterUtilsLatestV = "22.4.0"
lazy val CatsTaglessV = "0.14.0"
lazy val libthriftV = "0.10.0"

lazy val SCALA_2_13 = "2.13.8"
lazy val SCALA_2_12 = "2.12.16"

lazy val Scala2Versions = Seq(SCALA_2_13, SCALA_2_12)

lazy val scala2CompilerPlugins: Seq[ModuleID] = Seq(
  compilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full)
)

def dedupKindProjectorOptions(opts: Seq[String]): Seq[String] =
  if (opts.count(_.contains("-Ykind-projector")) > 1) opts.filterNot(_ == "-Ykind-projector") else opts

lazy val moduleBase =
  Def.setting((Compile / scalaSource).value.getParentFile)

lazy val `async-utils-core` = (projectMatrix in file("core"))
  .customRow(
    scalaVersions = Scala2Versions,
    axisValues = Seq(CatsEffect2, VirtualAxis.jvm),
    _.settings(
      moduleName := name.value + "-ce2",
      Compile / unmanagedSourceDirectories += moduleBase.value / "scala-ce2",
      libraryDependencies ++= {
        Seq(
          "org.typelevel" %% "cats-effect" % CatsEffect2V,
          "org.typelevel" %% "cats-tagless-core" % CatsTaglessV,
        ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
      },
    )
  )
  .customRow(
    scalaVersions = Scala2Versions,
    axisValues = Seq(CatsEffect3, VirtualAxis.jvm),
    _.settings(
      moduleName := name.value + "-ce3",
      Compile / unmanagedSourceDirectories += moduleBase.value / "scala-ce3",
      libraryDependencies ++= {
        Seq(
          "org.typelevel" %% "cats-effect" % CatsEffect3V,
          "org.typelevel" %% "cats-tagless-core" % CatsTaglessV,
        ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
      },
    )
  )
  .customRow(
    scalaVersions = Scala2Versions,
    axisValues = Seq(CatsEffect2, VirtualAxis.js),
    _.settings(
      moduleName := name.value + "-ce2",
      Compile / unmanagedSourceDirectories += moduleBase.value / "scala-ce2",
      libraryDependencies ++= {
        Seq(
          "org.typelevel" %%% "cats-effect" % CatsEffect2V,
          "org.typelevel" %% "cats-tagless-core" % CatsTaglessV,
        ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
      },
    )
      .enablePlugins(ScalaJSPlugin)
  )
  .customRow(
    scalaVersions = Scala2Versions,
    axisValues = Seq(CatsEffect3, VirtualAxis.js),
    _.settings(
      moduleName := name.value + "-ce3",
      Compile / unmanagedSourceDirectories += moduleBase.value / "scala-ce3",
      libraryDependencies ++= {
        Seq(
          "org.typelevel" %%% "cats-effect" % CatsEffect3V,
          "org.typelevel" %% "cats-tagless-core" % CatsTaglessV,
        ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
      },
    )
      .enablePlugins(ScalaJSPlugin)
  )

lazy val `async-utils` = (projectMatrix in file("scala-futures"))
  .customRow(
    scalaVersions = Scala2Versions,
    axisValues = Seq(CatsEffect2, VirtualAxis.jvm),
    _.settings(
      moduleName := name.value + "-ce2",
      Compile / unmanagedSourceDirectories += moduleBase.value / "scala-ce2",
      libraryDependencies ++= {
        Seq(
          "org.typelevel" %% "cats-effect" % CatsEffect2V,
        ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
      },
    )
  )
  .customRow(
    scalaVersions = Scala2Versions,
    axisValues = Seq(CatsEffect3, VirtualAxis.jvm),
    _.settings(
      moduleName := name.value + "-ce3",
      Compile / unmanagedSourceDirectories += moduleBase.value / "scala-ce3",
      libraryDependencies ++= {
        Seq(
          "org.typelevel" %% "cats-effect" % CatsEffect3V,
        ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
      },
    )
  )
  .customRow(
    scalaVersions = Scala2Versions,
    axisValues = Seq(CatsEffect2, VirtualAxis.js),
    _.settings(
      moduleName := name.value + "-ce2",
      Compile / unmanagedSourceDirectories += moduleBase.value / "scala-ce2",
      libraryDependencies ++= {
        Seq(
          "org.typelevel" %%% "cats-effect" % CatsEffect2V,
        ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
      },
    )
      .enablePlugins(ScalaJSPlugin)
  )
  .customRow(
    scalaVersions = Scala2Versions,
    axisValues = Seq(CatsEffect3, VirtualAxis.js),
    _.settings(
      moduleName := name.value + "-ce3",
      Compile / unmanagedSourceDirectories += moduleBase.value / "scala-ce3",
      libraryDependencies ++= {
        Seq(
          "org.typelevel" %%% "cats-effect" % CatsEffect3V,
        ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
      },
    )
      .enablePlugins(ScalaJSPlugin)
  )
  .dependsOn(`async-utils-core`)

lazy val `async-utils-twitter` = (projectMatrix in file("twitter-futures"))
  .customRow(
    scalaVersions = Scala2Versions,
    axisValues = Seq(CatsEffect2, VirtualAxis.jvm),
    _.settings(
      moduleName := name.value + "-ce2",
      Compile / unmanagedSourceDirectories += moduleBase.value / "scala-ce2",
      libraryDependencies ++= {
        Seq(
          "org.typelevel" %% "cats-effect" % CatsEffect2V,
          "com.twitter" %% "util-core" % TwitterUtilsLatestV,
        ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
      }
    )
  )
  .customRow(
    scalaVersions = Scala2Versions,
    axisValues = Seq(CatsEffect3, VirtualAxis.jvm),
    _.settings(
      moduleName := name.value + "-ce3",
      Compile / unmanagedSourceDirectories += moduleBase.value / "scala-ce3",
      libraryDependencies ++= {
        Seq(
          "org.typelevel" %% "cats-effect" % CatsEffect3V,
          "com.twitter" %% "util-core" % TwitterUtilsLatestV,
        ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
      }
    )
  )
  .dependsOn(`async-utils-core`)

lazy val `async-utils-finagle` = (projectMatrix in file("twitter-finagle"))
  .customRow(
    scalaVersions = Scala2Versions,
    axisValues = Seq(CatsEffect2, VirtualAxis.jvm),
    _.settings(
      moduleName := name.value + "-ce2",
      Compile / unmanagedSourceDirectories += moduleBase.value / "scala-ce2",
      libraryDependencies ++= {
        Seq(
          "com.twitter" %% "finagle-thrift" % TwitterUtilsLatestV,
        ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
      }
    )
  )
  .customRow(
    scalaVersions = Scala2Versions,
    axisValues = Seq(CatsEffect3, VirtualAxis.jvm),
    _.settings(
      moduleName := name.value + "-ce3",
      Compile / unmanagedSourceDirectories += moduleBase.value / "scala-ce3",
      libraryDependencies ++= {
        Seq(
          "com.twitter" %% "finagle-thrift" % TwitterUtilsLatestV,
        ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
      }
    )
  )
  .dependsOn(`async-utils-twitter`)

lazy val examples = (projectMatrix in file("examples"))
  .settings(
    libraryDependencies ++= {
      Seq(
        "org.typelevel" %% "cats-tagless-macros" % CatsTaglessV,
      ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
    },
    publish / skip := true,
  )
  .jvmPlatform(scalaVersions = Scala2Versions)
  .dependsOn(`async-utils`)

lazy val `scalafix-rules` = (projectMatrix in file("scalafix/rules"))
  .jvmPlatform(scalaVersions = Scala2Versions)
  .settings(
    moduleName := "finagle-tagless-scalafix",
    libraryDependencies ++= Seq(
      "ch.epfl.scala" %% "scalafix-core" % _root_.scalafix.sbt.BuildInfo.scalafixVersion,
      "org.scalameta" %% "munit" % "0.7.29" % Test,
      "com.eed3si9n.expecty" %% "expecty" % "0.16.0" % Test,
    ),
    scalacOptions ~= { _.filterNot(_ == "-Xfatal-warnings") },
  )

lazy val `scalafix-input` = (project in file("scalafix/input"))
  .settings(
    publish / skip := true,
    scalaVersion := Scala2Versions.head,
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

lazy val `scalafix-output` = (project in file("scalafix/output"))
  .settings(
    publish / skip := true,
    scalaVersion := Scala2Versions.head,
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

lazy val `scalafix-tests` = (projectMatrix in file("scalafix/tests"))
  .jvmPlatform(scalaVersions = Scala2Versions)
  .settings(
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

lazy val `scalafix-input-dependency` = (project in file("scalafix/input-dependency"))
  .settings(
    publish / skip := true,
    scalaVersion := Scala2Versions.head,
    libraryDependencies ++= {
      Seq(
        "com.twitter" %% "finagle-thrift" % TwitterUtilsLatestV,
        "org.typelevel" %% "cats-tagless-core" % CatsTaglessV,
        "org.typelevel" %% "cats-tagless-macros" % CatsTaglessV,
      )
    },
  )

lazy val `scalafix-output-dependency` = (project in file("scalafix/output-dependency"))
  .settings(
    publish / skip := true,
    scalaVersion := Scala2Versions.head,
    libraryDependencies ++= {
      Seq(
        "com.twitter" %% "util-core" % TwitterUtilsLatestV,
        "org.typelevel" %% "cats-tagless-core" % CatsTaglessV,
        "org.typelevel" %% "cats-tagless-macros" % CatsTaglessV,
      )
    },
  )
  .dependsOn(`async-utils-finagle`.finder(CatsEffect3, VirtualAxis.jvm).apply(Scala2Versions.head))

lazy val `async-utils-root` = (project in file("."))
  .aggregate(
    Seq(
      `async-utils-core`,
      `async-utils`,
      `async-utils-twitter`,
      `async-utils-finagle`,
      `scalafix-rules`,
      `scalafix-tests`,
      examples,
    ).flatMap(_.projectRefs): _*
  )
  .settings(
    publish / skip := true,
  )
