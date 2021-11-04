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

libraryDependencies += "de.unruh" %% "scala-isabelle" % "master-SNAPSHOT" from "file:/Users/qj213/Projects/PISA/lib/scala-isabelle_2.13.jar"// development snapshot
resolvers += Resolver.sonatypeRepo("snapshots")

libraryDependencies += "net.liftweb" %% "lift-json" % "3.4.3"