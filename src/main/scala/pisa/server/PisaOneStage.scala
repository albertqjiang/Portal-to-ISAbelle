/*
This is adapted from contents in Dominique Unruh's package scala-isabelle
The package can be found on github at https://github.com/dominique-unruh/scala-isabelle
This particular file is adapted from https://github.com/dominique-unruh/scala-isabelle/blob/master/src/test/scala/de/unruh/isabelle/experiments/ExecuteIsar.scala
 */

package pisa.server

import io.grpc.Status
import zio.{ZEnv, ZIO}
import pisa.server.ZioServer.ZServer
import de.unruh.isabelle.pure.{Theory, ToplevelState}
import de.unruh.isabelle.control.IsabelleException
import de.unruh.isabelle.mlvalue.MLValue
import de.unruh.isabelle.control.Isabelle
import de.unruh.isabelle.pure.Implicits._
import de.unruh.isabelle.mlvalue.Implicits._

import scala.concurrent.{ExecutionContext, TimeoutException}
import java.io.PrintWriter
import scala.util.control.Breaks

class OneStageBody extends ZServer[ZEnv, Any] {
  var pisaos: PisaOS = null
  var stand_in_thy: MLValue[Theory] = null
  var stand_in_tls: MLValue[ToplevelState] = null
  var isaPath: String = null
  var isaWorkingDirectory: String = null

  def initialiseIsabelle(isa_path: IsaPath): ZIO[zio.ZEnv, Status, IsaMessage] = {
    isaPath = isa_path.path
    ZIO.succeed(IsaMessage(s"You entered the path to the Isabelle executable: ${isa_path.path} \n" +
      s"We have successfully received it."))
  }

  def isabelleWorkingDirectory(isa_working_directory: IsaPath): zio.ZIO[zio.ZEnv, Status, IsaMessage] = {
    isaWorkingDirectory = isa_working_directory.path
    ZIO.succeed(IsaMessage(s"You entered the path to the Isabelle working directory: ${isaWorkingDirectory} " +
      s"We have successfully received it."))
  }

  def isabelleContext(path_to_file: IsaContext): ZIO[zio.ZEnv, Status, IsaMessage] = {
    pisaos = new PisaOS(path_to_isa_bin = isaPath, path_to_file = path_to_file.context,
      working_directory = isaWorkingDirectory)
    stand_in_thy = pisaos.thy1.mlValue
    stand_in_tls = pisaos.copy_tls
    ZIO.succeed(IsaMessage(s"You entered the path to the Theory file: ${path_to_file.context} \n" +
      s"We have successfully initialised the Isabelle environment."))
  }

  def reset_problem(): Unit = {
    implicit val isabelle: Isabelle = pisaos.isabelle
    implicit val ec: ExecutionContext = pisaos.ec
    pisaos.thy1 = stand_in_thy.force.retrieveNow
    pisaos.toplevel = stand_in_tls.force.retrieveNow
    pisaos.reset_map()
    pisaos.register_tls("default", pisaos.toplevel)
  }

  def deal_with_reset_problem(): String = {
    reset_problem()
    "The problem is reset."
  }

  def deal_with_extraction(): String = pisaos.step("PISA extract data")

  def deal_with_extraction_with_hammer(): String = pisaos.step("PISA extract data with hammer")

  def deal_with_list_states(): String = pisaos.top_level_state_map.keys.mkString(" | ")

  def deal_with_initialise(): String = {
    pisaos.top_level_state_map += ("default" -> pisaos.copy_tls)
    "Toplevel state 'default' is ready"
  }

  def deal_with_get_state(toplevel_state_name: String): String = {
    if (pisaos.top_level_state_map.contains(toplevel_state_name)) pisaos.getStateString(pisaos.retrieve_tls(toplevel_state_name))
    else s"Didn't find top level state of given name: ${toplevel_state_name}"
  }

  def deal_with_is_finished(toplevel_state_name: String): String = {
    if (pisaos.top_level_state_map.contains(toplevel_state_name)) {
      if (pisaos.getProofLevel(pisaos.retrieve_tls(toplevel_state_name)) == 0) "true"
      else "false"
    } else s"Didn't find top level state of given name: ${toplevel_state_name}"
  }

  val TRY_STRING: String = "Try this:"
  val FOUND_PROOF_STRING: String = "found a proof:"
  val ERROR_MSG: String = "error"
  val GAP_STEP: String = "sledgehammer"
  val TIME_STRING1: String = " ms)"
  val TIME_STRING2: String = " s)"

