package pisa.server

import scalapb.zio_grpc.{ServerMain, ServiceList}

object PisaOneStageServer8000 extends ServerMain {
  override def port : Int = 8000
  def services: ServiceList[zio.ZEnv] = ServiceList.add(new OneStageBody)
}

object PisaOneStageServer9000 extends ServerMain {
  override def port : Int = 9000
  def services: ServiceList[zio.ZEnv] = ServiceList.add(new OneStageBody)
}

object PisaOneStageServer10000 extends ServerMain {
  override def port : Int = 10000
  def services: ServiceList[zio.ZEnv] = ServiceList.add(new OneStageBody)
}

object PisaOneStageServer11000 extends ServerMain {
  override def port : Int = 11000
  def services: ServiceList[zio.ZEnv] = ServiceList.add(new OneStageBody)
}

object PisaOneStageServer12000 extends ServerMain {
  override def port : Int = 12000
  def services: ServiceList[zio.ZEnv] = ServiceList.add(new OneStageBody)
}