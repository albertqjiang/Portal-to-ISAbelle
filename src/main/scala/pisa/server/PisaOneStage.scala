/*
This is adapted from contents in Dominique Unruh's package scala-isabelle
The package can be found on github at https://github.com/dominique-unruh/scala-isabelle
This particular file is adapted from https://github.com/dominique-unruh/scala-isabelle/blob/master/src/test/scala/de/unruh/isabelle/experiments/ExecuteIsar.scala
 */

package pisa.server

import io.grpc.Status
import zio.{ZEnv, ZIO}
import pisa.server.ZioServer.ZServer

import de.unruh.isabelle.pure.ToplevelState

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

  def deal_with_get_state(toplevel_state_name: String) : String = {
    if (pisaos.top_level_state_map.contains(toplevel_state_name)) pisaos.getStateString(pisaos.retrieve_tls(toplevel_state_name))
    else "Didn't find top level state of given name"
  }

  def deal_with_is_finished(toplevel_state_name: String) : String = {
    if (toplevel_state_name == "default") {
      if (pisaos.getProofLevel == 0) "true"
      else "false"
    } else {
      if (pisaos.getProofLevel(pisaos.retrieve_tls(toplevel_state_name)) == 0) "true"
      else "false"
    }
  }

  def deal_with_apply_to_tls(toplevel_state_name: String, action: String, new_name: String) : String = {
    if (pisaos.top_level_state_map.contains(toplevel_state_name)) {
      val new_state : ToplevelState = pisaos.step(action, pisaos.retrieve_tls(toplevel_state_name), 10000)
      pisaos.register_tls(name=new_name, tls=new_state)
      s"${pisaos.getStateString(new_state)}"
    }
    else "Didn't find top level state of given name"
  }

  def deal_with_proceed_before(true_command: String) : String = pisaos.step_to_transition_text(true_command, after=false)
  def deal_with_proceed_after(true_command: String) : String = pisaos.step_to_transition_text(true_command, after=true)
  def deal_with_exit(command: String) : String = {
    pisaos.step(command)
    pisaos = null
    "Exited"
  }
  def deal_with_clone(old_name: String, new_name: String) : String = {
    pisaos.clone_tls(old_name, new_name)
    "Successfully copied top level state named: " + new_name
  }

  def isabelleCommand(isa_command: IsaCommand): ZIO[
    zio.ZEnv, Status, IsaState] = {
    var proof_state : String = {
      if (isa_command.command.startsWith("<get state>")) {
        val tls_name : String = isa_command.command.stripPrefix("<get state>").trim
        deal_with_get_state(tls_name)
      }
      else if (isa_command.command.startsWith("<is finished>")) {
        val tls_name : String = isa_command.command.split("<is finished>").last.trim
        deal_with_is_finished(tls_name)
      }
      else if (isa_command.command.startsWith("<apply to top level state>")) {
        val tls_name : String = isa_command.command.split("<apply to top level state>")(1).trim
        val action : String = isa_command.command.split("<apply to top level state>")(2).trim
        val new_name : String = isa_command.command.split("<apply to top level state>")(3).trim
        deal_with_apply_to_tls(tls_name, action, new_name)
      }
      else if (isa_command.command.startsWith("<proceed before>")){
        val true_command : String = isa_command.command.stripPrefix("<proceed before>").trim
        deal_with_proceed_before(true_command)
      } 
      else if (isa_command.command.startsWith("<proceed after>")){
        val true_command : String = isa_command.command.stripPrefix("<proceed after>").trim
        deal_with_proceed_after(true_command)
      } 
      else if (isa_command.command.trim.startsWith("<clone>")){
        val old_name : String = isa_command.command.trim.split("<clone>")(1).trim
        val new_name : String = isa_command.command.trim.split("<clone>")(2).trim
        deal_with_clone(old_name, new_name)
      }
      else if (isa_command.command == "exit") deal_with_exit(isa_command.command)
      else "Unrecognised operation."
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

// object PisaOneStageTestStd {
//   val path_to_isa_bin : String = "/home/qj213/Isabelle2021"
//   val path_to_afp : String = "/home/qj213/afp-2021-10-22"
//   def main(args: Array[String]): Unit = {
//     val path_to_file : String = s"$path_to_afp/thys/Functional-Automata/NA.thy"
//     val working_directory : String = s"$path_to_afp/thys/Functional-Automata"
//     val pisaos = new PisaOS(
//       path_to_isa_bin=path_to_isa_bin,
//       path_to_file=path_to_file,
//       working_directory=working_directory)

//     implicit val ec: ExecutionContext = ExecutionContext.global
//     implicit val isabelle = pisaos.isabelle
//     pisaos.step("theory NA imports Main begin")
//     println(pisaos.proof_level(pisaos.toplevel).retrieveNow)
//     pisaos.step("theorem 1: \"1+2=3\"")
//     println(pisaos.proof_level(pisaos.toplevel).retrieveNow)
//     pisaos.step("proof -")
//     println(pisaos.proof_level(pisaos.toplevel).retrieveNow)
//     pisaos.step("show ?thesis")
//     println(pisaos.proof_level(pisaos.toplevel).retrieveNow)
//     pisaos.step("by auto")
//     println(pisaos.proof_level(pisaos.toplevel).retrieveNow)
//     pisaos.step("qed")
//     println(pisaos.proof_level(pisaos.toplevel).retrieveNow)
//   }
// }


