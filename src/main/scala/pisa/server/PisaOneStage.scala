/*
This is adapted from contents in Dominique Unruh's package scala-isabelle
The package can be found on github at https://github.com/dominique-unruh/scala-isabelle
This particular file is adapted from https://github.com/dominique-unruh/scala-isabelle/blob/master/src/test/scala/de/unruh/isabelle/experiments/ExecuteIsar.scala
 */

package pisa.server

import io.grpc.Status
import zio.{ZEnv, ZIO}
import pisa.server.ZioServer.ZServer

import util.control.Breaks
import scala.collection.mutable.ListBuffer
import _root_.java.nio.file.{Files, Path}
import _root_.java.io.File
import de.unruh.isabelle.control.Isabelle
import de.unruh.isabelle.mlvalue.{AdHocConverter, MLFunction, MLFunction0, MLFunction2, MLFunction3, MLValue, MLValueWrapper}
import de.unruh.isabelle.mlvalue.MLValue.{compileFunction, compileFunction0, compileValue}
import de.unruh.isabelle.pure.{Context, Position, Theory, TheoryHeader, ToplevelState}
import pisa.utils.TheoryManager
import pisa.utils.TheoryManager.{Ops, Source, Text}

import scala.concurrent.{ExecutionContext,Await,Future}
import scala.concurrent.duration.Duration

// Implicits
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._

object Transition extends AdHocConverter("Toplevel.transition")
object ProofState extends AdHocConverter("Proof.state")
object RuntimeError extends AdHocConverter("Runtime.error")
object Pretty extends AdHocConverter("Pretty.T")
object ProofContext extends AdHocConverter("Proof_Context.T")

class PisaOS(var path_to_isa_bin: String, var path_to_file : String, var working_directory: String, var use_Sledgehammer :Boolean = false) {
  val currentTheoryName : String = path_to_file.split("/").last.replace(".thy", "")
  val currentProjectName : String = {
    if (path_to_file.contains("afp")) {
      working_directory.slice(working_directory.indexOf("thys/")+5, working_directory.length).split("/").head
    } else if (path_to_file.contains("Isabelle") && path_to_file.contains("/src/")){
      // The theory file could be /Applications/Isabelle2021.app/Isabelle/src/HOL/Analysis/ex
      // The correct project name for it is HOL-Analysis-ex
      val relative_working_directory =
        working_directory.slice(working_directory.indexOf("/src/")+5, working_directory.length).split(
          "/")
      relative_working_directory.mkString("-")
    } else {"This is not supported at the moment"}
  }
//  println("Name of the current project:")
//  println(currentProjectName)
  val sessionRoots : Seq[Path] = {
    if (path_to_file.contains("afp")) {
      Seq(Path.of(working_directory.slice(-1, working_directory.indexOf("thys/")+4)))
    } else if (path_to_file.contains("Isabelle") && path_to_file.contains("/src/")) {
      val src_index : Int = working_directory.indexOf("/src/")+5
      val session_root_path_string : String = working_directory.slice(0, src_index) +
        working_directory.slice(src_index, working_directory.length).split("/").head
      Seq(Path.of(session_root_path_string))
    } else {Seq(Path.of("This is not supported at the moment"))}
  }
  val setup: Isabelle.Setup = Isabelle.Setup(
    isabelleHome = Path.of(path_to_isa_bin),
    sessionRoots = sessionRoots,
    userDir = None,
    logic = currentProjectName,
    workingDirectory = Path.of(working_directory),
    build=false
  )
  implicit val isabelle: Isabelle = new Isabelle(setup)
  implicit val ec: ExecutionContext = ExecutionContext.global

