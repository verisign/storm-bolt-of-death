organization := "com.verisign.storm"

name := "storm-bolt-of-death"

scalaVersion := "2.10.4"

// https://github.com/jrudolph/sbt-dependency-graph
net.virtualvoid.sbt.graph.Plugin.graphSettings

resolvers ++= Seq(
  "typesafe-repository" at "http://repo.typesafe.com/typesafe/releases/",
  "clojars-repository" at "https://clojars.org/repo"
)

val curatorVersion = "2.7.1"
val javaVersion = "1.7"
val stormVersion = "0.9.3"
val zookeeperVersion = "3.4.6"

libraryDependencies ++= Seq(
  "org.apache.storm" % "storm-core" % stormVersion % "provided"
    exclude("org.apache.zookeeper", "zookeeper")
    exclude("org.slf4j", "log4j-over-slf4j"),
  "org.apache.curator" % "curator-framework" % curatorVersion
    exclude("com.google.guava", "guava")
    exclude("org.apache.zookeeper", "zookeeper"),
  "org.apache.curator" % "curator-recipes" % curatorVersion
    exclude("com.google.guava", "guava"),
  "org.apache.zookeeper" % "zookeeper" % zookeeperVersion
    exclude("org.slf4j", "slf4j-log4j12"),
  "com.google.guava" % "guava" % "18.0",
  // Logback with slf4j facade
  "ch.qos.logback" % "logback-classic" % "1.1.2"
)

// Required IntelliJ workaround.  This tells `sbt gen-idea` to include scala-reflect as a compile dependency (and not
// merely as a test dependency), which we need for TypeTag usage.
libraryDependencies <+= (scalaVersion)("org.scala-lang" % "scala-reflect" % _)

// Enable forking (see sbt docs) because our full build (including tests) uses many threads.
fork := true

javacOptions in Compile ++= Seq(
  "-source", javaVersion,
  "-target", javaVersion,
  "-Xlint:unchecked",
  "-Xlint:deprecation")

scalacOptions ++= Seq(
  "-target:jvm-" + javaVersion,
  "-encoding", "UTF-8"
)

scalacOptions in Compile ++= Seq(
  "-deprecation", // Emit warning and location for usages of deprecated APIs.
  "-feature",  // Emit warning and location for usages of features that should be imported explicitly.
  "-unchecked", // Enable additional warnings where generated code depends on assumptions.
  "-Xlint", // Enable recommended additional warnings.
  "-Ywarn-adapted-args", // Warn if an argument list is modified to match the receiver.
  "-Ywarn-dead-code",
  "-Ywarn-value-discard" // Warn when non-Unit expression results are unused.
)

parallelExecution in ThisBuild := false

mainClass in (Compile,run) := Some("com.verisign.storm.tools.sbod.BoltOfDeathTopology")
