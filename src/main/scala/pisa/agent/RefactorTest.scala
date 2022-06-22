package pisa.agent

import de.unruh.isabelle.control.Isabelle
import pisa.server.PisaOS
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._

import scala.concurrent.ExecutionContext
import scala.util.control.Breaks

object RefactorTest {
  val path_to_isa_bin: String = "/home/qj213/Isabelle2021"
  val path_to_file: String = "/home/qj213/afp-2021-10-22/thys/FunWithFunctions/FunWithFunctions.thy"
  val working_directory: String = "/home/qj213/afp-2021-10-22/thys/FunWithFunctions"
  def main(args: Array[String]): Unit = {
    val pisaos = new PisaOS(
      path_to_isa_bin=path_to_isa_bin,
      path_to_file=path_to_file,
      working_directory=working_directory
    )
    implicit val isabelle: Isabelle = pisaos.isabelle
    implicit val ec: ExecutionContext = pisaos.ec

    val continue = new Breaks
    for ((transition, text) <- pisaos.parse_text(pisaos.thy1, pisaos.fileContentCopy).force.retrieveNow) {
      continue.breakable {
        if (text.trim.isEmpty) continue.break
        else if (text.trim=="end") continue.break
        else {
          pisaos.singleTransition(transition)
        }
      }
    }
    println(pisaos.total_facts_and_defs_string(pisaos.toplevel))
  }
}
