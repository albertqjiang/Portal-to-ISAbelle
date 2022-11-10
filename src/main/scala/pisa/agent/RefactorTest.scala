package pisa.agent


import de.unruh.isabelle.control.Isabelle
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._
import de.unruh.isabelle.pure.ToplevelState
import pisa.server.PisaOS

import scala.concurrent.ExecutionContext
import scala.util.control.Breaks
import java.io._
import java.util.Base64
import java.nio.charset.StandardCharsets.UTF_8

object RefactorTest {
  val path_to_isa_bin: String = "/home/qj213/Isabelle2021"
  val working_directory: String = "/home/qj213/afp-2021-10-22/thys/RefinementReactive"
  val path_to_file: String = "/home/qj213/afp-2021-10-22/thys/RefinementReactive/Temporal.thy"
  val theorem_string = """lemma until_always: "(INF n. (SUP i \<in> {i. i < n} . - p i) \<squnion> ((p :: nat \<Rightarrow> 'a) n)) \<le> p n"""".stripMargin

  def main(args: Array[String]): Unit = {
    val pisaos = new PisaOS(
      path_to_isa_bin=path_to_isa_bin,
      path_to_file=path_to_file,
      working_directory=working_directory
    )
    implicit val isabelle: Isabelle = pisaos.isabelle
    implicit val ec: ExecutionContext = pisaos.ec

    pisaos.step_to_transition_text(theorem_string, after = true)

    println(pisaos.exp_with_hammer(pisaos.toplevel))
  }
}
