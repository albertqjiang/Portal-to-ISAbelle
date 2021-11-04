package pisa.server

import de.unruh.isabelle.control.Isabelle
import de.unruh.isabelle.pure.ToplevelState

import _root_.java.nio.file.{Files, Path}
import java.io._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.util.control.Breaks._

// Implicits
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._

class AutoConjecturer(val pisaos: PisaOS) {
  implicit val isabelle: Isabelle = pisaos.isabelle
  implicit val ec: ExecutionContext = pisaos.ec
  val file_content : String = Files.readString(Path.of(pisaos.path_to_file))

  val sorry_state_queue = new mutable.Queue[(ToplevelState, Int, String, Int)]

  def parse_through(sorry_element: (ToplevelState, Int, String, Int),
                    parsed_text: List[(Transition.T, String)]): String = {
    var state_action_total : String = ""
    var top_level_state = sorry_element._1
    val entire_proof_level = sorry_element._2
    var previous_proof_segment = sorry_element._3
    val starting_index = sorry_element._4
    breakable {
      var i : Int = starting_index
      while (i < parsed_text.length) {
        val parsed_element = parsed_text(i)
        val transition = parsed_element._1
        val text = parsed_element._2

        val proof_level_before_transition = pisaos.proof_level(top_level_state).retrieveNow
        breakable {
          if (text.trim.isEmpty) {
            break
          } else {
            state_action_total = state_action_total + (pisaos.getStateString(top_level_state) + "<\\STATESEP>" +
              previous_proof_segment + "<\\PROOFSEP>" + text.trim + "<\\TRANSEP>")
//            println(previous_proof_segment)
            top_level_state = pisaos.singleTransition(transition, top_level_state)
          }

          if ((text.trim.startsWith("lemma") || text.trim.startsWith("theorem")) &&
            !text.trim.startsWith("theorems") && !text.trim.startsWith("lemmas")) {
            previous_proof_segment = text.trim
          } else if (text.trim.nonEmpty) {
            previous_proof_segment += (" \n" + text.trim)
          }
        }
        val proof_level_after_transition = pisaos.proof_level(top_level_state).retrieveNow

        if ((!pisaos.is_proof(top_level_state).retrieveNow) ||
          (proof_level_after_transition == entire_proof_level)) break
        if (text.trim.startsWith("have")) {
          // When a have statement is found, record the instance, have a sorry right afterwards, and proceed
          sorry_state_queue += Tuple4(
            ToplevelState.instantiate(top_level_state.mlValue),
            proof_level_before_transition,
            previous_proof_segment,
            i+1
          )

          // Handle the thread with sorry instead of the actual proof by running a clone (scapegoat top level state)
          previous_proof_segment += " sorry"
          var tls_clone = ToplevelState.instantiate(top_level_state.mlValue)
          while (pisaos.proof_level(tls_clone).retrieveNow != proof_level_before_transition) {
            i += 1
            tls_clone = pisaos.singleTransition(parsed_text(i)._1, tls_clone)
          }
          top_level_state = pisaos.step("sorry", top_level_state)
        }

        i += 1
      }
    }
    state_action_total
  }

  var parsed_text : List[(Transition.T, String)] = null
  def initialise_file_theorems(isar_string: String, top_level_state: ToplevelState) : List[String] = {
    val theorem_declaration_list = ListBuffer[String]()
    var scapegoat_toplevel = ToplevelState.instantiate(top_level_state.mlValue)
    parsed_text = pisaos.parse_text(pisaos.thy1, isar_string).retrieveNow
    // Scan the whole file for all the theorems there are and put a mark on the start of each of them
    for (i <- List.range(0, parsed_text.length)) {
      val transition = parsed_text(i)._1
      val text = parsed_text(i)._2

      if (text.trim.isEmpty) {} else {
        scapegoat_toplevel = pisaos.singleTransition(transition, scapegoat_toplevel)
      }
      if ((text.trim.startsWith("theorem") || text.trim.startsWith("lemma")) &&
        !text.trim.startsWith("theorems") && !text.trim.startsWith("lemmas")) {
        theorem_declaration_list += text.replace("\n", " ").replaceAll(
          " +", " ").trim
        sorry_state_queue += Tuple4(
          ToplevelState.instantiate(scapegoat_toplevel.mlValue),
          pisaos.proof_level(top_level_state).retrieveNow,
          text.replace("\n", " ").replaceAll(" +", " ").trim,
          i+1
        )
      }
    }
    theorem_declaration_list.toList
  }

  def sorrying: String = {
    var total_string : String = ""
    while (sorry_state_queue.nonEmpty) {
      total_string += parse_through(sorry_state_queue.dequeue, parsed_text)
    }
    total_string
  }
}

object ConjecturerParser {
  def process_single_transition_string(single_transition_string: String) : (String, String) = {
    val proof_string = single_transition_string.split("""<\\PROOFSEP>""")(0).split(
    """<\\STATESEP>""")(1).replace("\n", " \\n ").replaceAll(" +", " ")
    val state_string = single_transition_string.split("""<\\STATESEP>""")(0).replace(
      "\n", " \\n ").replaceAll(" +", " ")
    val target_string = single_transition_string.split("""<\\PROOFSEP>""")(1).replace(
      "\n", " \\n ").replaceAll(" +", " ")
    ("Proof: " + proof_string + " State: " + state_string, target_string)
  }

  def process_returned_string(returned_string: String) : List[(String, String)] = {
    val list_of_transitions = returned_string.split("""<\\TRANSEP>""")
    list_of_transitions.toList.map(process_single_transition_string)
  }

  // Four arguments: path to file, working directory, isabelle location, and saving directory
  def main(args: Array[String]): Unit = {
    val path_to_file : String = args(0)
    val working_directory : String = args(1)
    val pisaos = new PisaOS(
      path_to_isa_bin=args(2),
      path_to_file=path_to_file,
      working_directory=working_directory)

    val conjecturer_parser = new AutoConjecturer(pisaos)
    val file_theorems = conjecturer_parser.initialise_file_theorems(
      conjecturer_parser.file_content, conjecturer_parser.pisaos.toplevel)
    val returned_text = conjecturer_parser.sorrying
    val processed_return = process_returned_string(returned_text)

    val saving_directory = args(3)
    val pure_file_name = path_to_file.split('/').last.split(".thy").head
    // Save the seq2seq texts
    val pw_src = new PrintWriter(new File(saving_directory+'/'+pure_file_name+".src"))
    val pw_tgt = new PrintWriter(new File(saving_directory+'/'+pure_file_name+".tgt"))
    for (seq2seq <- processed_return) {
      pw_src.write(seq2seq._1)
      pw_src.write("\n")
      pw_tgt.write(seq2seq._2)
      pw_tgt.write("\n")
    }
    pw_src.close()
    pw_tgt.close()

    // Save the theorem names
    val pw_theorem_name = new PrintWriter(new File(saving_directory+'/'+pure_file_name+"_theorem_names.txt"))
    for (theorem_name <- file_theorems) {
      pw_theorem_name.write(theorem_name)
      pw_theorem_name.write("\n")
    }
    pw_theorem_name.close()
  }
}