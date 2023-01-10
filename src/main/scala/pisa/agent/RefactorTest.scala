package pisa.agent


import de.unruh.isabelle.control.Isabelle
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._
import de.unruh.isabelle.pure.ToplevelState
import pisa.server.PisaOS

import scala.concurrent._
import scala.concurrent.ExecutionContext
import scala.concurrent.blocking
import scala.concurrent.ExecutionContext.Implicits._
import scala.util.control.Breaks
import java.util.concurrent.CancellationException
import java.io._
import java.util.Base64
import java.nio.charset.StandardCharsets.UTF_8

object RefactorTest {
  val path_to_isa_bin: String = "/home/qj213/Isabelle2022"
  val working_directory: String = "/home/qj213/afp-2022-12-06/thys/Formal_SSA"
  val path_to_file: String = "/home/qj213/afp-2022-12-06/thys/Formal_SSA/Construct_SSA.thy"
  val problem1: String = "lemma phiDefNodes_aux_cases:\n    obtains (nonrec) \"phiDefNodes_aux g v unvisited n = {}\" \"(n \\<notin> set unvisited \\<or> v \\<in> defs g n)\"\n    | (rec) \"phiDefNodes_aux g v unvisited n = fold union (map (phiDefNodes_aux g v (removeAll n unvisited)) (predecessors g n))\n          (if length (predecessors g n) = 1 then {} else {n})\"\n       \"n \\<in> set unvisited\" \"v \\<notin> defs g n\""
  val problem2: String = "lemma phiDefNode_aux_is_join_node:\n    assumes \"n \\<in> phiDefNodes_aux g v un m\"\n    shows \"length (predecessors g n) \\<noteq> 1\""

  def main(args: Array[String]): Unit = {
    val pisaos = new PisaOS(
      path_to_isa_bin=path_to_isa_bin,
      path_to_file=path_to_file,
      working_directory=working_directory
    )
    println(pisaos.accumulative_step_to_theorem_end(problem1))
    pisaos.top_level_state_map += ("default" -> pisaos.copy_tls)
    println(pisaos.get_dependent_theorems("default", "Construct_SSA.CFG_Construct.phiDefNodes_aux_cases"))
    println(pisaos.accumulative_step_to_theorem_end(problem2))
    pisaos.top_level_state_map += ("default" -> pisaos.copy_tls)
    println(pisaos.get_dependent_theorems("default", "phiDefNode_aux_is_join_node"))
  }
}