  def process_hammer_strings(hammer_string_list: List[String]): String = {
    var found = false
    for (attempt_string <- hammer_string_list) {
      if (!found && (attempt_string contains TRY_STRING)) {
        found = true
        val parsed = attempt_string.split(TRY_STRING).drop(1).mkString("").trim
        if ((parsed contains TIME_STRING1) || (parsed contains TIME_STRING2)) {
          return parsed.split('(').dropRight(1).mkString("(").trim
        }
        return parsed
      } else if (!found && (attempt_string contains FOUND_PROOF_STRING)) {
        found = true
        val parsed = attempt_string.split(FOUND_PROOF_STRING).drop(1).mkString("").trim
        if ((parsed contains TIME_STRING1) || (parsed contains TIME_STRING2)) {
          return parsed.split('(').dropRight(1).mkString("(").trim
        }
        return parsed
      }
    }
    ""
  }

  def deal_with_apply_to_tls(toplevel_state_name: String, action: String, new_name: String): String = {
    if (pisaos.top_level_state_map.contains(toplevel_state_name)) {
      var actual_timeout = 10000
      val old_state: ToplevelState = pisaos.retrieve_tls(toplevel_state_name)
      var actual_step: String = "Gibberish"

      if (action == GAP_STEP) {
        // If found a sledgehammer step, execute it differently
        var raw_hammer_strings = List[String]()
        try {
          val total_result = pisaos.exp_with_hammer(old_state, timeout_in_millis=120000)
          val success = total_result._1
          
          if (success) {
            println("Hammer string list: " + total_result._2.mkString(" ||| "))
            actual_step = process_hammer_strings(total_result._2)
            println("actual_step: " + actual_step)
          }
        } catch {
          case _: TimeoutException => {
            println("Sledgehammer timeout 1")
            try {
              val total_result = pisaos.exp_with_hammer(old_state, timeout_in_millis=5000)
              val success = total_result._1
              if (success) {
                println("Hammer string list: " + total_result._2.mkString(" ||| "))
                actual_step = process_hammer_strings(total_result._2)
                println("actual_step: " + actual_step)
              }
            } catch {
              case e: TimeoutException => {
                println("Sledgehammer timeout 2")
                return s"$ERROR_MSG: ${e.getMessage}"
              }
            }
          }
          case e: Exception => {
            println("Exception while trying to run sledgehammer: " + e.getMessage)
          }
        }
        // println(actual_step)
        assert(actual_step.trim.nonEmpty)
      } else {
        actual_step = action
      }
      // println("Actual step: " + actual_step)

      val new_state: ToplevelState = pisaos.step(actual_step, old_state, actual_timeout)
      // println("Application successful")
      // println("New state: " + pisaos.getStateString(new_state))
      
      pisaos.register_tls(name = new_name, tls = new_state)
      if (action.trim == "sledgehammer") {
        s"$actual_step <hammer> ${pisaos.getStateString(new_state)}"
      } else s"${pisaos.getStateString(new_state)}"
    }
    else s"Didn't find top level state of given name: ${toplevel_state_name}"
  }

  def deal_with_proof_level(toplevel_state_name: String): String = {
    if (pisaos.top_level_state_map.contains(toplevel_state_name)) {
      val tls: ToplevelState = pisaos.retrieve_tls(toplevel_state_name)
      s"${pisaos.getProofLevel(tls)}"
    } else s"Didn't find top level state of given name: ${toplevel_state_name}"
  }

  def deal_with_proceed_before(true_command: String): String = pisaos.step_to_transition_text(true_command, after = false)

  def deal_with_proceed_after(true_command: String): String = pisaos.step_to_transition_text(true_command, after = true)

  def deal_with_exit(command: String): String = {
    pisaos.step(command)
    pisaos = null
    "Exited"
  }

  def deal_with_clone(old_name: String, new_name: String): String = {
    pisaos.clone_tls(old_name, new_name)
    "Successfully copied top level state named: " + new_name
  }

  def deal_with_delete(toplevel_state_name: String): String = {
    if (pisaos.top_level_state_map.contains(toplevel_state_name)) {
      pisaos.top_level_state_map -= toplevel_state_name
      s"Successfully deleted state named: ${toplevel_state_name}"
    } else s"Didn't find top level state of given name: ${toplevel_state_name}"
  }

  def deal_with_local_facts_and_defs(toplevel_state_name: String): String = {
    if (pisaos.top_level_state_map.contains(toplevel_state_name)) {
      pisaos.local_facts_and_defs_string(toplevel_state_name)
    } else s"Didn't find top level state of given name: ${toplevel_state_name}"
  }

