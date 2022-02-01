package pisa.agent

import de.unruh.isabelle.control.IsabelleException
import net.liftweb.json.{DefaultFormats, parse}
import pisa.server.PisaOS

import java.io.PrintWriter
import scala.concurrent.TimeoutException
import scala.io.Source

object PisaHammerTest {
  implicit val formats: DefaultFormats = DefaultFormats

  def main(args: Array[String]): Unit = {
    val test_theorem_number: String = args(0).split('/').last.split('.').head.split('_').last
    val dump_path: String = "results/hammer_eval"
    val change_to_my_repo : String = args(0).replaceAll("/home/ywu", "/home/qj213")
    val json_element = parse(Source.fromFile(change_to_my_repo).mkString).children(0)
    val theory_path = json_element(0).extract[String]
    val thys_index = theory_path.split("/").indexOf("thys")
    val working_directory = theory_path.split("/").take(thys_index + 2).mkString("/")
    val theorem_name = json_element(1).extract[String].replaceAll("\n", " ").replaceAll(" +", " ").trim
    val pisaos = new PisaOS(
      path_to_isa_bin = "/home/qj213/Isabelle2021",
      path_to_file = theory_path,
      working_directory = working_directory
    )
    pisaos.step_to_transition_text(theorem_name)

    val proved =
      try {
        pisaos.check_if_provable_with_Sledgehammer()
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
