package pisa.agent

import de.unruh.isabelle.control.Isabelle
import pisa.server.PisaOS

import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._

import scala.concurrent.ExecutionContext

object RefactorTest {
  val path_to_isa_bin: String = "/home/qj213/Isabelle2021"
  val path_to_file: String = "/home/qj213/Isabelle2021/src/HOL/Fields.thy"
  val working_directory: String = "/home/qj213/Isabelle2021/src/HOL"
  val inner_syntax_string: String = """lemma max_mult_distrib_right:
                                      |  fixes x::"'a::linordered_idom"
                                      |  shows "max x y * p = (if 0 \<le> p then max (x*p) (y*p) else min (x*p) (y*p))"
                                      |  """.stripMargin
  def main(args: Array[String]): Unit = {
    val pisaos = new PisaOS(
      path_to_isa_bin=path_to_isa_bin,
      path_to_file=path_to_file,
      working_directory=working_directory
    )
    implicit val isabelle: Isabelle = pisaos.isabelle
    implicit val ec: ExecutionContext = pisaos.ec

    val step_string = "by (simp add: max_mult_distrib_right divide_inverse)"
    pisaos.step_to_transition_text(step_string)
    pisaos.top_level_state_map += ("default" -> pisaos.copy_tls)
    println(pisaos.get_dependent_theorems(tls_name = "default", "max_divide_distrib_right"))
    for ((transition, text) <- pisaos.parse_text(pisaos.thy1, inner_syntax_string).force.retrieveNow) {
      println(text)
    }
  }
}