  val script_thy: MLFunction2[String, Theory, Theory] = compileFunction[String, Theory, Theory]("fn (str,thy) => Thy_Info.script_thy Position.none str thy")
  val init_toplevel: MLFunction0[ToplevelState] = compileFunction0[ToplevelState]("Toplevel.init_toplevel")
  val is_proof: MLFunction[ToplevelState, Boolean] = compileFunction[ToplevelState, Boolean]("Toplevel.is_proof")
  val is_skipped_proof: MLFunction[ToplevelState, Boolean] = compileFunction[ToplevelState, Boolean]("Toplevel.is_skipped_proof")
  val proof_level: MLFunction[ToplevelState, Int] = compileFunction[ToplevelState, Int]("Toplevel.level")
  val proof_of: MLFunction[ToplevelState, ProofState.T] = compileFunction[ToplevelState, ProofState.T]("Toplevel.proof_of")
  val command_exception: MLFunction3[Boolean, Transition.T, ToplevelState, ToplevelState] = compileFunction[Boolean, Transition.T, ToplevelState, ToplevelState](
    "fn (int, tr, st) => Toplevel.command_exception int tr st")
  val command_errors: MLFunction3[Boolean, Transition.T, ToplevelState, (List[RuntimeError.T], Option[ToplevelState])] = compileFunction[Boolean, Transition.T, ToplevelState, (List[RuntimeError.T], Option[ToplevelState])](
    "fn (int, tr, st) => Toplevel.command_errors int tr st")
  val toplevel_end_theory: MLFunction[ToplevelState, Theory] = compileFunction[ToplevelState, Theory]("Toplevel.end_theory Position.none")
  val theory_of_state: MLFunction[ToplevelState, Theory] = compileFunction[ToplevelState, Theory]("Toplevel.theory_of")
  val context_of_state: MLFunction[ToplevelState, Context] = compileFunction[ToplevelState, Context]("Toplevel.context_of")
  val name_of_transition: MLFunction[Transition.T, String] = compileFunction[Transition.T, String]("Toplevel.name_of")
  val parse_text: MLFunction2[Theory, String, List[(Transition.T, String)]] = compileFunction[Theory, String, List[(Transition.T, String)]](
    """fn (thy, text) => let
      |  val transitions = Outer_Syntax.parse_text thy (K thy) Position.start text
      |  fun addtext symbols [tr] =
      |        [(tr, implode symbols)]
      |    | addtext _ [] = []
      |    | addtext symbols (tr::nextTr::trs) = let
      |        val (this,rest) = Library.chop (Position.distance_of (Toplevel.pos_of tr, Toplevel.pos_of nextTr) |> Option.valOf) symbols
      |        in (tr, implode this) :: addtext rest (nextTr::trs) end
      |  in addtext (Symbol.explode text) transitions end""".stripMargin)
  val theoryName: MLFunction2[Boolean, Theory, String] = compileFunction[Boolean, Theory, String](
    "fn (long, thy) => Context.theory_name' {long=long} thy")
  val toplevel_string_of_state: MLFunction[ToplevelState, String] = compileFunction[ToplevelState, String](
    "Toplevel.string_of_state")
  val pretty_local_facts: MLFunction2[ToplevelState, Boolean, List[Pretty.T]] = compileFunction[ToplevelState, Boolean, List[Pretty.T]](
    "fn (tls, b) => Proof_Context.pretty_local_facts b (Toplevel.context_of tls)"
  )
  val make_pretty_list_string_list : MLFunction[List[Pretty.T], List[String]] = compileFunction[List[Pretty.T], List[String]](
    "fn (pretty_list) => map Pretty.unformatted_string_of pretty_list"
  )
  val header_read : MLFunction2[String, Position, TheoryHeader] =
    compileFunction[String, Position, TheoryHeader]("fn (text,pos) => Thy_Header.read pos text")

  // setting up Sledgehammer
  val thy_for_sledgehammer : Theory = Theory("HOL.List")
  val Sledgehammer_Commands : String = thy_for_sledgehammer.importMLStructureNow("Sledgehammer_Commands")
  val Sledgehammer : String = thy_for_sledgehammer.importMLStructureNow("Sledgehammer")
  val Sledgehammer_Prover : String = thy_for_sledgehammer.importMLStructureNow("Sledgehammer_Prover")
  val check_with_Sledgehammer: MLFunction[ToplevelState, Boolean] = compileFunction[ToplevelState, Boolean] (
    s""" fn state =>
      |    (
      |    let
      |      val ctxt = Toplevel.context_of state;
      |      val thy = Proof_Context.theory_of ctxt
      |      val p_state = Toplevel.proof_of state;
      |      val params = ${Sledgehammer_Commands}.default_params thy
      |                      [("isar_proofs", "false"),("smt_proofs", "false"),("learn","false")]
      |      val override = {add=[],del=[],only=false}
      |      val run_sledgehammer = ${Sledgehammer}.run_sledgehammer params ${Sledgehammer_Prover}.Auto_Try
      |                                  NONE 1 override
      |                                : Proof.state -> bool * (string * string list);
      |    in
      |      run_sledgehammer p_state |> fst
      |    end)
    """.stripMargin) 

