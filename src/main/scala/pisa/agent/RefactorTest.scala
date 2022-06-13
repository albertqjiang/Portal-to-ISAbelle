package pisa.agent

import pisa.server.PisaOS

object RefactorTest {
  val path_to_isa_bin: String = "/home/qj213/Isabelle2021"
  val path_to_file: String = "/home/qj213/Isabelle2021/src/HOL/Fields.thy"
  val working_directory: String = "/home/qj213/Isabelle2021/src/HOL"
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

  }
}
