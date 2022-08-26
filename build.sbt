name := "PISA"

version := "0.1"

scalaVersion := "2.13.4"

val grpcVersion = "1.34.0"

PB.targets in Compile := Seq(
  scalapb.gen(grpc = true) -> (sourceManaged in Compile).value,
  scalapb.zio_grpc.ZioCodeGenerator -> (sourceManaged in Compile).value,
)

libraryDependencies ++= Seq(
  "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
  "io.grpc" % "grpc-netty" % grpcVersion
)
// libraryDependencies += "de.unruh" %% "scala-isabelle" % "master-SNAPSHOT" from "file:/large_experiments/theorem/aqj/third_party_software/Portal-to-ISAbelle/lib/scala-isabelle_2.13.jar"// development snapshot
libraryDependencies += "de.unruh" %% "scala-isabelle" % "master-SNAPSHOT" from "file:./lib/scala-isabelle_2.13.jar"// development snapshot
resolvers += Resolver.sonatypeRepo("snapshots")

libraryDependencies += "net.liftweb" %% "lift-json" % "3.4.3"

assemblyMergeStrategy in assembly := {
    case x if x.contains("io.netty.versions.properties") => MergeStrategy.discard
    case x if x.contains("de/unruh") => MergeStrategy.first
    case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
}
