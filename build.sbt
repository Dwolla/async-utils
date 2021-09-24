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

lazy val CatsEffect2V = "2.5.1"
lazy val CatsEffect3V = "3.1.1"
lazy val TwitterUtilsLatestV = "21.5.0"
lazy val TwitterUtils19_4V = "19.4.0"
lazy val CatsTaglessV = "0.14.0"

lazy val SCALA_2_13 = "2.13.6"
lazy val SCALA_2_12 = "2.12.14"
lazy val SCALA_3 = "3.0.0"

lazy val Scala2Versions = Seq(SCALA_2_13, SCALA_2_12)
lazy val Scala2And3 = Seq(SCALA_2_13, SCALA_2_12, SCALA_3)

lazy val scala2CompilerPlugins: Seq[ModuleID] = Seq(
  compilerPlugin("org.typelevel" % "kind-projector" % "0.13.0" cross CrossVersion.full)
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
    axisValues = Seq(CatsEffect2, TwitterUtilsLatest, VirtualAxis.jvm),
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
    axisValues = Seq(CatsEffect3, TwitterUtilsLatest, VirtualAxis.jvm),
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
  .customRow(
    scalaVersions = Seq(SCALA_2_12),
    axisValues = Seq(CatsEffect2, TwitterUtils19_4, VirtualAxis.jvm),
    _.settings(
      moduleName := name.value + "-19-4-ce2",
      libraryDependencies ++= {
        Seq(
          "org.typelevel" %% "cats-effect" % CatsEffect2V,
          "com.twitter" %% "util-core" % TwitterUtils19_4V,
        ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
      },
    )
  )
  .customRow(
    scalaVersions = Seq(SCALA_2_12),
    axisValues = Seq(CatsEffect3, TwitterUtils19_4, VirtualAxis.jvm),
    _.settings(
      moduleName := name.value + "-19-4-ce3",
      libraryDependencies ++= {
        Seq(
          "org.typelevel" %% "cats-effect" % CatsEffect3V,
          "com.twitter" %% "util-core" % TwitterUtils19_4V,
        ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
      },
    )
  )
  .dependsOn(`async-utils-core`)

lazy val `async-utils-finagle` = (projectMatrix in file("twitter-finagle"))
  .customRow(
    scalaVersions = Scala2Versions,
    axisValues = Seq(CatsEffect2, TwitterUtilsLatest, VirtualAxis.jvm),
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
    axisValues = Seq(CatsEffect3, TwitterUtilsLatest, VirtualAxis.jvm),
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
        "org.typelevel" %% "cats-tagless-macros" % "0.14.0",
      ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
    },
    publish / skip := true,
  )
  .jvmPlatform(scalaVersions = Scala2Versions)
  .dependsOn(`async-utils`)

lazy val `async-utils-root` = (project in file("."))
  .aggregate(
    Seq(
      `async-utils-core`,
      `async-utils`,
      `async-utils-twitter`,
      `async-utils-finagle`,
      examples,
    ).flatMap(_.projectRefs): _*
  )
  .settings(
    publish / skip := true,
  )