  def deal_with_global_facts_and_defs(toplevel_state_name: String): String = {
    if (pisaos.top_level_state_map.contains(toplevel_state_name)) {
      pisaos.global_facts_and_defs_string(toplevel_state_name)
    } else s"Didn't find top level state of given name: ${toplevel_state_name}"
  }

  def deal_with_total_facts_and_defs(toplevel_state_name: String): String = {
    if (pisaos.top_level_state_map.contains(toplevel_state_name)) {
      pisaos.total_facts_and_defs_string(toplevel_state_name)
    } else s"Didn't find top level state of given name: ${toplevel_state_name}"
  }

  def deal_with_get_all_defs(theorem_string: String): String = {
    val tls_name = "default"
    pisaos.get_all_definitions(tls_name, theorem_string).mkString("\n")
  }

  def deal_with_global_facts_from_file: String = {
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
    pisaos.global_facts_and_defs_string(pisaos.toplevel)
  }

  def deal_with_parse_text(text: String): String = {
    implicit val isabelle: Isabelle = pisaos.isabelle
    implicit val ec: ExecutionContext = pisaos.ec
    val parsed = pisaos.parse_text(pisaos.thy1, text.trim).force.retrieveNow
    parsed.map(x => x._2).filter(_.trim.nonEmpty).mkString("<SEP>")
  }

  def deal_with_accummulative_step_before(text: String) = {
    pisaos.accumulative_step_to_before_transition_starting(text)
  }

  def isabelleCommand(isa_command: IsaCommand): ZIO[
    zio.ZEnv, Status, IsaState] = {
      val proof_state: String = {
        if (isa_command.command.trim == "PISA extract data") deal_with_extraction()
        else if (isa_command.command.trim == "PISA extract data with hammer") deal_with_extraction_with_hammer()
        else if (isa_command.command.startsWith("<accumulative step before>")) {
          val text = isa_command.command.stripPrefix("<accumulative step before>")
          deal_with_accummulative_step_before(text)
        }
        else if (isa_command.command.trim.startsWith("<parse text>")) {
          val text = isa_command.command.trim.stripPrefix("<parse text>")
          deal_with_parse_text(text)
        }
        else if (isa_command.command.trim.startsWith("<get all definitions>")) {
          val theorem_string: String = isa_command.command.stripPrefix("<get all definitions>").trim
          deal_with_get_all_defs(theorem_string)
        }
        else if (isa_command.command.startsWith("<local facts and defs>")) {
          val tls_name: String = isa_command.command.stripPrefix("<local facts and defs>").trim
          deal_with_local_facts_and_defs(tls_name)
        }
        else if (isa_command.command.startsWith("<global facts and defs>")) {
          val tls_name: String = isa_command.command.stripPrefix("<global facts and defs>").trim
          deal_with_global_facts_and_defs(tls_name)
        }
        else if (isa_command.command.startsWith("<total facts and defs>")) {
          val tls_name: String = isa_command.command.stripPrefix("<total facts and defs>").trim
          deal_with_total_facts_and_defs(tls_name)
        }
        else if (isa_command.command.startsWith("<get global facts from file>")) {
          deal_with_global_facts_from_file
        }
        else if (isa_command.command.startsWith("<list states>")) deal_with_list_states()
        else if (isa_command.command.startsWith("<initialise>")) deal_with_initialise()
        else if (isa_command.command.startsWith("<get state>")) {
          val tls_name: String = isa_command.command.stripPrefix("<get state>").trim
          deal_with_get_state(tls_name)
        }
        else if (isa_command.command.startsWith("<is finished>")) {
          val tls_name: String = isa_command.command.split("<is finished>").last.trim
          deal_with_is_finished(tls_name)
        }
        else if (isa_command.command.startsWith("<apply to top level state>")) {
          val tls_name: String = isa_command.command.split("<apply to top level state>")(1).trim
          val action: String = isa_command.command.split("<apply to top level state>")(2).trim
          val new_name: String = isa_command.command.split("<apply to top level state>")(3).trim
          try {
            deal_with_apply_to_tls(tls_name, action, new_name)
          } catch {
            case e: IsabelleException => {
              println("Action: " + action)
              println("IsabelleException: " + e.getMessage + "\n")
              "Step error: " + e.getMessage
            }
            case e: Throwable => {
              println("Action: " + action)
              println("Unknown error: " + e.getMessage + "\n")
              "Unknown error: " + e.getMessage
            }
          }
        }
        else if (isa_command.command.startsWith("<get_proof_level>")) {
          val tls_name: String = isa_command.command.stripPrefix("<get_proof_level>").trim
          deal_with_proof_level(tls_name)
        }
        else if (isa_command.command.startsWith("<proceed before>")) {
          val true_command: String = isa_command.command.stripPrefix("<proceed before>").trim
          deal_with_proceed_before(true_command)
        }
        else if (isa_command.command.startsWith("<proceed after>")) {
          val true_command: String = isa_command.command.stripPrefix("<proceed after>").trim
          deal_with_proceed_after(true_command)
        }
        else if (isa_command.command.trim.startsWith("<clone>")) {
          val old_name: String = isa_command.command.trim.split("<clone>")(1).trim
          val new_name: String = isa_command.command.trim.split("<clone>")(2).trim
          deal_with_clone(old_name, new_name)
        }
        else if (isa_command.command.trim.startsWith("<delete>")) {
          val tls_name: String = isa_command.command.trim.stripPrefix("<delete>").trim
          deal_with_delete(tls_name)
        }
        else if (isa_command.command.trim == "<get_ancestors>") {
          val ancestors_names_list: List[String] = pisaos.get_theory_ancestors_names(pisaos.thy1)
          ancestors_names_list.mkString(",")
        }

        else if (isa_command.command == "exit") deal_with_exit(isa_command.command)
        else "Unrecognised operation."
      }
      ZIO.succeed(IsaState(proof_state))
  }

