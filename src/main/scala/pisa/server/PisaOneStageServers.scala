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

object PisaOneStageServer13000 extends ServerMain {
  override def port : Int = 13000
  def services: ServiceList[zio.ZEnv] = ServiceList.add(new OneStageBody)
}

object PisaOneStageServer14000 extends ServerMain {
  override def port : Int = 14000
  def services: ServiceList[zio.ZEnv] = ServiceList.add(new OneStageBody)
}

object PisaOneStageServer15000 extends ServerMain {
  override def port : Int = 15000
  def services: ServiceList[zio.ZEnv] = ServiceList.add(new OneStageBody)
}

object PisaOneStageServer16000 extends ServerMain {
  override def port : Int = 16000
  def services: ServiceList[zio.ZEnv] = ServiceList.add(new OneStageBody)
}

object PisaOneStageServer17000 extends ServerMain {
  override def port : Int = 17000
  def services: ServiceList[zio.ZEnv] = ServiceList.add(new OneStageBody)
}