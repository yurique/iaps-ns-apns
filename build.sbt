ThisBuild / scalaVersion     := "3.7.0"
ThisBuild / version          := "0.1.0-dev2"
ThisBuild / organization     := "com.yurique"
ThisBuild / organizationName := "com.yurique"

val root = project
  .in(file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name                 := "iaps-ns-apns",
    Docker / packageName := Option(System.getenv("NSAPNS_BUILD_DOCKER_PACKAGE_NAME")).map(_.trim).filterNot(_.isEmpty).getOrElse("yurique/iaps-ns-apns"),
    dockerExposedPorts ++= Seq(22626),
    dockerBaseImage      := "--platform=linux/amd64 eclipse-temurin:17-jdk",
    libraryDependencies ++= Seq(
      "io.circe"              %% "circe-core"          % versions.circe,
      "io.circe"              %% "circe-parser"        % versions.circe,
      "org.typelevel"         %% "cats-effect"         % versions.catsEffect,
      "org.http4s"            %% "http4s-ember-server" % versions.http4s,
      "org.http4s"            %% "http4s-ember-client" % versions.http4s,
      "org.http4s"            %% "http4s-dsl"          % versions.http4s,
      "org.http4s"            %% "http4s-circe"        % versions.http4s,
      "org.typelevel"         %% "log4cats-core"       % versions.log4cats,
      "org.typelevel"         %% "log4cats-slf4j"      % versions.log4cats,
      "ch.qos.logback"         % "logback-classic"     % versions.logback,
      "io.socket"              % "socket.io-client"    % versions.socketIO,
      "com.github.jwt-scala"  %% "jwt-circe"           % versions.jwtScala,
      "com.monovore"          %% "decline"             % versions.decline,
      "com.monovore"          %% "decline-effect"      % versions.decline,
      "com.rewardsnetwork"    %% "pure-aws-s3"         % versions.pureAws,
      "software.amazon.awssdk" % "s3"                  % versions.aws,
      "software.amazon.awssdk" % "sts"                 % versions.aws
    ),
  )