  def beginTheory(source: Source)(implicit isabelle: Isabelle, ec: ExecutionContext): Theory = {
    val header = getHeader(source)
//    The master directory, currently is set to the source path instead of the parent of the source path
//    For the afp extraction experiments, the parent path was used
//    val masterDir = source.path.getParent
    val masterDir = source.path
    val registers : ListBuffer[String] = new ListBuffer[String]()
    for (theory_name <- header.imports) {
      if (importMap.contains(theory_name)) {
      registers += s"${currentProjectName}.${importMap(theory_name)}"
      } else registers += theory_name
    }
//    println("Ops begin theory setting:")
//    println(masterDir)
//    println(header)
//    println(registers)
    Ops.begin_theory(masterDir, header, registers.toList.map(Theory.apply)).retrieveNow
  }
  def getHeader(source: Source)(implicit isabelle: Isabelle, ec: ExecutionContext): TheoryHeader = source match {
    case Text(text, path, position) => Ops.header_read(text, position).retrieveNow
  }

  // Find out about the starter string
  private val fileContent : String = Files.readString(Path.of(path_to_file))
  private def getStarterString : String = {
    val decoyThy : Theory = Theory("Main")
    for ((transition, text) <- parse_text(decoyThy, fileContent).force.retrieveNow) {
      if (text.contains("theory") && text.contains(currentTheoryName) && text.contains("begin")) {
        return text
      }
    }
    "This is wrong!!!"
  }

  val starter_string : String = getStarterString.trim.replaceAll("\n", " ").trim

  // Find out what to import from the current directory
  def getListOfTheoryFiles(dir: File) : List[File] = {
    if (dir.exists && dir.isDirectory) {
      var listOfFilesBuffer : ListBuffer[File] = new ListBuffer[File]
      for (f <- dir.listFiles()) {
        if (f.isDirectory) {
          listOfFilesBuffer = listOfFilesBuffer ++ getListOfTheoryFiles(f)
        } else if (f.toString.endsWith(".thy")) {
          listOfFilesBuffer += f
        }
      }
      listOfFilesBuffer.toList
    } else {
      List[File]()
    }
  }

  def sanitiseInDirectoryName(fileName: String) : String = {
    fileName.replace("\"", "").split("/").last.split(".thy").head
  }

  val available_files : List[File] = getListOfTheoryFiles(new File(working_directory))
//  println("Available files:")
//  println(available_files)

  var available_imports_buffer : ListBuffer[String] = new ListBuffer[String]
  for (file_name <- available_files) {
    if (file_name.getName().endsWith(".thy")) {
      available_imports_buffer = available_imports_buffer += file_name.getName().split(".thy")(0)
    }
  }
  var available_imports : Set[String] = available_imports_buffer.toSet
//  println("Available libraries to import:")
//  println(available_imports)
  val theoryNames : List[String] = starter_string.split("imports")(1).split("begin")(0).split(" ").map(_.trim).filter(_.nonEmpty).toList
//  println("Names of the theories to import:")
//  println(theoryNames)
  var importMap:Map[String, String] = Map()
  for (theoryName <- theoryNames) {
    val sanitisedName = sanitiseInDirectoryName(theoryName)
    if (available_imports(sanitisedName)) {
      importMap += (theoryName.replace("\"", "") -> sanitisedName)
    }
  }

//  println("The import map:")
//  println(importMap)

  var top_level_state_map : Map[String, MLValue[ToplevelState]] = Map()

  val theoryStarter : TheoryManager.Text = TheoryManager.Text(starter_string, setup.workingDirectory.resolve(""))
//  println("Successfully set up the Isabelle executable")
//  println("Theory starter:")
//  println(theoryStarter)

