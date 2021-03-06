package pisa.agent

import de.unruh.isabelle.control.{Isabelle, IsabelleException}
import de.unruh.isabelle.pure.ToplevelState
import net.liftweb.json._
import pisa.server.PisaOS
import pisa.agent.ProofText
import pisa.agent.logProbs

import java.io.PrintWriter
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.collection.immutable.ListMap
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.io.Source
import scala.sys.process._
import scala.util.control.Breaks._
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._

class ConjNormalOrderSearch(use_proof: Boolean = false, use_conjecture: Boolean = false, use_state_first: Boolean = false,
                 debug_mode: Boolean = true, search_width : Int = 32, maximum_queue_length : Int = 32,
                 temperature : Double = 0.8, max_tokens : Int = 128, max_trials : Int = 200, timeout : Int = 240000,
                 engine_address : String = "") {
  implicit val formats : DefaultFormats = DefaultFormats
  implicit val ec: ExecutionContext = ExecutionContext.global
  val firstOrd : Ordering[(Double, ListBuffer[(ToplevelState, Int, String, Int)])] =
    Ordering.by { t: (Double, ListBuffer[(ToplevelState, Int, String, Int)]) => t._1 }

  var pisaos : PisaOS = null
  var theorem_name : String = null
  var index_to_successful_skeletons : mutable.Map[Int, String] = mutable.Map[Int, String]()
  val used_engine_id : String =
    if (engine_address.nonEmpty) engine_address
    else {
      if (use_conjecture) "https://api.openai.com/v1/engines/formal-isabelle-isabelle-cnj-v1-c4/completions"
      else if (use_proof) "https://api.openai.com/v1/engines/formal-small-isabelle-wproof-v1-c4/completions"
      else "https://api.openai.com/v1/engines/formal-small-isabelle-v6-c4/completions"
    }

  def register(path_to_isa_bin: String, path_to_file: String, working_directory: String, tn: String) : Unit = {
    theorem_name = tn.replaceAll("\n", " ").replaceAll(" +", " ").trim
    pisaos = new PisaOS(
      path_to_isa_bin = path_to_isa_bin,
      path_to_file = path_to_file,
      working_directory = working_directory
    )
  }

  def get_request_string(proof_string: String, state_string: String, initial_step : Boolean = false) : String = {
    if (use_conjecture) {
      s"""curl ${used_engine_id}
         |-H 'Content-Type: application/json'
         |-H 'Authorization: Bearer <OPENAI_TOKEN>'
         |-d '{"prompt": "[IS] GOAL""".stripMargin + " Proof: " + proof_string +
        " State: " + state_string + " " +
        s"""PROOFSTEP", "best_of": $search_width, "temperature": $temperature,
           |"max_tokens": $max_tokens, "logprobs": 1, "n": $search_width}'""".stripMargin
    } else {
      "Rubbish"
    }
  }

  def process_string(input_string: String) : String =
  // Remove the change lines and extra spaces
    input_string.replaceAll("\n", " ").replaceAll(" +", " ").trim

  def process_proof_string(input_string: String) : String =
    input_string.replaceAll("\n", "\\n").replaceAll(" +", " ").trim

  def process_json_value_to_texts(json_value: JValue) : List[String] = {
    val text_buffer : ListBuffer[String] = new ListBuffer[String]()
    for (i <- List.range(0, search_width)) {
      text_buffer += (json_value \ "choices")(i).extract[ProofText].text
    }
    text_buffer.toList
  }

  def process_json_value_to_logprobs(json_value: JValue) : List[Double] = {
    val logprobs_buffer : ListBuffer[Double] = new ListBuffer[Double]()
    for (i <- List.range(0, search_width)) {
      // Extracting information about the log probability of each token and what the tokens are
      val logprob_info = ((json_value \ "choices")(i) \ "logprobs").extract[logProbs]
      val list_of_tokens = logprob_info.tokens
      // If there is an EOS token, stop counting there; Otherwise keep counting till the end
      val endoftext_in_the_tokens = list_of_tokens.indexOf("<|endoftext|>")
      var tokens_in_the_text = 50000000
      if (endoftext_in_the_tokens == -1) {
        tokens_in_the_text = list_of_tokens.length
      } else {
        tokens_in_the_text = endoftext_in_the_tokens + 1
      }
      // Calculate the accumulative logprobs of the returned text
      val accumulative_logprobs : Double = logprob_info.token_logprobs.take(tokens_in_the_text).sum
      logprobs_buffer += accumulative_logprobs
    }
    logprobs_buffer.toList
  }

  def coordinate_and_make_texts_and_logprobs_distinct(texts: List[String], logprobs: List[Double])
  : (List[(String, Double)]) = {
    var text_logprob_map : Map[String, Double] = Map[String, Double]()
    for (i <- List.range(0, texts.length)) {
      val text = texts(i)
      val logprob = logprobs(i)
      // If this entry doesn't exist yet, or the existing entry has a small prob than our entry, update the map
      if ((!text_logprob_map.contains(text)) || text_logprob_map(text) < logprob)
        text_logprob_map = text_logprob_map + (text -> logprob)
    }
    text_logprob_map.toList.sortWith(_._2 > _._2)
  }

  // Return four things: success or fail, failure cause, proof until now, proof length
  def prove_the_theorem_and_exit(timeout_in_millis: Int = timeout) :
  Tuple5[Int, String, String, Int, Map[Int, String]] = {
    var result : Tuple5[Int, String, String, Int, Map[Int, String]] = null
    val f_st : Future[Unit] = Future.apply{
      result = prove_the_theorem
      pisaos.step("exit")
      pisaos = null
    }
    Await.result(f_st, Duration(timeout_in_millis, "millis"))
    result
  }

  def prove_the_theorem : Tuple5[Int, String, String, Int, Map[Int, String]] = {
    var search_thread_index : Int = 0

    // Initialise a priority queue of (logprob, toplevel_state, state_string) ordered by the logprob
    implicit val isabelle: Isabelle = pisaos.isabelle
    try
      pisaos.step_to_transition_text(theorem_name)
    catch {
      case e: IsabelleException => return Tuple5(0, "Overall timeout", "", -1, index_to_successful_skeletons.toMap)
    }
    var longest_proof_length : Int = 0
    // The priority queue elements contain five things:
    // the accumulative log prob,
    // the list of (top level state, proof level, proof until this step, proof length up till now)
    var successful_proof_length : Int = -1
    var successful_proof_script : String = ""
    var accumulative_logprob_toplevel_pq =
      new mutable.PriorityQueue[(Double, ListBuffer[(ToplevelState, Int, String, Int)])]()(firstOrd)
    val initial_toplevel_state_listbuffer = new ListBuffer[(ToplevelState, Int, String, Int)]
    initial_toplevel_state_listbuffer += Tuple4(pisaos.toplevel, pisaos.proof_level(pisaos.toplevel).retrieveNow, theorem_name, 0)
    accumulative_logprob_toplevel_pq += Tuple2(0, initial_toplevel_state_listbuffer)

    var trials = 0
    var proved : Boolean = false

    while (accumulative_logprob_toplevel_pq.nonEmpty && trials <= max_trials) {
      trials += 1
      val acc_logprob_toplevel_tuple = accumulative_logprob_toplevel_pq.dequeue
      // Get the logprob and the state string for the current toplevel
      val parent_logprob = acc_logprob_toplevel_tuple._1
      val parent_toplevel_state_proof_level_list = acc_logprob_toplevel_tuple._2

      val parent_toplevel_state = parent_toplevel_state_proof_level_list.head._1
      val parent_toplevel_proof_level = parent_toplevel_state_proof_level_list.head._2
      val proof_till_now = parent_toplevel_state_proof_level_list.head._3
      val proof_length_till_now = parent_toplevel_state_proof_level_list.head._4

      val initial_step = {
        if (proof_length_till_now == 0) true
        else false
      }

      val proof_string : String = proof_till_now.split("<conj_sep>").last.trim
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replaceAll("'", raw"""\\u0027""")
        .replace("\n", "\\\\n")
      val state_string : String = {
        if (use_conjecture) {
          pisaos.getStateString(parent_toplevel_state).replace(
            "\n", " \\n ").replaceAll(" +", " ").trim.replace(
            "\\", "\\\\").replace("\"", "\\\"").replaceAll(
            "'", raw"""\\u0027""")
        } else {
          process_string(pisaos.getStateString(parent_toplevel_state))
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\\\n")
            .replaceAll("'", raw"""\\u0027""")
        }
      }

      var request_string = get_request_string(proof_string, state_string, initial_step = initial_step)
      //      println(request_string)
      var returned_text = request_string.!!

      breakable {
        if (returned_text.contains("error")) {
          request_string = get_request_string(proof_string.takeRight(6000), state_string)
          returned_text = request_string.!!
          if (returned_text.contains("error")) {
            break
          } else {}
        }
        //        println(returned_text)
        var parsed_value : JValue = null
        try {
          parsed_value = parse(returned_text)
        } catch {
          //          case e: Throwable => throw new RuntimeException(e.toString + '\n' + request_string + '\n' + returned_text)
          case _: Throwable => break
        }
        // Extract the candidate commands and their respective accumulative logprobs
        //        val candidate_commands : List[String] = process_json_value_to_texts(parsed_value).distinct
        //        val candidate_logprobs : List[Double] = process_json_value_to_logprobs(parsed_value).distinct
        val candidate_commands : List[String] = process_json_value_to_texts(parsed_value)
        val candidate_logprobs : List[Double] = process_json_value_to_logprobs(parsed_value)
        val candidate_commands_and_logprobs =
          coordinate_and_make_texts_and_logprobs_distinct(candidate_commands, candidate_logprobs)
        //      println("Length of the candidate commands: " + candidate_commands.length.toString)
        //        assert(candidate_commands.distinct.length == candidate_logprobs.distinct.length)
        // Create copies of the toplevel state for the search expansion
        val child_toplevel_state_list_buffer : ListBuffer[ToplevelState] = new ListBuffer[ToplevelState]
        for (_ <- List.range(0, candidate_commands_and_logprobs.length)) {
          child_toplevel_state_list_buffer += ToplevelState.instantiate(parent_toplevel_state.mlValue)
        }
        val child_toplevel_state_list = child_toplevel_state_list_buffer.toList

        // For each command candidate, apply the command to a copy of the top level state and retrieve the resulting top level state
        //      println(candidate_commands.length)
        for (i <- List.range(0, candidate_commands_and_logprobs.length)) {
          //        println("Trials: " + trials.toString)
          //        println("i: " + i.toString)
          val proof_command = process_string(candidate_commands_and_logprobs(i)._1)
          //        println(proof_command)
          // We don't want the agent to cheat
          if (proof_command.contains("sorry") || proof_command.contains("oops")) {}
          else {
            //            println(proof_command)
            //            println(candidate_commands_and_logprobs(i)._2)
            try {
              val child_toplevel : ToplevelState = pisaos.step(
                proof_command,
                child_toplevel_state_list(i),
                10000
              )
              val child_logprob : Double = parent_logprob + candidate_commands_and_logprobs(i)._2
              val child_state_string : String = pisaos.getStateString(child_toplevel)
              val child_proof_level : Int = pisaos.proof_level(child_toplevel).retrieveNow

              // If everything works to this point, copy the
              // parent (toplevel, proof_level, proof until this step, proof length until now) list to the child one
              // Change the first element to the child one
              val child_toplevel_state_proof_level_listbuffer = new ListBuffer[(ToplevelState, Int, String, Int)]
              for (i <- List.range(1, parent_toplevel_state_proof_level_list.length)) {
                val toplevel_state_and_proof_level_tuple = parent_toplevel_state_proof_level_list(i)
                child_toplevel_state_proof_level_listbuffer.append(Tuple4(
                  ToplevelState.instantiate(toplevel_state_and_proof_level_tuple._1.mlValue),
                  toplevel_state_and_proof_level_tuple._2,
                  toplevel_state_and_proof_level_tuple._3,
                  toplevel_state_and_proof_level_tuple._4
                ))
              }

              if (use_conjecture && proof_command.startsWith("have")) {
                child_toplevel_state_proof_level_listbuffer.prepend(
                  Tuple4(
                    ToplevelState.instantiate(child_toplevel.mlValue),
                    child_proof_level,
                    proof_till_now + " \n " + proof_command.trim,
                    proof_length_till_now + 1
                  )
                )
                child_toplevel_state_proof_level_listbuffer.append(
                  Tuple4(
                    pisaos.step("sorry", child_toplevel),
                    parent_toplevel_proof_level,
                    proof_till_now + " \n " + proof_command.trim + " sorry",
                    proof_length_till_now + 1
                  )
                )
              }
              // If the proof of this level has been completed, do not add an element to the list
              // Otherwise, do
              else if (child_proof_level < parent_toplevel_proof_level) {
                if (debug_mode) {
                  search_thread_index += 1
                  index_to_successful_skeletons(search_thread_index) = s"Proof level: ${child_proof_level}\n" + proof_till_now + " \n " + proof_command.trim
                  println(index_to_successful_skeletons)
                }

                if (child_toplevel_state_proof_level_listbuffer.isEmpty) {
                  successful_proof_length = proof_length_till_now + 1
                  successful_proof_script = proof_till_now + " \n " + proof_command.trim
                } else {
                  val first_element = child_toplevel_state_proof_level_listbuffer.head
                  child_toplevel_state_proof_level_listbuffer.remove(0)
                  child_toplevel_state_proof_level_listbuffer.prepend(
                    (first_element._1, first_element._2, proof_till_now + " \n " + proof_command.trim + " <conj_sep> " + first_element._3, proof_length_till_now+1+first_element._4)
                  )
                }
              } else {
                child_toplevel_state_proof_level_listbuffer.prepend(
                  Tuple4(child_toplevel, parent_toplevel_proof_level,
                    proof_till_now + " \n " + proof_command.trim, proof_length_till_now + 1)
                )
              }

              if (child_toplevel_state_proof_level_listbuffer.isEmpty) {
                //                println("Is empty")
                proved = true
              } else {
                accumulative_logprob_toplevel_pq += Tuple2(
                  child_logprob,
                  child_toplevel_state_proof_level_listbuffer
                )
              }
              // Update longest proof length
              if (proof_length_till_now + 1 > longest_proof_length) longest_proof_length = proof_length_till_now+1
            } catch {
              case e: IsabelleException => {}
              //                println("Throws an isabelle error")
              case _: TimeoutException => {}
              //                println("Timeout happens in step")
              case t: Throwable => throw new RuntimeException(t.toString)
              //            case _: Throwable => println("We don't know this error")
            }
          }
        }

        if (proved) return Tuple5(1, "Proved!", successful_proof_script, successful_proof_length, index_to_successful_skeletons.toMap)
        if (accumulative_logprob_toplevel_pq.length > maximum_queue_length) {
          accumulative_logprob_toplevel_pq = accumulative_logprob_toplevel_pq.dropRight(
            accumulative_logprob_toplevel_pq.length - maximum_queue_length)
        }
      }

    }
    if (accumulative_logprob_toplevel_pq.isEmpty) Tuple5(0, "Queue empty", "", longest_proof_length, index_to_successful_skeletons.toMap)
    else Tuple5(0, "Out of fuel", "", longest_proof_length, index_to_successful_skeletons.toMap)
  }
}


