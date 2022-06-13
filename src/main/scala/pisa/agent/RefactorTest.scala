package pisa.agent

import pisa.server.PisaOS

object RefactorTest {
  val path_to_isa_bin: String = "/home/qj213/Isabelle2021"
  val path_to_file: String = "/home/qj213/Isabelle2021/src/HOL/Fields.thy"
  val working_directory: String = "/home/qj213/Isabelle2021/src/HOL"
  val inner_syntax_string: String = """lemma max_mult_distrib_right:
                                      |  fixes x::"'a::linordered_idom"
                                      |  shows "max x y * p = (if 0 \<le> p then max (x*p) (y*p) else min (x*p) (y*p))"
                                      |by (auto simp add: min_def max_def mult_le_cancel_right)""".stripMargin
  def main(args: Array[String]): Unit = {
    val pisaos = new PisaOS(
      path_to_isa_bin=path_to_isa_bin,
      path_to_file=path_to_file,
      working_directory=working_directory
    )
    val step_string = "by (simp add: max_mult_distrib_right divide_inverse)"
    pisaos.step_to_transition_text(step_string)
    pisaos.top_level_state_map += ("default" -> pisaos.copy_tls)
    println(pisaos.get_dependent_theorems(tls_name = "default", "max_divide_distrib_right"))
    println(pisaos.get_all_definitions(tls_name = "default", theorem_string = inner_syntax_string))
  }
}
