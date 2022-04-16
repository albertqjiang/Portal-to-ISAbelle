package pisa.agent

import de.unruh.isabelle.control.IsabelleException
import net.liftweb.json.{DefaultFormats, parse}
import pisa.server.PisaOS

import java.io.PrintWriter
import scala.concurrent.TimeoutException
import scala.io.Source

object PisaHammerTest {
  implicit val formats: DefaultFormats = DefaultFormats

  def get_proved(pisaos: PisaOS) : Boolean = {
    val hammer_results = pisaos.prove_with_hammer(pisaos.toplevel)
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

  def main(args: Array[String]): Unit = {
    val test_theorem_number: String = args(0).split('/').last.split('.').head.split('_').last
    val dump_path: String = "results/hammer_eval"
    val json_element = parse(Source.fromFile(args(0)).mkString).children(0)
    val theory_path = json_element(0).extract[String].replaceAll("/home/ywu/afp-2021-02-11", "/home/qj213/afp-2021-10-22")
    val thys_index = theory_path.split("/").indexOf("thys")
    val working_directory = theory_path.split("/").take(thys_index + 2).mkString("/")
    val theorem_name = json_element(1).extract[String].replaceAll("\n", " ").replaceAll(" +", " ").trim
    val pisaos = new PisaOS(
      path_to_isa_bin = "/home/qj213/Isabelle2021",
      path_to_file = theory_path,
      working_directory = working_directory
    )
    pisaos.step_to_transition_text(theorem_name)

    val proved : Boolean =
      try {
        get_proved(pisaos)
      } catch {
        case _: IsabelleException => false
        case _: TimeoutException => false
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
