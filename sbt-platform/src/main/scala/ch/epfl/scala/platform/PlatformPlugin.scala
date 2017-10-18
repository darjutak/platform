package ch.epfl.scala.platform

import coursier.core.Version
import sbt.{AutoPlugin, Def, ModuleID, PluginTrigger, Plugins}
import java.io.File

object PlatformPlugin extends AutoPlugin {
  val autoImport = AutoImportedKeys
  override def trigger: PluginTrigger = allRequirements

  override def requires: Plugins =
    bintray.BintrayPlugin &&
      com.typesafe.sbt.SbtPgp &&
      com.typesafe.tools.mima.plugin.MimaPlugin &&
      coursier.CoursierPlugin

  override def globalSettings: Seq[Def.Setting[_]] = super.globalSettings
  override def buildSettings: Seq[Def.Setting[_]] = super.buildSettings
  override def projectSettings: Seq[Def.Setting[_]] = PlatformPluginImplementation.settings
}

object AutoImportedKeys extends PlatformKeys.PlatformSettings with PlatformKeys.PlatformTasks

object PlatformKeys {
  import sbt.{settingKey, taskKey}

  case class PgpRings(`public`: File, `private`: File)

  trait PlatformSettings {
    val platformRootDir = settingKey[Option[File]]("Tells where the root directory is located.")
    val platformInsideCi = settingKey[Boolean]("Checks if CI is executing the build.")
    val platformTargetBranch = settingKey[String]("Branch used for the platform release.")
    val platformGitHubToken = settingKey[String]("Token to publish releases to GitHub.")
    val platformDefaultPublicRingName =
      settingKey[String]("Default file name for fetching the public gpg keys.")
    val platformDefaultPrivateRingName =
      settingKey[String]("Default file name for fetching the private gpg keys.")
  }

  trait PlatformTasks {}
}

object PlatformPluginImplementation {
  import sbt.{Resolver, Keys, Compile, Test, ThisBuild, Task, file, fileToRichFile}
  import ch.epfl.scala.platform.{AutoImportedKeys => ThisPluginKeys}
  import bintray.BintrayPlugin.{autoImport => BintrayKeys}
  import com.typesafe.tools.mima.plugin.MimaPlugin.{autoImport => MimaKeys}
  import ch.epfl.scala.sbt.release.{AutoImported => ReleaseEarlyKeys}

  private val PlatformReleases =
    Resolver.bintrayRepo("scalaplatform", PlatformReleasesRepo)
  private val PlatformTools =
    Resolver.bintrayRepo("scalaplatform", "tools")

  private final val PlatformReleasesRepo = "releases"
  private final val PlatformNightliesRepo = "nightlies"
  private final val twoLastScalaVersions = List("2.12.3", "2.11.11")
  private final val defaultCompilationFlags =
    List("-deprecation", "-encoding", "UTF-8", "-unchecked")

  val settings: Seq[Def.Setting[_]] = List(
    Keys.crossScalaVersions := twoLastScalaVersions,
    Keys.resolvers ++= Seq(PlatformReleases, PlatformTools),
    Keys.scalacOptions in Compile := {
      val currentOptions = (Keys.scalacOptions in Compile).value
      currentOptions.foldLeft(defaultCompilationFlags) {
        case (opts, defaultOpt) =>
          if (opts.contains(defaultOpt)) opts else opts :+ defaultOpt
      }
    },
    Keys.publishArtifact in Test := false
  ) ++ publishSettings ++ platformSettings

  lazy val publishSettings: Seq[Def.Setting[_]] = Seq(
    Keys.publishTo := (Keys.publishTo in BintrayKeys.bintray).value,
    // Necessary for synchronization with Maven Central
    Keys.publishMavenStyle := true,
    // Don't publish tests by default
    BintrayKeys.bintrayReleaseOnPublish in ThisBuild := false,
    BintrayKeys.bintrayRepository := PlatformReleasesRepo,
    BintrayKeys.bintrayOrganization := Some("scalaplatform")
  )

