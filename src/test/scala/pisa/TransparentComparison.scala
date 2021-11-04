package pisa

import de.unruh.isabelle.control.{Isabelle, IsabelleException}
import de.unruh.isabelle.pure.ToplevelState
import net.liftweb.json._
import scala.sys.process._
import pisa.server.PisaOS
import java.io.PrintWriter

import scala.concurrent.ExecutionContext
import scala.io.Source

object TransparentComparison {
  implicit val formats : DefaultFormats = DefaultFormats
  implicit val ec: ExecutionContext = ExecutionContext.global
  var pisaos : PisaOS = null
  var theorem_name : String = null
  var state_only_src2tgt : Map[String, String] = Map()
  var proof_and_state_src2tgt : Map[String, String] = Map()
  var oracle_proof_till_now : String = ""
  var agent_proof_till_now : String = ""

  def register(path_to_isa_bin: String, path_to_file: String, working_directory: String, tn: String) : Unit = {
    theorem_name = tn.replaceAll("\n", " ").replaceAll(" +", " ").trim
    oracle_proof_till_now += theorem_name
    agent_proof_till_now += theorem_name
    pisaos = new PisaOS(
      path_to_isa_bin = path_to_isa_bin,
      path_to_file = path_to_file,
      working_directory = working_directory
    )
  }

  def get_ground_truth(theorem_number: Int) : Unit = {
//    val state_only_ground_truth_path_prefix : String = "debug/with_state/"
    val proof_and_state_ground_truth_path_prefix : String = "debug/with_proof_and_state/"

//    val state_only_ground_truth_text_path = state_only_ground_truth_path_prefix + s"${theorem_number}_pairs.txt"
//    val state_only_ground_truth_open_source = Source.fromFile(state_only_ground_truth_text_path)
//    val state_only_ground_truth_list : List[String] = state_only_ground_truth_open_source.getLines.toList
//    state_only_ground_truth_open_source.close
//
//    for (line <- state_only_ground_truth_list) {
//      val src_tgt_pair = line.split("<SEP>")
//      val src : String = src_tgt_pair.head.trim
//      val tgt : String = src_tgt_pair.last.trim
//      state_only_src2tgt += (src -> tgt)
//    }
//    println(state_only_src2tgt)

    val proof_and_state_ground_truth_text_path = proof_and_state_ground_truth_path_prefix + s"${theorem_number}_pairs.txt"
    val proof_and_state_ground_truth_open_source = Source.fromFile(proof_and_state_ground_truth_text_path)
    val proof_and_state_ground_truth_list : List[String] = proof_and_state_ground_truth_open_source.getLines.toList
    proof_and_state_ground_truth_open_source.close

    for (line <- proof_and_state_ground_truth_list) {
      val src_tgt_pair = line.split("<SEP>")
      val src : String = src_tgt_pair.head.trim
      val tgt : String = src_tgt_pair.last.trim
      proof_and_state_src2tgt += (src -> tgt)
    }
    println(proof_and_state_src2tgt)
  }

  def state_only_get_request_string(state_only_toplevel : ToplevelState) : String = {
    val state_string : String = pisaos.getStateString(state_only_toplevel).replace("\\", "\\\\").replace(
      "\"", "\\\"").replace("\n", " ").replaceAll(" +", " ").replaceAll("'", raw"""\\u0027""")
    s"""curl https://api.openai.com/v1/engines/formal-small-isabelle-v6-c4/completions
       |-H 'Content-Type: application/json'
       |-H 'Authorization: Bearer <OPENAI_TOKEN>'
       |-d '{"prompt": "[IS] GOAL """.stripMargin + state_string +
      s""" PROOFSTEP", "best_of": 1, "temperature": 0.0, "max_tokens": 128, "n": 1}'""".stripMargin
  }

//  def state_only_get_oracle_string(state_only_toplevel : ToplevelState) : String = {
//    val state_string : String = "State: " +
//      pisaos.getStateString(state_only_toplevel).replace("\n", " ").replaceAll(" +", " ")
//    println("State only oracle query string:")
//    println(state_string)
//    if (state_only_src2tgt.contains(state_string)) {
//      state_only_src2tgt(state_string)
//    } else {
//      "No oracle solution"
//    }
//  }