  var thy1: Theory = beginTheory(theoryStarter)
  thy1.await
//  println("Theory initialisation")
  var toplevel: ToplevelState = init_toplevel().force.retrieveNow
//  println("Initialisation finished.")

  def reset_prob(): Unit = {
    thy1 = beginTheory(theoryStarter)
    toplevel = init_toplevel().force.retrieveNow
  }

  def getFacts(stateString : String) : String = {
    var facts : String = ""
    if (stateString.trim.nonEmpty) {
      // Limit the maximum number of local facts to be 5
      for (fact <- make_pretty_list_string_list(pretty_local_facts(toplevel, false)).retrieveNow.takeRight(5)) {
        facts = facts + fact + "<\\PISASEP>"
      }
    }
    facts
  }

  def getStateString(top_level_state: ToplevelState): String =
    toplevel_string_of_state(top_level_state).retrieveNow

  def getStateString: String = getStateString(toplevel)

  def singleTransition(single_transition: Transition.T, top_level_state: ToplevelState) : ToplevelState = {
    command_exception(true, single_transition, top_level_state).retrieveNow.force
  }

  def singleTransition(singTransition: Transition.T): String = {
    //    TODO: inlcude global facts
    toplevel = singleTransition(singTransition, toplevel)
    getStateString
  }

  def parseStateAction(isarString : String) : String = {
    // Here we directly apply transitions to the theory repeatedly
    // to get the (last_observation, action, observation, reward, done) tuple
    var stateActionTotal : String = ""
    val continue = new Breaks
    // Initialising the state string
    var stateString = getStateString
    Breaks.breakable {
      for ((transition, text) <- parse_text(thy1, isarString).force.retrieveNow)
        continue.breakable {
          if (text.trim.isEmpty) continue.break
          stateActionTotal = stateActionTotal + (stateString + "<\\STATESEP>" + text.trim + "<\\TRANSEP>")
          stateString = singleTransition(transition)
        }
    }
    stateActionTotal
  }

  def parse : String = {
    parseStateAction(fileContent)
  }

  def step(isar_string: String, top_level_state: ToplevelState, timeout_in_millis: Int =2000): ToplevelState = {
    // Normal isabelle business
    var tls_to_return : ToplevelState = null
    var stateString : String = ""
    val continue = new Breaks
    
    val f_st: Future[Unit] = Future.apply {
      Breaks.breakable {
      for ((transition, text) <- parse_text(thy1, isar_string).force.retrieveNow)
        continue.breakable {
          if (text.trim.isEmpty) continue.break
          tls_to_return = singleTransition(transition, top_level_state)
        }
      }
    }
    
    Await.result(f_st, Duration(timeout_in_millis, "millis"))
    tls_to_return
  }

  def step(isar_string: String): String = {
    // Specific method for extracting data
    if (isar_string == "PISA extract data")
      return parse

    // Exit string
    if (isar_string == "exit") {
      isabelle.destroy()
      //      print("Isabelle process destroyed")
      return "Destroyed"
    }
    toplevel = step(isar_string, toplevel)
    getStateString
  }

  // Returns true if the current toplevel state is a proof state & can be proved by Sledgehammer before timeout
  def check_if_provable_with_Sledgehammer(top_level_state: ToplevelState, timeout_in_millis: Int =240000): Boolean = {
    println(check_with_Sledgehammer.getClass.toString)
    println(top_level_state.getClass.toString)
    val f_res: Future[Boolean] = Future.apply {
      check_with_Sledgehammer(top_level_state).force.retrieveNow
    }
    Await.result(f_res, Duration(timeout_in_millis, "millis"))
  }

  def check_if_provable_with_Sledgehammer(): Boolean = {
    check_if_provable_with_Sledgehammer(toplevel)
  }

  def step_to_transition_text(isar_string: String): String = {
//    println("Start parsing")
    var stateString : String = ""
    val continue = new Breaks
    Breaks.breakable {
      for ((transition, text) <- parse_text(thy1, fileContent).force.retrieveNow) {
        continue.breakable {
          if (text.trim.isEmpty) continue.break
          stateString = singleTransition(transition)
          val trimmed_text = text.trim.replaceAll("\n", " ").replaceAll(" +", " ")
          if (trimmed_text == isar_string) {
            return stateString
          }
        }
      }
    }
    println("Did not find the text")
    stateString
  }

