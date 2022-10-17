package pisa.agent

import de.unruh.isabelle.control.Isabelle
import pisa.server.PisaOS
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._

import scala.concurrent.ExecutionContext
import scala.util.control.Breaks

object RefactorTest {

  val path_to_isa_bin: String = "/private/home/aqj/Isabelle2021"
  val working_directory: String = "/private/home/aqj/Isabelle2021/src/HOL/Examples"
  val path_to_file: String = "/private/home/aqj/Isabelle2021/src/HOL/Examples/Drinker.thy"
  val theorem_string = """theory Drinker imports Main begin
  |  theorem 1: 
  |    fixes f:: "int \<Rightarrow> int"
  |      and g:: "int \<Rightarrow> int"
  |    assumes "\<forall> x. f x = 3 * x - 8"
  |      and "\<forall> x. g (f x) = 2 * x ^ 2 + 5 * x - 3"
  |    shows "g (-5) = 4"
  |  proof -
  |    have "f 1 = -5" 
  |      by (simp add: assms(1))
  |    then have "g (-5) = g (f 1)" 
  |      by auto
  |    then show ?thesis 
  |      by (simp add: assms(2))
  |  qed""".stripMargin
  def main(args: Array[String]): Unit = {
    val pisaos = new PisaOS(
      path_to_isa_bin=path_to_isa_bin,
      path_to_file=path_to_file,
      working_directory=working_directory
    )
    implicit val isabelle: Isabelle = pisaos.isabelle
    implicit val ec: ExecutionContext = pisaos.ec

    val continue = new Breaks
    val transition_and_index_list = pisaos.parse_text(pisaos.thy1, theorem_string).force.retrieveNow.zipWithIndex
    for (((transition, text), i) <- transition_and_index_list) {
      continue.breakable {
        if (text.trim.isEmpty) continue.break
        else if (text.trim=="end" && (i==transition_and_index_list.length-1)) continue.break
        else {
          pisaos.singleTransition(transition)
        }
      }
    }
    println(pisaos.total_facts_and_defs_string(pisaos.toplevel))
  }
}