  def proof_and_state_get_request_string(proof_and_state_toplevel : ToplevelState) : String = {
    val state_string = pisaos.getStateString(proof_and_state_toplevel).replace("\n", " ").replaceAll(" +", " ")
    val query_string = ("[IS] PROOF " + agent_proof_till_now + " <PS_SEP> [IS] GOAL " + state_string + " PROOFSTEP").replace(
      "\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replaceAll(" +", " ").replaceAll("'", raw"""\\u0027""")
    s"""curl https://api.openai.com/v1/engines/formal-small-isabelle-wproof-v1-c4/completions
       |-H 'Content-Type: application/json'
       |-H 'Authorization: Bearer <OPENAI_TOKEN>'
       |-d '{"prompt": """".stripMargin + query_string + """", "best_of": 1, "temperature": 0.0, "max_tokens": 128, "n": 1}'"""
  }

  def proof_and_state_get_oracle_string(proof_and_state_oracle_toplevel : ToplevelState) : String = {
    val state_string = pisaos.getStateString(proof_and_state_oracle_toplevel).replace("\n", " ").replaceAll(" +", " ")
    val query_string = "Proof: " + oracle_proof_till_now + " <PS_SEP> State: " + state_string
    println("Proof and state oracle query string:")
    println(query_string)
    if (proof_and_state_src2tgt.contains(query_string)) {
      proof_and_state_src2tgt(query_string)
    } else {
      "No oracle solution"
    }
  }

  def get_proof_command_from_request_string(request_string: String) : String = {
    val returned_string = request_string.!!
    if (returned_string.contains("error")) "error"
    else (parse(returned_string) \ "choices")(0).extract[GetText].text.trim
  }

  def prove_the_theorem_verbose (theorem_number : Int): Boolean = {
    implicit val isabelle: Isabelle = pisaos.isabelle
    try
      pisaos.step_to_transition_text(theorem_name)
    catch {
      case _: IsabelleException =>
        println("There's something wrong with the theorem name")
    }

//    var state_only_oracle_toplevel : ToplevelState = ToplevelState.instantiate(pisaos.toplevel.mlValue)
    var state_only_toplevel : ToplevelState = ToplevelState.instantiate(pisaos.toplevel.mlValue)
    var proof_and_state_oracle_toplevel : ToplevelState = ToplevelState.instantiate(pisaos.toplevel.mlValue)
    var proof_and_state_toplevel : ToplevelState = ToplevelState.instantiate(pisaos.toplevel.mlValue)

    var ground_truth_length : Int = 0
    var state_only_matched : Int = 0
    var proof_and_state_matched : Int = 0
    var oracle_proved : Boolean = false
    var state_only_proved : Boolean = false
    var proof_and_state_proved : Boolean = false

    for (step <- List.range(0, 10)) {
      println(s"Step ${step+1}")
      println("-"*100)
      // STATE ONLY ORACLE AGENT //
//      val state_only_oracle_response = state_only_get_oracle_string(state_only_oracle_toplevel)
//      println("State only oracle agent proof command:")
//      println(state_only_oracle_response)
//      try {
//        state_only_oracle_toplevel = pisaos.step(state_only_oracle_response, state_only_oracle_toplevel, 10000)
//      } catch {
//        case e: IsabelleException => println(e)
//      }
//      println("New state string:")
//      println(pisaos.getStateString(state_only_oracle_toplevel).replace("\n", " ").replaceAll(" +", " "))
//      println("*" * 100)

      // PROOF AND STATE ORACLE AGENT //
      var proof_and_state_oracle_response = ""
      if (oracle_proved) {}
      else {
        proof_and_state_oracle_response = proof_and_state_get_oracle_string(proof_and_state_oracle_toplevel)
        if (!(proof_and_state_oracle_response == "No oracle solution")) {
          ground_truth_length += 1
        }
        println("Proof and state oracle agent proof command:")
        println(proof_and_state_oracle_response)
        try {
          proof_and_state_oracle_toplevel = pisaos.step(proof_and_state_oracle_response,
            proof_and_state_oracle_toplevel, 10000)
          oracle_proof_till_now += " \\n " + proof_and_state_oracle_response.trim
        } catch {
          case e: IsabelleException => println(e)
        }
        println("New state string:")
        val oracle_state : String = pisaos.getStateString(proof_and_state_oracle_toplevel).replace("\n", " ").replaceAll(" +", " ")
        if (oracle_state.isEmpty) oracle_proved = true
        println("*" * 100)
      }

      // STATE ONLY AGENT //
      if (state_only_proved) {}
      else {
        val state_only_request_string = state_only_get_request_string(state_only_toplevel)
        println("State only request string:")
        println(state_only_request_string)
        val state_only_proof_command = get_proof_command_from_request_string(state_only_request_string)
        try {
          if (state_only_proof_command.contains("error")) {}
          else {
            if (state_only_proof_command == proof_and_state_oracle_response)
              state_only_matched += 1

            println("State only agent proof command:")
            println(state_only_proof_command)
            state_only_toplevel = pisaos.step(state_only_proof_command, state_only_toplevel, 10000)
          }
        } catch {
          case e: IsabelleException => println(e)
        }
        println("New state string:")
        val state_only_state : String = pisaos.getStateString(state_only_toplevel).replace("\n", " ").replaceAll(" +", " ")
        if (state_only_state.isEmpty) state_only_proved = true
        println("*" * 100)
      }

      // PROOF AND STATE AGENT //
      if (proof_and_state_proved) {}
      else {
        val proof_and_state_request_string = proof_and_state_get_request_string(proof_and_state_toplevel)
        println("Proof and state request string:")
        println(proof_and_state_request_string)
        val proof_and_state_proof_command = get_proof_command_from_request_string(proof_and_state_request_string)
        try {
          if (proof_and_state_proof_command.contains("error")) {}
          else {
            if (proof_and_state_proof_command == proof_and_state_oracle_response)
              proof_and_state_matched += 1

            println("Proof and state agent proof command:")
            println(proof_and_state_proof_command)
            proof_and_state_toplevel = pisaos.step(proof_and_state_proof_command, proof_and_state_toplevel, 10000)
            agent_proof_till_now += " \n " + proof_and_state_proof_command.trim
          }
        } catch {
          case e: IsabelleException => println(e)
        }
        println("New state string:")
        val proof_and_state : String = pisaos.getStateString(proof_and_state_toplevel).replace("\n", " ").replaceAll(" +", " ")
        if (proof_and_state.isEmpty) proof_and_state_proved = true
      }
    }

    new PrintWriter(s"debug/debug_problem_${theorem_number}") {
      write(s"Ground truth proof length : ${ground_truth_length} \n")
      write(s"State only proved: ${state_only_proved} \n")
      write(s"State only matched: ${state_only_matched} \n")
      write(s"Proof and state proved: ${proof_and_state_proved} \n")
      write(s"Proof and state matched: ${proof_and_state_matched} \n")
      close
    }

    true
    // TODO: maintain three parallel proving processes: state only, proof + state, and oracle.
    //  Make the proving process as verbose as possible for debugging.
  }

  def main(args: Array[String]): Unit = {
    if (args.length == 0) {
      println("Please give the path to the calibration file")
      sys.exit(1)
    }
    val theorem_number : Int = args(0).split('/').last.split('.').head.split('_').last.toInt
    val json_source = Source.fromFile(args(0))
    val json = parse(json_source.mkString).children.head
    json_source.close

    val file_path = json(0).extract[String]
    val thys_index = file_path.split("/").indexOf("thys")
    theorem_name = json(1).extract[String]

    register(
      path_to_isa_bin = "/home/ywu/Isabelle2020",
      path_to_file = file_path,
      working_directory = file_path.split("/").take(thys_index+2).mkString("/"),
      tn = theorem_name
    )
    get_ground_truth(theorem_number = theorem_number)
    prove_the_theorem_verbose(theorem_number = theorem_number)
  }
}
