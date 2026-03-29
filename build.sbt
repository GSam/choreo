ThisBuild / organization := "me.romac"
ThisBuild / homepage     := Some(url("https://github.com/romac/choreo"))
ThisBuild / licenses     := Seq(
  "BSD-3-Clause" -> url("https://opensource.org/licenses/BSD-3-Clause")
)
ThisBuild / developers   := List(
  Developer(
    id = "romac",
    name = "Romain Ruetschi",
    email = "romain@romac.me",
    url = url("https://github.com/romac")
  )
)
ThisBuild / scmInfo      := Some(
  ScmInfo(
    url("https://github.com/romac/choreo"),
    "scm:git:git@github.com:romac/choreo.git"
  )
)

// Publish to Sonatype Central (central.sonatype.com)
ThisBuild / sonatypeCredentialHost := "central.sonatype.com"
ThisBuild / sonatypeRepository     := "https://central.sonatype.com/api/v1/publisher"

ThisBuild / version      := Versions.choreo
ThisBuild / scalaVersion := Versions.scala3

lazy val root = project
  .in(file("."))
  .aggregate(core, examples)

lazy val core = project
  .in(file("core"))
  .settings(
    name := "choreo",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core"         % Versions.cats,
      "org.typelevel" %% "cats-free"         % Versions.cats,
      "org.typelevel" %% "cats-effect"       % Versions.catsEffect,
      "org.scodec"    %% "scodec-core"       % Versions.scodec,
      "org.scalameta" %% "munit"             % Versions.munit           % Test,
      "org.typelevel" %% "munit-cats-effect" % Versions.munitCatsEffect % Test
    )
  )

lazy val examples = project
  .in(file("examples"))
  .settings(
    name           := "choreo-examples",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core"   % Versions.cats,
      "org.typelevel" %% "cats-effect" % Versions.catsEffect,
      "org.scodec"    %% "scodec-core" % Versions.scodec
    )
  )
  .dependsOn(core)

val PrimaryJava = JavaSpec.temurin("17")
val GraalVM     = JavaSpec.graalvm(Graalvm.Distribution("graalvm-community"), "17")

ThisBuild / githubWorkflowJavaVersions := Seq(PrimaryJava, GraalVM)

ThisBuild / githubWorkflowTargetTags ++= Seq("v*")

ThisBuild / githubWorkflowPublishTargetBranches :=
  Seq(RefPredicate.StartsWith(Ref.Tag("v")))

ThisBuild / githubWorkflowPublish := Seq(
  WorkflowStep.Sbt(
    commands = List("ci-release"),
    name = Some("Publish project"),
    env = Map(
      "PGP_PASSPHRASE"    -> "${{ secrets.PGP_PASSPHRASE }}",
      "PGP_SECRET"        -> "${{ secrets.PGP_SECRET }}",
      "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
      "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}"
    )
  )
)
