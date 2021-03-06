name := "Sandbox"

version:= "0.1.0-SNAPSHOT"

scalaVersion := "2.12.4"

scalacOptions ++= Seq("-deprecation", "-feature")

resolvers += "MQTT Repository" at "https://repo.eclipse.org/content/repositories/paho-releases/"

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.datastax.cassandra" % "cassandra-driver-core" % "3.3.2",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.9.2",
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.9.2",
  "com.fasterxml.jackson.dataformat" % "jackson-dataformat-smile" % "2.9.2",
  "com.github.etaty" %% "rediscala" % "1.8.0",
  "com.github.haifengl" %% "smile-scala" % "1.5.0",
  "com.github.scopt" %% "scopt" % "3.7.0",
  "com.typesafe.akka" %% "akka-cluster" % "2.4.20",
  "com.typesafe.akka" %% "akka-slf4j" % "2.4.20",
  "com.typesafe.akka" %% "akka-http" % "10.0.11",
  "org.clapper" %% "scalasti" % "3.0.1",
  "org.eclipse.paho" % "mqtt-client" % "0.4.0",

  "com.typesafe.akka" %% "akka-http-testkit" % "10.0.11" % "test",
  "org.scalatest" %% "scalatest" % "3.2.0-SNAP9" % "test"
)
