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
  val working_directory: String = "/home/qj213/afp-2022-12-06/thys/Security_Protocol_Refinement"
  val path_to_file: String = "/home/qj213/afp-2022-12-06/thys/Security_Protocol_Refinement/Key_establish/m3_ds_par.thy"
  val problem1: String = "lemma corrKey_shrK_bad [simp]: \"corrKey = shrK`bad\""
  val problem2: String = "lemma PO_m3_inv1_lkeysec_init [iff]:\n  \"init m3 \\<subseteq> m3_inv1_lkeysec\""

  def main(args: Array[String]): Unit = {
    val pisaos = new PisaOS(
      path_to_isa_bin=path_to_isa_bin,
      path_to_file=path_to_file,
      working_directory=working_directory
    )
    println(pisaos.accumulative_step_to_theorem_end(problem1))
    println(pisaos.accumulative_step_to_theorem_end(problem2))

  }
}