  def isabelleSetSearchWidth(request: IsaSearchWidth): ZIO[zio.ZEnv with Any, Status, IsaMessage] = {
    ZIO.succeed(IsaMessage(s"This shouldn't be used here."))
  }

  def isabelleSearchIndexCommand(request: IsaSearchIndexCommand):
  ZIO[zio.ZEnv with Any, Status, IsaState] =
    ZIO.succeed(IsaState(s"This shouldn't be used here."))
}

object PisaOneStage {
  val path_to_isa_bin: String = "/home/qj213/Isabelle2021"
  val path_to_afp: String = "/home/qj213/afp-2021-10-22"

  def main(args: Array[String]): Unit = {
    //    val path_to_file: String = s"$path_to_afp/thys/Game_Based_Crypto/Guessing_Many_One.thy"
    val path_to_file: String = "/home/qj213/miniF2F/isabelle/valid/amc12_2000_p15.thy"
    //    val working_directory: String = s"$path_to_afp/thys/Game_Based_Crypto"
    val working_directory: String = "/home/qj213/miniF2F/isabelle/valid"
    val pisaos = new PisaOS(
      path_to_isa_bin = path_to_isa_bin,
      path_to_file = path_to_file,
      working_directory = working_directory)
    //    val theorem_name = """lemma accepts_conv_steps: "accepts A w = (\<exists>q. (start A,q) \<in> steps A w \<and> fin A q)"""".stripMargin
    //    val parsed : String = pisaos.step("PISA extract data")
    val parsed: String = pisaos.step("PISA extract data")
    //    val parsed : String = pisaos.step_to_transition_text(theorem_name)
    println(parsed)
    //    println(pisaos.step("by(simp add: delta_conv_steps accepts_def)"))
  }
}

object PisaMini {
  val path_to_isa_bin: String = "/home/qj213/Isabelle2021"
  val path_to_mini: String = "/home/qj213/miniF2F/isabelle"

  def main(args: Array[String]): Unit = {
    val path_to_file: String = s"$path_to_mini/valid/aime_1983_p9.thy"
    val working_directory: String = s"$path_to_mini/valid"
    val pisaos = new PisaOS(
      path_to_isa_bin = path_to_isa_bin,
      path_to_file = path_to_file,
      working_directory = working_directory)
    val theorem_name = """theorem aime_1983_p9: fixes x::real assumes "0<x" "x<pi" shows "12 \<le> ((9 * (x^2 * (sin x)^2)) + 4) / (x * sin x)"""".stripMargin
    val parsed: String = pisaos.step("PISA extract data")
    //    val parsed : String = pisaos.step_to_transition_text(theorem_name)
    println(parsed)
    //    println(pisaos.step("by(simp add: delta_conv_steps accepts_def)"))
  }
}

object PisaExtraction {
  val path_to_isa_bin: String = "/home/qj213/Isabelle2021"
  val path_to_afp: String = "/home/qj213/afp-2021-10-22"

  def main(args: Array[String]): Unit = {
    val path_to_file: String = args(0)
    val working_directory: String = args(1)
    val dump_path: String = args(2)
    val pisaos = new PisaOS(
      path_to_isa_bin = path_to_isa_bin,
      path_to_file = path_to_file,
      working_directory = working_directory
    )
    new PrintWriter(dump_path) {
      write(pisaos.parse_with_hammer);
      close()
    }
  }
}


