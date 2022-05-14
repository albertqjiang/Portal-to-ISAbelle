package pisa.agent

import de.unruh.isabelle.control.IsabelleException
import de.unruh.isabelle.pure.ToplevelState
import net.liftweb.json.{DefaultFormats, parse}
import pisa.server.PisaOS

import java.io.PrintWriter
import scala.concurrent.TimeoutException
import scala.io.Source

object PisaHammerTest {
  implicit val formats: DefaultFormats = DefaultFormats

  def get_proved(pisaos: PisaOS): Boolean = {
    val hammer_results = pisaos.prove_with_hammer(pisaos.toplevel, 120000)
    val hammered_string =
      if (hammer_results._1) {
        val hammer_strings = hammer_results._2
        var found = false
        var real_string = ""
        for (attempt_string <- hammer_strings) {
          if (!found && (attempt_string contains "Try this:")) {
            found = true
            real_string = attempt_string.trim.stripPrefix("Try this:").trim.split('(').dropRight(1).mkString("(")
          }
        }
        if (found) real_string
        else throw IsabelleException("Hammered said it worked but didn't find proof.")
      } else {
        throw IsabelleException("Hammer failed")
      }

    val new_state = pisaos.step(hammered_string, pisaos.toplevel, timeout_in_millis = 10000)
    pisaos.getProofLevel(new_state) == 0
  }

  def apply_to_top_level_state(pisaOS: PisaOS, tls_name: String, isar_string: String): Boolean = {
    pisaOS.clone_tls(tls_name)
    try {
      val resulting_tls: ToplevelState = pisaOS.step(isar_string, pisaOS.retrieve_tls(tls_name))
      val level: Int = pisaOS.getProofLevel(resulting_tls)
      level == 0
    } catch {
      case _: Throwable => false
    }
  }

  def main(args: Array[String]): Unit = {
    val test_theorem_number: String = args(0).split('/').last.split('.').head.split('_').last
    val try_heuristics: Boolean = args(1).trim.toBoolean
    val dump_path: String = "results/hammer_eval"
    val json_element = parse(
      {
        val textSource = Source.fromFile(args(0))
        val str = textSource.mkString
        textSource.close()
        str
      }
    ).children.head
    val theory_path = json_element(0).extract[String].replaceAll("/home/ywu/afp-2021-02-11", "/home/qj213/afp-2021-10-22")
    val thys_index = theory_path.split("/").indexOf("thys")
    val working_directory = {
      if (theory_path.contains("miniF2F")) "/home/qj213/afp-2021-10-22/thys/Symmetric_Polynomials"
      else theory_path.split("/").take(thys_index + 2).mkString("/")
    }
    val theorem_name = json_element(1).extract[String].replaceAll("\n", " ").replaceAll(" +", " ").trim
    val pisaos = new PisaOS(
      path_to_isa_bin = "/home/qj213/Isabelle2021",
      path_to_file = theory_path,
      working_directory = working_directory
    )
    pisaos.step_to_transition_text(theorem_name)
    if (theorem_name.contains("assumes")) pisaos.step("using assms")

    var proved: Boolean = false
    if (try_heuristics) {
      if (apply_to_top_level_state(pisaos, "1", "by auto")) {
        proved = true
      } else if (apply_to_top_level_state(pisaos, "2", "by simp")) {
        proved = true
      } else if (apply_to_top_level_state(pisaos, "3", "by blast")) {
        proved = true
      } else if (apply_to_top_level_state(pisaos, "4", "by fastforce")) {
        proved = true
      } else if (apply_to_top_level_state(pisaos, "5", "by force")) {
        proved = true
      } else if (apply_to_top_level_state(pisaos, "6", "by eval")) {
        proved = true
      } else if (apply_to_top_level_state(pisaos, "7", "by presburger")) {
        proved = true
      } else if (apply_to_top_level_state(pisaos, "8", "by sos")) {
        proved = true
      } else if (apply_to_top_level_state(pisaos, "9", "by arith")) {
        proved = true
      }
    }

    if (!proved) {
      proved = try {
        get_proved(pisaos)
      } catch {
        case _: IsabelleException => false
        case _: TimeoutException => false
      }
    }

    new PrintWriter(s"$dump_path/$test_theorem_number" + "_hammer.out") {
      write(proved.toString)
      close()
    }
    new PrintWriter(s"$dump_path/$test_theorem_number" + "_hammer.info") {
      write(theory_path)
      write("\n")
      write(theorem_name)
      close()
    }
  }
}
