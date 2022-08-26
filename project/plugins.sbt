val zioGrpcVersion = "0.4.2"

addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.0-RC4")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "1.2.0")
libraryDependencies += "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-codegen" % zioGrpcVersion
