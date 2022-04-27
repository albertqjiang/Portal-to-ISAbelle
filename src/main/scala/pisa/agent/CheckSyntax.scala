package pisa.agent

import de.unruh.isabelle.control.Isabelle
import de.unruh.isabelle.mlvalue.MLFunction2
import de.unruh.isabelle.pure.{Theory, ToplevelState}
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._
import pisa.server.{PisaOS, Transition}

import _root_.java.nio.file.{Files, Path}
import java.io.PrintWriter
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext

class CheckSyntax(path_to_isa_bin: String, path_to_file: String, working_directory: String) {
  def divide_by_theorem(total_string: String): (String, List[String]) = {
    val keyword = "theorem"
    val split_theorems = total_string.split(keyword)
    split_theorems.foreach(println)
    val header = split_theorems.head
    (header, split_theorems.drop(1).map(x => keyword + x).toList)
  }

  def try_to_parse_theorem(theorem_string: String): Boolean = {
    var trial_state = pisaos.copy_tls.retrieveNow
    try {
      for ((_, text) <- parse_text(thy, theorem_string).force.retrieveNow) {
        trial_state = step(text, trial_state)
      }
      true
    } catch {
      case _: Throwable => false
    }
  }

  def parse_all_theorems(list_of_theorem_strings: List[String]): List[Boolean] = {
    list_of_theorem_strings.map(x => try_to_parse_theorem(x))
  }

  def get_all_parsable_theorems(list_of_theorem_strings: List[String]): List[String] = {
    list_of_theorem_strings.filter(x => try_to_parse_theorem(x))
  }

  def step(isar_string: String, top_level_state: ToplevelState, timeout_in_millis: Int = 10000): ToplevelState = {
    pisaos.step(isar_string, top_level_state, timeout_in_millis)
  }

  // Load PisaOS and useful methods and attributes
  val pisaos = new PisaOS(path_to_isa_bin = path_to_isa_bin, path_to_file = path_to_file, working_directory = working_directory)
  implicit val isabelle: Isabelle = pisaos.isabelle
  implicit val ec: ExecutionContext = pisaos.ec
  val thy: Theory = pisaos.thy1
  val parse_text: MLFunction2[Theory, String, List[(Transition.T, String)]] = pisaos.parse_text
  val top_level_state: ToplevelState = pisaos.toplevel
  // Names of all the parsable theorems
  var parsable_theorem_names: ListBuffer[String] = ListBuffer[String]()
  // String of the entire file
  val file_string: String = Files.readString(Path.of(path_to_file))
  val (header: String, individual_theorem_strings: List[String]) = divide_by_theorem(file_string)
  pisaos.step(header)
  val all_parsable_theorems : List[String] = get_all_parsable_theorems(individual_theorem_strings)
}

object CheckSyntax {
  def main(args: Array[String]): Unit = {
    val theory_path: String = args(0).trim
    val syntax_checker: CheckSyntax = new CheckSyntax(
      path_to_isa_bin = "/home/qj213/Isabelle2021",
      path_to_file = theory_path,
      working_directory = "/home/qj213/afp-2021-10-22/thys/Symmetric_Polynomials"
    )

    new PrintWriter("syntax_correct_theorem_names") {
      for (str <- syntax_checker.all_parsable_theorems) {
        write(str.replaceAll("\n", " ").replaceAll(" +", " ").trim)
        write("\n")
      }
      close()
    }
  }
}