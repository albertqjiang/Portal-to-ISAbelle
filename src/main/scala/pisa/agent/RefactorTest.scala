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

    val get_used_consts_strs: MLFunction2[ToplevelState, String, List[String]] = compileFunction[ToplevelState, String, List[String]](
      """fn(tls, inner_syntax) =>
        |  let
        |     val ctxt = Toplevel.context_of tls;
        |     val parsed = Syntax.parse_term ctxt inner_syntax;
        |     fun leaves (left $ right) = (leaves left) @ (leaves right)
        |     |   leaves t = [t];
        |     val all_consts = leaves parsed;
        |  in
        |     map (Syntax.string_of_term ctxt) all_consts
        |  end""".stripMargin
    )
    println(get_used_consts_strs(pisaos.toplevel, "1+1=2").force.retrieveNow)
  }
}
