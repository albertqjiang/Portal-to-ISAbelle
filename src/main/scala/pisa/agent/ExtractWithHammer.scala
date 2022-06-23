package pisa.agent

import net.liftweb.json.DefaultFormats
import pisa.server.PisaOS
import java.io.PrintWriter

import scala.io.Source

object ExtractWithHammer {
  implicit val formats: DefaultFormats = DefaultFormats

  def main(args: Array[String]): Unit = {
    val path_to_isa_bin: String = "/home/qj213/Isabelle2021"
    val test_theorem_number: String = args(0)
    val test_theorem_path: String = s"data/hammer_extraction_$test_theorem_number.txt"
    val fileSource = Source.fromFile(test_theorem_path)
    val fileContents = fileSource.getLines.mkString
    fileSource.close
    val theory_path = fileContents.trim.replaceAll("/home/ywu/afp-2021-02-11", "/home/qj213/afp-2021-10-22")
    val thys_index = theory_path.split("/").indexOf("thys")
    val working_directory = {
      if (theory_path.contains("miniF2F")) "/home/qj213/afp-2021-10-22/thys/Symmetric_Polynomials"
      else theory_path.split("/").take(thys_index + 2).mkString("/")
    }
    val pisaos = new PisaOS(
      path_to_isa_bin=path_to_isa_bin,
      path_to_file=theory_path,
      working_directory=working_directory
    )
    val hammer_extraction_string = pisaos.parse_with_hammer
    new PrintWriter(s"data/hammer_extraction_results/$test_theorem_number.txt") {
      write(hammer_extraction_string)
      close()
    }
  }
}