object PisaConjecturerNormalOrder {
  implicit val formats : DefaultFormats = DefaultFormats

  def update_path(original:String): String = {
//     val replacements = Map("/home/ywu/afp-2021-02-11/".r -> "/home/wenda/Libraries/afp2020/")
//    val replacements = Map("/home/ywu/afp-2021-02-11/".r -> "/root/afp-2021-02-11/")
      val replacements = Map("/home/ywu/afp-2021-02-11/".r -> "/home/ywu/afp-2021-02-11/")
    replacements.foldLeft(original) { (s, r) => r._1.replaceAllIn(s, r._2) }
  }

  def main(args: Array[String]): Unit = {
    if (args.length == 0) {
      println("Please give the path to the calibration file")
      sys.exit(1)
    }

    val test_theorem_number : String = args(0).split('/').last.split('.').head.split('_').last
    val use_proof : Boolean = args(1).toBoolean
    val use_conj : Boolean = args(2).toBoolean
    val use_state_first : Boolean = args(3).toBoolean
    val dump_path : String = args(4)

    val search_width : Int = args(5).toInt
    val maximum_queue_length : Int = args(6).toInt
    val temperature : Double = args(7).toDouble
    val max_tokens : Int = args(8).toInt
    val max_trials : Int = args(9).toInt
    val timeout : Int = args(10).toInt
    val engine_id : String = if (args.length == 12) args(11) else ""

    // val json = parse(Source.fromFile("20_calibration_names.json").mkString).children
    val json = parse(Source.fromFile(args(0)).mkString).children
    var proved_theorems : Int = 0
    val failure_causes : ListBuffer[String] = new ListBuffer[String]
    val proof_scripts : ListBuffer[String] = new ListBuffer[String]
    val proof_lengths : ListBuffer[Int] = new ListBuffer[Int]
    var total_theorems : Int = 0

    val debug_mode : Boolean = true
    val search_agent = new ConjNormalOrderSearch(use_proof=use_proof, use_conjecture=use_conj,
      use_state_first = use_state_first, debug_mode = debug_mode,
      search_width = search_width, maximum_queue_length = maximum_queue_length, temperature = temperature,
      max_tokens = max_tokens, max_trials = max_trials, timeout = timeout, engine_address = engine_id
    )
    var result : (Int, String, String, Int, Map[Int, String]) = null
    for (element <- json) {
      total_theorems += 1
      println("Thoerem number: " + total_theorems.toString)
      val temporary_wd = update_path(element(0).extract[String])
      val thys_index = temporary_wd.split("/").indexOf("thys")
      //      search_agent.register(path_to_isa_bin = "/Applications/Isabelle2020.app/Isabelle",
//      search_agent.register(path_to_isa_bin = "/root/Isabelle2020/",
              search_agent.register(path_to_isa_bin = "/home/ywu/Isabelle2020/",
        // search_agent.register(path_to_isa_bin = "/opt/Isabelle2020/",
        // path_to_file = element(0).extract[String],
        path_to_file = temporary_wd,
        working_directory = temporary_wd.split("/").take(thys_index+2).mkString("/"),
        tn = element(1).extract[String]
      )
      try
        result = search_agent.prove_the_theorem_and_exit()
      catch {
        case e: TimeoutException => result = Tuple5(0, "Overall timeout", "", -1, search_agent.index_to_successful_skeletons.toMap)
        case rte: RuntimeException =>
          println("This is strange")
          new PrintWriter(s"$dump_path/runtime_error_$test_theorem_number") {
            write(rte.toString)
            close()
          }
      }
      //      println(result)
      proved_theorems += result._1
      failure_causes += result._2
      proof_scripts += result._3
      proof_lengths += result._4
    }
    println(failure_causes.head)
    //    println(proved_theorems.toFloat/total_theorems)

    new PrintWriter(s"$dump_path/test_result_$test_theorem_number") {
      write(proved_theorems.toString); close()
    }
    new PrintWriter(s"$dump_path/test_cause_$test_theorem_number") {
      write(failure_causes.mkString("\n")); close()
    }
    if (proved_theorems == 1) {
      new PrintWriter(s"$dump_path/successful_proof_script_$test_theorem_number") {
        write(proof_scripts.head)
        close()
      }
    }
    new PrintWriter(s"$dump_path/proof_length_$test_theorem_number") {
      write(proof_lengths.head.toString); close()
    }

    if (debug_mode) {
      val successful_skeletons_seq : ListMap[Int, String] = ListMap(result._5.toSeq.sortWith(_._1 < _._1):_*)
      new PrintWriter(s"$dump_path/successful_skeletons_$test_theorem_number") {
        for (key <- successful_skeletons_seq.keys) {
          write(
            s"""Proof thread: ${key}.
               |${successful_skeletons_seq(key)}
               |""".stripMargin)
        }
        close()
      }
    }
  }
}