  def copy_tls: MLValue[ToplevelState] = toplevel.mlValue

  def clone_tls(tls_name: String): Unit = {
    top_level_state_map += (tls_name -> copy_tls)
  }

  def retrieve_tls(tls_name: String) : ToplevelState = ToplevelState.instantiate(top_level_state_map(tls_name))
}

class OneStageBody extends ZServer[ZEnv, Any] {
  var pisaos : PisaOS = null
  var isaPath : String = null
  var isaWorkingDirectory : String = null

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
    pisaos = new PisaOS(path_to_isa_bin=isaPath, path_to_file = path_to_file.context,
      working_directory = isaWorkingDirectory)
    ZIO.succeed(IsaMessage(s"You entered the path to the Theory file: ${path_to_file.context} \n" +
      s"We have successfully initialised the Isabelle environment."))
  }

  def isabelleCommand(isa_command: IsaCommand): ZIO[
    zio.ZEnv, Status, IsaState] = {
    var proof_state : String = null

    if (isa_command.command.startsWith("proceed:")){
      val true_command : String = isa_command.command.stripPrefix("proceed:").trim
      proof_state = pisaos.step_to_transition_text(true_command)
    } else if (isa_command.command == "exit") {
      proof_state = pisaos.step(isa_command.command)
      pisaos = null
    } else if (isa_command.command.trim.startsWith("<clone>")){
      val tls_name : String = isa_command.command.trim.stripPrefix("<clone>").trim
      pisaos.clone_tls(tls_name)
      proof_state = "Successfully copied top level state named: " + tls_name
    } else {
      proof_state = pisaos.step(isa_command.command)
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
  val path_to_isa_bin : String = "/home/qj213/Isabelle2021"
  val path_to_afp : String = "/home/qj213/afp-2021-10-22"
  def main(args: Array[String]): Unit = {
    val path_to_file : String = s"$path_to_afp/thys/Functional-Automata/NA.thy"
    val working_directory : String = s"$path_to_afp/thys/Functional-Automata"
    val pisaos = new PisaOS(
      path_to_isa_bin=path_to_isa_bin,
      path_to_file=path_to_file,
      working_directory=working_directory)
    val theorem_name = """lemma accepts_conv_steps: "accepts A w = (\<exists>q. (start A,q) \<in> steps A w \<and> fin A q)"""".stripMargin
    val parsed : String = pisaos.step("PISA extract data")
//    val parsed : String = pisaos.step_to_transition_text(theorem_name)
    println(parsed)
//    println(pisaos.step("by(simp add: delta_conv_steps accepts_def)"))
  }
}

object PisaOneStageTestStd {
  val path_to_isa_bin : String = "/home/qj213/Isabelle2021"
  val path_to_afp : String = "/home/qj213/afp-2021-10-22"
  def main(args: Array[String]): Unit = {
    val path_to_file : String = s"$path_to_afp/thys/Functional-Automata/NA.thy"
    val working_directory : String = s"$path_to_afp/thys/Functional-Automata"
    val pisaos = new PisaOS(
      path_to_isa_bin=path_to_isa_bin,
      path_to_file=path_to_file,
      working_directory=working_directory)

    implicit val ec: ExecutionContext = ExecutionContext.global
    implicit val isabelle = pisaos.isabelle
    pisaos.step("theory NA imports Main begin")
    println(pisaos.proof_level(pisaos.toplevel).retrieveNow)
    pisaos.step("theorem 1: \"1+2=3\"")
    println(pisaos.proof_level(pisaos.toplevel).retrieveNow)
    pisaos.step("proof -")
    println(pisaos.proof_level(pisaos.toplevel).retrieveNow)
    pisaos.step("show ?thesis")
    println(pisaos.proof_level(pisaos.toplevel).retrieveNow)
    pisaos.step("by auto")
    println(pisaos.proof_level(pisaos.toplevel).retrieveNow)
    pisaos.step("qed")
    println(pisaos.proof_level(pisaos.toplevel).retrieveNow)
  }
}


