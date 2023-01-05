package pisa.agent

import pisa.server.PisaOS
import de.unruh.isabelle.control.Isabelle
import scala.concurrent.ExecutionContext

object DepThms {
  val path_to_isa_bin: String = "/home/qj213/Isabelle2022"
  val working_directory: String = "/home/qj213/Isabelle2022/src/HOL/Computational_Algebra"
  val path_to_file: String = "/home/qj213/Isabelle2022/src/HOL/Computational_Algebra/Primes.thy"
  val theorem_string = "by (auto simp add: prime_int_iff')"

  def main(args: Array[String]): Unit = {
    val pisaos = new PisaOS(
      path_to_isa_bin=path_to_isa_bin,
      path_to_file=path_to_file,
      working_directory=working_directory
    )
    implicit val isabelle: Isabelle = pisaos.isabelle
    implicit val ec: ExecutionContext = pisaos.ec

    pisaos.step_to_transition_text(theorem_string, after = false)
    println(pisaos.getStateString(pisaos.toplevel))
    
    pisaos.top_level_state_map += ("default" -> pisaos.copy_tls)
    println("~"*50)
    println(pisaos.theorem_statement("default", "prime_nat_naive"))
  }
}
