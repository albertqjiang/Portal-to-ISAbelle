package pisa.agent

import scala.concurrent.ExecutionContext
import scala.util.control.Breaks
import scala.collection.mutable.ListBuffer
import java.util.concurrent.TimeoutException

import de.unruh.isabelle.control.Isabelle
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._

import pisa.server.PisaOS

object CloseGaps {
  val ERROR_MSG: String = "error"
  val GAP_STEP: String = "sledgehammer"
  val TRY_STRING: String = "Try this:"

  def get_and_execute_step(os: PisaOS, step: String): String = {
    var actual_step: String = "Gibberish"
    if (step == GAP_STEP) {
      // If found a sledgehammer step, execute it differently
      var raw_hammer_strings = List[String]()
      try {
        raw_hammer_strings = os.prove_with_hammer(os.toplevel)._2
      } catch {
        case _: TimeoutException => {
          try {
            raw_hammer_strings = os.prove_with_hammer(os.toplevel, timeout_in_millis=5000)._2
          } catch {
            case e: TimeoutException => {
              return s"$ERROR_MSG: ${e.getMessage}"
            }
          }
        }
      }
      
      var found = false
      for (attempt_string <- raw_hammer_strings) {
        if (!found && (attempt_string contains TRY_STRING)) {
          found = true
          actual_step = attempt_string.stripPrefix(TRY_STRING).trim.split('(').dropRight(1).mkString("(")
        }
      }
    } else {
      actual_step = step
    }

    try {
      os.step(actual_step)
      actual_step
    } catch {
      case e: Exception => s"$ERROR_MSG: ${e.getMessage}"
    }
  }

  def parse_whole_file(os: PisaOS): (List[String], Boolean, String) = {
    implicit val isabelle: Isabelle = os.isabelle
    implicit val ec: ExecutionContext = os.ec
    
    var parsed_steps = ListBuffer[String]()
    val continue = new Breaks
    Breaks.breakable {
      for ((transition, text) <- os.parse_text(os.thy1, os.fileContentCopy).force.retrieveNow)
        continue.breakable {
          if (text.trim.isEmpty) continue.break
          val step_result = get_and_execute_step(os, text.trim)
          if (step_result.startsWith(ERROR_MSG))
            return (parsed_steps.toList, false, text.trim)
          parsed_steps += step_result
        }
    }
    (parsed_steps.toList, true, "")
  }

  def main(args: Array[String]): Unit = {
    val path_to_isa_bin: String = args(0)
    val working_directory: String = args(1)
    val path_to_file: String = args(2)
    val dump_path: String = args(3)

    val pisaos = new PisaOS(
      path_to_isa_bin=path_to_isa_bin,
      path_to_file=path_to_file,
      working_directory=working_directory
    )
    
    var result_string: String = ""
    val (parsed_steps, success, failed_step) = parse_whole_file(pisaos)
    
    if (success) {
      println(parsed_steps.mkString("\n"))
    } else {
      result_string += s"$ERROR_MSG: Could not parse file\n"
      val successful_step_string = "Success:   " + parsed_steps.map(_.replaceAll("\n", "\nSuccess:   ")).mkString("\nSuccess:   ")
      result_string += s"$ERROR_MSG:\n$successful_step_string\n"
      result_string += s"Failed at\n---------> ${failed_step} <---------\n"
    }

    import java.io.PrintWriter
    new PrintWriter(dump_path) { write(result_string); close }
  }
}