  /** Define custom release steps and add them to the default pipeline. */
  import com.typesafe.sbt.SbtPgp.{autoImport => PgpKeys}

  lazy val platformSettings: Seq[Def.Setting[_]] = Seq(
    ThisPluginKeys.platformInsideCi := sys.env.get("CI").nonEmpty,
    ThisPluginKeys.platformTargetBranch := "platform-release",
    ThisPluginKeys.platformGitHubToken := Defaults.platformGitHubToken.value,
    ThisPluginKeys.platformDefaultPublicRingName := Defaults.platformDefaultPublicRingName.value,
    ThisPluginKeys.platformDefaultPrivateRingName := Defaults.platformDefaultPrivateRingName.value,
    MimaKeys.mimaReportBinaryIssues := Defaults.mimaReportBinaryIssues.value,
    PgpKeys.pgpSigningKey := Defaults.pgpSigningKey.value,
    PgpKeys.pgpPassphrase := Defaults.pgpPassphrase.value,
    PgpKeys.pgpPublicRing := Defaults.pgpPublicRing.value,
    PgpKeys.pgpSecretRing := Defaults.pgpSecretRing.value,
  )

  object Defaults {
    val mimaReportBinaryIssues: Def.Initialize[Task[Unit]] = Def.taskDyn {
      val canBreakCompat = Keys.version.value.startsWith("0.")
      if (canBreakCompat) Def.task(())
      else MimaKeys.mimaReportBinaryIssues
    }

    val platformDefaultPublicRingName: Def.Initialize[String] =
      Def.setting("platform.pubring.asc")
    val platformDefaultPrivateRingName: Def.Initialize[String] =
      Def.setting("platform.secring.asc")

    final val GithubPlatformTokenKey = "GITHUB_PLATFORM_TOKEN"
    val platformGitHubToken: Def.Initialize[String] = Def.setting {
      sys.env.getOrElse(GithubPlatformTokenKey,
                        sys.error(Feedback.undefinedEnvironmentVariable(GithubPlatformTokenKey)))
    }

    private final val PlatformPgpKey = "11BCFDCC60929524"
    val pgpSigningKey: Def.Initialize[Option[Long]] = Def.setting {
      if (!ReleaseEarlyKeys.releaseEarlyNoGpg.value) {
        Some(new java.math.BigInteger(PlatformPgpKey, 16).longValue)
      } else None
    }

    val pgpPassphrase: Def.Initialize[Option[Array[Char]]] = Def.setting {
      if (!ReleaseEarlyKeys.releaseEarlyNoGpg.value) {
        sys.env.get("PLATFORM_PGP_PASSPHRASE").map(_.toCharArray)
      } else None
    }

    def getPgpRingFile(defaultRingFileName: String): Def.Initialize[File] = Def.setting {
      val rootDir = ThisPluginKeys.platformRootDir.value
      ThisPluginKeys.platformRootDir.value
        .filter(_.exists())
        .orElse(Option(file(System.getProperty("user.home"))))
        .map(_ / ".gnupg" / defaultRingFileName)
        .getOrElse(sys.error(Feedback.expectedCustomRing))
    }

    val pgpPublicRing: Def.Initialize[File] = Def.settingDyn {
      if (!ReleaseEarlyKeys.releaseEarlyNoGpg.value) {
        getPgpRingFile(ThisPluginKeys.platformDefaultPublicRingName.value)
      } else Def.setting(pgpPublicRing.value)
    }

    val pgpSecretRing: Def.Initialize[File] = Def.settingDyn {
      if (!ReleaseEarlyKeys.releaseEarlyNoGpg.value) {
        getPgpRingFile(ThisPluginKeys.platformDefaultPrivateRingName.value)
      } else Def.setting(pgpSecretRing.value)
    }
  }
}
