//import com.typesafe.sbt.pgp.PgpKeys
//import sbtrelease.ReleaseStateTransformations._

lazy val publishSettings = Seq(
  publishMavenStyle := true,
/* bintrayOrganization := Some("scalaplatform"),
  bintrayRepository := "tools",
  bintrayPackageLabels := Seq("scala", "platform", "tools", "sbt"),
  publishTo := (publishTo in bintray).value,
  publishArtifact in Test := false,
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,*/
  licenses := Seq(
    // Scala Center license... BSD 3-clause
    "BSD" -> url("http://opensource.org/licenses/BSD-3-Clause")
  ),
  homepage := Some(url("https://github.com/scalaplatform/platform")),
  autoAPIMappings := true,
  apiURL := Some(url("https://scalaplatform.github.io/platform")),
/*  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    publishArtifacts,
    setNextVersion,
    commitNextVersion,
    pushChanges
  ),*/
  pomExtra :=
    <developers>
      <developer>
        <id>jvican</id>
        <name>Jorge Vicente Cantero</name>
        <url></url>
      </developer>
    </developers>
)
lazy val buildSettings = Seq(
  organization := "ch.epfl.scala",
  resolvers += Resolver.jcenterRepo,
  resolvers += Resolver.bintrayRepo("jvican", "releases"),
  updateOptions := updateOptions.value.withCachedResolution(true)
)

lazy val compilerOptions = Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-unchecked",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Xfuture",
  "-Xlint"
)

lazy val commonSettings = Seq(
  triggeredMessage in ThisBuild := Watched.clearWhenTriggered,
  watchSources += baseDirectory.value / "resources",
  scalacOptions in(Compile, console) := compilerOptions,
  testOptions in Test += Tests.Argument("-oD")
)

lazy val testDependencies = Seq(
  "org.scalatest" %% "scalatest" % "3.0.0" % "test",
  "junit" % "junit" % "4.12" % "test"
)

lazy val noPublish = Seq(
  publish := {},
  publishLocal := {}
)

lazy val allSettings = commonSettings ++ buildSettings ++ publishSettings

lazy val platform = project
  .in(file("."))
  .settings(allSettings)
  .settings(noPublish)
  .settings(scalaVersion := "2.11.8")
  .aggregate(process, `release-manager`)
  .dependsOn(process, `release-manager`)

lazy val process: Project = project
  .in(file("process"))
  .enablePlugins(OrnatePlugin)
  .settings(allSettings)
  .settings(scalaVersion := "2.11.8")
  .settings(
    name := "platform-process",
    ornateTargetDir := Some(file("docs/")),
    compile in Compile := Def.taskDyn {
      val analysis = (compile in Compile).value
      Def.task {
        (ornate in process).value
        // Work around Ornate limitation to add custom CSS
        val targetDir = (ornateTargetDir in process).value.get
        val cssFolder = targetDir / "_theme" / "css"
        val customCss = cssFolder / "custom.css"
        val mainCss = cssFolder / "app.css"
        IO.append(mainCss, IO.read(customCss))
        analysis
      }
    }.value
  )

lazy val `release-manager` = project
  .in(file("release-manager"))
  .settings(allSettings)
  .settings(scalaVersion := "2.11.8")
  .settings(
    libraryDependencies ++= Seq(
      "org.eclipse.jgit" % "org.eclipse.jgit" % "4.5.0.201609210915-r",
      "me.vican.jorge" %% "stoml" % "0.2",
      "org.typelevel" %% "cats" % "0.7.2"
    ) ++ testDependencies
  )

val circeVersion = "0.5.1"
val ivyScriptedCachePath = settingKey[String]("Ivy scripted cache path.")
lazy val `sbt-platform` = project
  .in(file("utils"))
  .settings(allSettings)
  .settings(ScriptedPlugin.scriptedSettings)
  .settings(
    sbtPlugin := true,
    publishMavenStyle := false,
    addSbtPlugin("me.lessis" % "bintray-sbt" % "0.3.0"),
    addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.0.0"),
    addSbtPlugin("ch.epfl.scala" % "sbt-release" % "1.0.6"),
    addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.1.11"),
    addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-M14"),
    addCompilerPlugin(
      "org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full
    ),
    libraryDependencies ++= Seq(
      "com.eed3si9n" %% "gigahorse-core" % "0.1.1",
      "com.lihaoyi" %% "sourcecode" % "0.1.1",
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      // Magically solves NoSuchMethodError, bintry depends on it, sigh
      "net.databinder.dispatch" %% "dispatch-json4s-native" % "0.11.3",
      "com.github.nscala-time" %% "nscala-time" % "2.14.0"
    ) ++ testDependencies,
    ivyScriptedCachePath := {
      if (sys.env.get("CI").exists(_.toBoolean))
        "-Dsbt.ivy.home=/drone/.ivy2"
      else s"-Dsbt.ivy.home=${ivyPaths.value.ivyHome.get}"
    },
    scriptedLaunchOpts := Seq(
      "-Dplugin.version=" + version.value,
      // .jvmopts is ignored, simulate here
      "-XX:MaxPermSize=256m",
      "-Xmx2g",
      "-Xss2m",
      ivyScriptedCachePath.value,
      "-Dplatform.debug=true",
      "-Dplatform.test=true"
    ),
    scriptedBufferLog := false,
    fork in Test := true,
    javaOptions in Test ++= Seq("-Dplatform.debug=true",
      "-Dplatform.test=true")
  )
