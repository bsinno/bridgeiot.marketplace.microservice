/**
 * Copyright (c) 2016-2017 Atos IT Solutions and Services GmbH
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
import com.typesafe.sbt.packager.archetypes.JavaAppPackaging
import NativePackagerHelper._
import com.typesafe.sbt.packager.SettingsHelper._


val akkaVersion = "2.5.17"
val circeVersion = "0.9.3"
val funCqrsVersion = "1.0.0-M1"
val paradiseVersion = "2.1.0"
	
// usage of the flag: sbt "-DenableRetrieveManaged=true" universal:publish
val enableRetrieveManagedProp = sys.props.getOrElse("enableRetrieveManaged", "false")

lazy val `marketplace-microservice` = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    mappings in Universal := {if (enableRetrieveManagedProp.toBoolean) directory("lib_managed") else (mappings in Universal).value},
    mappings in Universal += { //   Add version.properties file to target directory
      val versionFile = target.value / "version.properties"
      IO.write(versionFile, s"version=${version.value}")
      versionFile -> "version.properties"
    },
    makeDeploymentSettings(Universal, packageBin in Universal, "zip"),
		    
    organization := "org.eclipse.bridgeiot",
    name := "marketplace-microservice",
    version := "0.1-SNAPSHOT",

    scalaVersion := "2.12.7",
    
    retrieveManaged := enableRetrieveManagedProp.toBoolean,
    
    addCompilerPlugin("org.scalamacros" % "paradise" % paradiseVersion cross CrossVersion.full),

    publishTo := Some("Local Nexus" at "https://nexus.big-iot.org/content/repositories/snapshots/"),
    credentials += Credentials(System.getenv("NEXUS_REALM"), System.getenv("NEXUS_HOST"),
      System.getenv("NEXUS_USER"), System.getenv("NEXUS_PASSWORD")),
    resolvers += (publishTo in Universal).value.get,
    publishTo in Universal := Some(Resolver.mavenLocal),

    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,

      "com.typesafe.akka" %% "akka-http" % "10.1.5",
      "de.heikoseeberger" %% "akka-http-circe" % "1.22.0",
      "com.typesafe.akka" %% "akka-stream-kafka" % "0.22",
      "com.typesafe.akka" %% "akka-persistence-cassandra" % "0.28",

      "org.sangria-graphql" %% "sangria" % "1.4.2",
      "org.sangria-graphql" %% "sangria-circe" % "1.2.1",

      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-generic-extras" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "com.pauldijou" %% "jwt-circe" % "0.18.0",

      "io.strongtyped" %% "fun-cqrs-akka" % funCqrsVersion,
      "io.strongtyped" %% "fun-cqrs-test-kit" % funCqrsVersion % "test",

      "org.slf4j" % "slf4j-api" % "1.7.25",
      "org.slf4j" % "slf4j-simple" % "1.7.25",
      "ch.qos.logback" % "logback-classic" % "1.2.3",

      "org.scalatest" %% "scalatest" % "3.0.5" % "test"
    )
  )
