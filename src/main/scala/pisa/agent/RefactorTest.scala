package pisa.agent

import de.unruh.isabelle.control.Isabelle
import pisa.server.PisaOS
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.mlvalue.{MLFunction, MLFunction2}
import de.unruh.isabelle.mlvalue.MLValue.compileFunction
import de.unruh.isabelle.pure.Implicits._
import de.unruh.isabelle.pure.ToplevelState

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
    val transition_and_index_list = pisaos.parse_text(pisaos.thy1, pisaos.fileContentCopy.trim).force.retrieveNow.zipWithIndex
    for (((transition, text), i) <- transition_and_index_list) {
      continue.breakable {
        if (text.trim.isEmpty) continue.break
        else if (text.trim=="end" && (i==transition_and_index_list.length-1)) continue.break
        else {
          pisaos.singleTransition(transition)
        }
      }
    }

    val get_def: MLFunction2[ToplevelState, String, String] =
      compileFunction[ToplevelState, String, String](
      """fn (tls, def_occur) => let
          |  val context = Toplevel.context_of tls;
          |  val term = Syntax.parse_term context def_occur;
          | in Syntax.string_of_term context term end""".stripMargin
      )
    println(get_def(pisaos.toplevel, "1").force.retrieveNow)
  }
}
