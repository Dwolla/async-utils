lazy val `async-utils-root` = (project in file("."))
  .aggregate(allProjects.map(_.project) *)
  .settings(
    publish / skip := true,
    publishArtifact := false,
  )
  .enablePlugins(AsyncUtilsBuildPlugin)
