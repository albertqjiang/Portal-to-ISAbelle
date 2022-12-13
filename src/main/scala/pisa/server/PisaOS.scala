package pisa.server

import util.control.Breaks
import scala.collection.mutable.ListBuffer
import _root_.java.nio.file.{Files, Path}
import _root_.java.io.File
import de.unruh.isabelle.control.Isabelle
import de.unruh.isabelle.mlvalue.{AdHocConverter, MLFunction, MLFunction0, MLFunction2, MLFunction3, MLFunction4, MLValue, MLValueWrapper}
import de.unruh.isabelle.mlvalue.MLValue.{compileFunction, compileFunction0, compileValue}
import de.unruh.isabelle.pure.{Context, Position, Theory, TheoryHeader, ToplevelState}
import pisa.utils.TheoryManager
import pisa.utils.TheoryManager.{Ops, Source, Text}

import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException, blocking}
import scala.concurrent.duration.Duration
import scala.util.{Success, Failure}

import sys.process._

// Implicits
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._
import de.unruh.isabelle.control.IsabelleException

object Transition extends AdHocConverter("Toplevel.transition")

object ProofState extends AdHocConverter("Proof.state")

object RuntimeError extends AdHocConverter("Runtime.error")

object Pretty extends AdHocConverter("Pretty.T")

object ProofContext extends AdHocConverter("Proof_Context.T")

class PisaOS(var path_to_isa_bin: String, var path_to_file: String, var working_directory: String, var use_Sledgehammer: Boolean = false) {
  println("Checkpoint 1")
  val currentTheoryName: String = path_to_file.split("/").last.replace(".thy", "")
  val currentProjectName: String = {
    if (path_to_file.contains("afp")) {
      working_directory.slice(working_directory.indexOf("thys/") + 5, working_directory.length).split("/").head
    } else if (path_to_file.contains("Isabelle") && path_to_file.contains("/src/")) {
      // The theory file could be /Applications/Isabelle2021.app/Isabelle/src/HOL/Analysis/ex
      // The correct project name for it is HOL-Analysis-ex
      val relative_working_directory =
      working_directory.slice(working_directory.indexOf("/src/") + 5, working_directory.length).split(
        "/")
      relative_working_directory.mkString("-")
    } else if (path_to_file.contains("miniF2F")) {
      //      working_directory.split("/").last
      "HOL"
    } else {
      "This is not supported at the moment"
    }
  }
  println("Checkpoint 2")
  // Figure out the session roots information and import the correct libraries
  val sessionRoots: Seq[Path] = {
    if (path_to_file.contains("afp")) {
      Seq(Path.of(working_directory.slice(-1, working_directory.indexOf("thys/") + 4)))
    } else if (path_to_file.contains("Isabelle") && path_to_file.contains("/src/")) {
      val src_index: Int = working_directory.indexOf("/src/") + 5
      val session_root_path_string: String = working_directory.slice(0, src_index) +
        working_directory.slice(src_index, working_directory.length).split("/").head
      Seq(Path.of(session_root_path_string))
    } else if (path_to_file.contains("miniF2F")) {
      Seq()
    } else {
      Seq(Path.of("This is not supported at the moment"))
    }
  }
  println("Checkpoint 3")
  // Prepare setup config and the implicit Isabelle context
  val setup: Isabelle.Setup = Isabelle.Setup(
    isabelleHome = Path.of(path_to_isa_bin),
    sessionRoots = sessionRoots,
    userDir = None,
    logic = currentProjectName,
    workingDirectory = Path.of(working_directory),
    build = false
  )
  implicit val isabelle: Isabelle = new Isabelle(setup)
  implicit val ec: ExecutionContext = ExecutionContext.global
  println("Checkpoint 4")
  // Complie useful ML functions
  val script_thy: MLFunction2[String, Theory, Theory] = compileFunction[String, Theory, Theory]("fn (str,thy) => Thy_Info.script_thy Position.none str thy")
  val init_toplevel: MLFunction0[ToplevelState] = compileFunction0[ToplevelState]("Toplevel.init_toplevel")
  val is_proof: MLFunction[ToplevelState, Boolean] = compileFunction[ToplevelState, Boolean]("Toplevel.is_proof")
  val is_skipped_proof: MLFunction[ToplevelState, Boolean] = compileFunction[ToplevelState, Boolean]("Toplevel.is_skipped_proof")
  val proof_level: MLFunction[ToplevelState, Int] = compileFunction[ToplevelState, Int]("Toplevel.level")
  val proof_of: MLFunction[ToplevelState, ProofState.T] = compileFunction[ToplevelState, ProofState.T]("Toplevel.proof_of")
  val command_exception: MLFunction3[Boolean, Transition.T, ToplevelState, ToplevelState] = compileFunction[Boolean, Transition.T, ToplevelState, ToplevelState](
    "fn (int, tr, st) => Toplevel.command_exception int tr st")
  val command_exception_with_10s_timeout: MLFunction3[Boolean, Transition.T, ToplevelState, ToplevelState] = compileFunction[Boolean, Transition.T, ToplevelState, ToplevelState](
    """fn (int, tr, st) => let
      |  fun go_run (a, b, c) = Toplevel.command_exception a b c
      |  in Timeout.apply (Time.fromSeconds 10) go_run (int, tr, st) end""".stripMargin)
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
  val ancestorsNamesOfTheory: MLFunction[Theory, List[String]] = compileFunction[Theory, List[String]](
    "fn (thy) => map Context.theory_name (Context.ancestors_of thy)"
  )
  val toplevel_string_of_state: MLFunction[ToplevelState, String] = compileFunction[ToplevelState, String](
    "Toplevel.string_of_state")
  val pretty_local_facts: MLFunction2[ToplevelState, Boolean, List[Pretty.T]] = compileFunction[ToplevelState, Boolean, List[Pretty.T]](
    "fn (tls, b) => Proof_Context.pretty_local_facts b (Toplevel.context_of tls)"
  )
  val make_pretty_list_string_list: MLFunction[List[Pretty.T], List[String]] = compileFunction[List[Pretty.T], List[String]](
    "fn (pretty_list) => map Pretty.unformatted_string_of pretty_list"
  )

  val local_facts_and_defs: MLFunction[ToplevelState, List[(String, String)]] =
    compileFunction[ToplevelState, List[(String, String)]](
    """fn tls =>
      |  let val ctxt = Toplevel.context_of tls;
      |      val facts = Proof_Context.facts_of ctxt;
      |      val props = map #1 (Facts.props facts);
      |      val local_facts =
      |        (if null props then [] else [("unnamed", props)]) @
      |        Facts.dest_static true [Global_Theory.facts_of (Proof_Context.theory_of ctxt)] facts;
      |      val thms = (
      |           if null local_facts then []
      |           else
      |           (map (fn e => #2 (#2 e)) (sort_by (#1 o #2) (map (`(Proof_Context.pretty_fact ctxt)) local_facts))));
      |      val condensed_thms = fold (fn x => fn y => (x @ y)) thms [];
      |  in 
      |      map (fn thm => (
      |            Thm.get_name_hint thm,
      |            Pretty.unformatted_string_of
      |          (Element.pretty_statement ctxt "" thm)
      |         ))
      |         condensed_thms
      |  end""".stripMargin
  )
  val global_facts_and_defs: MLFunction[ToplevelState, List[(String, String)]] =
    compileFunction[ToplevelState, List[(String, String)]](
      """fn tls =>
        | map (fn tup => (#1 tup, Pretty.unformatted_string_of (Element.pretty_statement (Toplevel.context_of tls) "test" (#2 tup))))
        | (Global_Theory.all_thms_of (Proof_Context.theory_of (Toplevel.context_of tls)) false)
        """.stripMargin
    )
  val get_dependent_thms: MLFunction2[ToplevelState, String, List[String]] = compileFunction[ToplevelState, String, List[String]](
    """fn (tls, name) =>
      | let val thy = Toplevel.theory_of tls;
      |     val thm = Global_Theory.get_thms thy name;
      | in
      |     map (fn x => (#1 (#2 x))) (Thm_Deps.thm_deps thy thm)
      | end""".stripMargin
  )
  def get_dependent_theorems(tls_name: String, theorem_name: String): List[String] = {
    val toplevel_state = retrieve_tls(tls_name)
    get_dependent_thms(toplevel_state, theorem_name).force.retrieveNow
  }

  val get_used_consts: MLFunction2[ToplevelState, String, List[String]] = compileFunction[ToplevelState, String, List[String]](
    """fn(tls, inner_syntax) =>
      |let
      |  val term_to_list = fn te =>
      |  let
      |     fun leaves (left $ right) = (leaves left) @ (leaves right)
      |     |   leaves t = [t];
      |     fun filter_out (Const ("_type_constraint_", _)) = false
      |     | filter_out (Const _) = true
      |     | filter_out _ = false;
      |     val all_leaves = leaves te;
      |     val filtered_leaves = filter filter_out all_leaves;
      |     fun remove(_, []) = []
      |       | remove(x, y::l) =
      |         if x = y then
      |           remove(x, l)
      |         else
      |           y::remove(x, l);
      |      fun removeDup [] = []
      |        | removeDup(x::l) = x::removeDup(remove(x, l));
      |      fun string_of_term (Const (s, _)) = s
      |        | string_of_term _ = "";
      |  in
      |      removeDup (map string_of_term filtered_leaves)
      |  end;
      |
      |  val type_to_list = fn ty =>
      |  let
      |    fun type_t (Type ty) = [#1 ty] @ (flat (map type_t (#2 ty)))
      |    | type_t (TFree _) = []
      |    | type_t (TVar _) = [];
      |    fun filter_out_universal_type_symbols symbol =
      |  case symbol of
      |    "fun" => false
      |    | "prop" => false
      |    | "itself" => false
      |    | "dummy" => false
      |    | "proof" => false
      |    | "Pure.proof" => false
      |    | _ => true;
      |  in
      |    filter filter_out_universal_type_symbols (type_t ty)
      |  end;
      |  val ctxt = Toplevel.context_of tls;
      |  val flex = fn str =>
      |   (type_to_list (Syntax.parse_typ ctxt str))
      |   handle _ => (term_to_list (Syntax.parse_term ctxt str));
      |in
      |  flex inner_syntax
      |end""".stripMargin
  )
  def get_all_definitions(tls_name: String, theorem_string: String): List[String] = {
    val toplevel_state = retrieve_tls(tls_name)
    val quotation_split : List[String] = theorem_string.split('"').toList
    val all_inner_syntax = quotation_split.indices.collect {case i if i%2==1 => quotation_split(i)}.filter(x => x.nonEmpty).toList
    val all_defs = all_inner_syntax.map(x => get_used_consts(toplevel_state, x).force.retrieveNow)
    val deduplicated_all_defs: List[String] = all_defs.flatten
    deduplicated_all_defs.distinct
  }


  def local_facts_and_defs_string(tls: ToplevelState): String =
    local_facts_and_defs(tls).force.retrieveNow.distinct.map(x => x._1 + "<DEF>" + x._2).mkString("<SEP>")
  def local_facts_and_defs_string(tls_name: String): String = {
    val tls = retrieve_tls(tls_name)
    try {
        local_facts_and_defs_string(tls)
    } catch {
        case e: Throwable => e.toString
    }
  }
  def global_facts_and_defs_string(tls: ToplevelState): String =
    global_facts_and_defs(tls).force.retrieveNow.distinct.map(x => x._1 + "<DEF>" + x._2).mkString("<SEP>")
  def global_facts_and_defs_string(tls_name: String): String = {
    val tls = retrieve_tls(tls_name)
    try {
        global_facts_and_defs_string(tls)
    } catch {
        case e: Throwable => e.toString
    }
  }
  def total_facts_and_defs_string(tls: ToplevelState): String = {
    val local_facts = local_facts_and_defs(tls).force.retrieveNow
    val global_facts = global_facts_and_defs(tls).force.retrieveNow
    (local_facts ++ global_facts).distinct.map(x => x._1 + "<DEF>" + x._2).mkString("<SEP>")
  }
  def total_facts_and_defs_string(tls_name: String): String = {
    val tls = retrieve_tls(tls_name)
    total_facts_and_defs_string(tls)
  }
  println("Checkpoint 4")
  val header_read: MLFunction2[String, Position, TheoryHeader] =
    compileFunction[String, Position, TheoryHeader]("fn (text,pos) => Thy_Header.read pos text")

  def get_theory_ancestors_names(theory: Theory): List[String] = ancestorsNamesOfTheory(theory).force.retrieveNow

  def beginTheory(source: Source)(implicit isabelle: Isabelle, ec: ExecutionContext): Theory = {
    println("Checkpoint 9_1")
    val header = getHeader(source)
    println("Checkpoint 9_2")
    val masterDir = source.path
    println("Checkpoint 9_3")
    val registers: ListBuffer[String] = new ListBuffer[String]()
    println("Checkpoint 9_4")
    for (theory_name <- header.imports) {
      if (importMap.contains(theory_name)) {
        registers += s"${currentProjectName}.${importMap(theory_name)}"
      } else registers += theory_name
    }
    println("Checkpoint 9_5")
    println(masterDir)
    println(header)
    println(registers.toList)
    Ops.begin_theory(masterDir, header, registers.toList.map(Theory.apply)).retrieveNow
  }
  println("Checkpoint 5")
  def getHeader(source: Source)(implicit isabelle: Isabelle, ec: ExecutionContext): TheoryHeader = source match {
    case Text(text, path, position) => Ops.header_read(text, position).retrieveNow
  }

  // Find out about the starter string
  private val fileContent: String = Files.readString(Path.of(path_to_file))
  val fileContentCopy: String = fileContent
  println("Checkpoint 6")
  private def getStarterString: String = {
    val decoyThy: Theory = Theory("Main")
    for ((transition, text) <- parse_text(decoyThy, fileContent).force.retrieveNow) {
      if (text.contains("theory") && text.contains(currentTheoryName) && text.contains("begin")) {
        return text
      }
    }
    "This is wrong!!!"
  }
  println("Checkpoint 7")
  val starter_string: String = getStarterString.trim.replaceAll("\n", " ").trim

  // Find out what to import from the current directory
  def getListOfTheoryFiles(dir: File): List[File] = {
    if (dir.exists && dir.isDirectory) {
      var listOfFilesBuffer: ListBuffer[File] = new ListBuffer[File]
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

  def sanitiseInDirectoryName(fileName: String): String = {
    fileName.replace("\"", "").split("/").last.split(".thy").head
  }
  println("Checkpoint 8")
  // Figure out what theories to import
  val available_files: List[File] = getListOfTheoryFiles(new File(working_directory))
  var available_imports_buffer: ListBuffer[String] = new ListBuffer[String]
  for (file_name <- available_files) {
    if (file_name.getName().endsWith(".thy")) {
      available_imports_buffer = available_imports_buffer += file_name.getName().split(".thy")(0)
    }
  }
  var available_imports: Set[String] = available_imports_buffer.toSet
  val theoryNames: List[String] = starter_string.split("imports")(1).split("begin")(0).split(" ").map(_.trim).filter(_.nonEmpty).toList
  var importMap: Map[String, String] = Map()
  for (theory_name <- theoryNames) {
    val sanitisedName = sanitiseInDirectoryName(theory_name)
    if (available_imports(sanitisedName)) {
      importMap += (theory_name.replace("\"", "") -> sanitisedName)
    }
  }
  
  var top_level_state_map: Map[String, MLValue[ToplevelState]] = Map()
  val theoryStarter: TheoryManager.Text = TheoryManager.Text(starter_string, setup.workingDirectory.resolve(""))
  println("Checkpoint 9")
  var thy1: Theory = beginTheory(theoryStarter)
  thy1.await
  println("Checkpoint 10")
  
  // setting up Sledgehammer
  // val thy_for_sledgehammer: Theory = Theory("HOL.List")
  val thy_for_sledgehammer = thy1
  val Sledgehammer: String = thy_for_sledgehammer.importMLStructureNow("Sledgehammer")
  val Sledgehammer_Commands: String = thy_for_sledgehammer.importMLStructureNow("Sledgehammer_Commands")
  val Sledgehammer_Fact: String = thy_for_sledgehammer.importMLStructureNow("Sledgehammer_Fact")
  val Sledgehammer_MaSh: String = thy_for_sledgehammer.importMLStructureNow("Sledgehammer_MaSh")
  val Sledgehammer_Prover: String = thy_for_sledgehammer.importMLStructureNow("Sledgehammer_Prover")
  val Sledgehammer_Prover_ATP: String = thy_for_sledgehammer.importMLStructureNow("Sledgehammer_Prover_ATP")
  val Sledgehammer_Prover_Minimize: String = thy_for_sledgehammer.importMLStructureNow("Sledgehammer_Prover_Minimize")
  val Sledgehammer_Util: String = thy_for_sledgehammer.importMLStructureNow("Sledgehammer_Util")
  val ATP_Util: String = thy_for_sledgehammer.importMLStructureNow("ATP_Util")
  val ATP_Proof: String = thy_for_sledgehammer.importMLStructureNow("ATP_Proof")
  
  // prove_with_Sledgehammer is mostly identical to check_with_Sledgehammer except for that when the returned Boolean is true, it will 
  // also return a non-empty list of Strings, each of which contains executable commands to close the top subgoal. We might need to chop part of 
  // the string to get the actual tactic. For example, one of the string may look like "Try this: by blast (0.5 ms)".
  println("Checkpoint 11")

  val normal_with_Sledgehammer: MLFunction4[ToplevelState, Theory, List[String], List[String], (Boolean, (String, List[String]))] = 
    compileFunction[ToplevelState, Theory, List[String], List[String], (Boolean, (String, List[String]))](
      s""" fn (state, thy, adds, dels) => let
         | fun get_refs_and_token_lists (name) = (Facts.named name, []);
         | val adds_refs_and_token_lists = map get_refs_and_token_lists adds;
         | val dels_refs_and_token_lists = map get_refs_and_token_lists dels;
         | val override = {add=adds_refs_and_token_lists,del=dels_refs_and_token_lists,only=false};
         |
         | val ordered_outcome_codes = [${Sledgehammer}.SH_Some, ${Sledgehammer}.SH_Unknown, ${Sledgehammer}.SH_Timeout, ${Sledgehammer}.SH_None];
         |
         | fun max_outcome_code codes =
         |   NONE
         |   |> fold (fn candidate =>
         |       fn accum as SOME _ => accum
         |        | NONE => if member (op =) codes candidate then SOME candidate else NONE)
         |     ordered_outcome_codes
         |   |> the_default ${Sledgehammer}.SH_Unknown;
         |
         | fun launch_prover (params as {debug, verbose, spy, max_facts, minimize, timeout, preplay_timeout,
         |      expect, ...}) mode writeln_result only learn
         |    {comment, state, goal, subgoal, subgoal_count, factss as (_, facts) :: _, found_proof} name =
         |  let
         |    val ctxt = Proof.context_of state
         |
         |    val hard_timeout = ${Sledgehammer_Util}.time_mult 5.0 timeout
         |    val _ = ${Sledgehammer_Util}.spying spy (fn () => (state, subgoal, name, "Launched"));
         |    val max_facts = max_facts |> the_default (${Sledgehammer_Prover_Minimize}.default_max_facts_of_prover ctxt name)
         |    val num_facts = length facts |> not only ? Integer.min max_facts
         |
         |    val problem =
         |      {comment = comment, state = state, goal = goal, subgoal = subgoal,
         |       subgoal_count = subgoal_count,
         |       factss = factss
         |       |> map (apsnd ((not (${Sledgehammer_Prover_ATP}.is_ho_atp ctxt name)
         |           ? filter_out (fn ((_, (_, Induction)), _) => true | _ => false))
         |         #> take num_facts)),
         |       found_proof = found_proof}
         |
         |    fun print_used_facts used_facts used_from =
         |      tag_list 1 used_from
         |      |> map (fn (j, fact) => fact |> apsnd (K j))
         |      |> ${Sledgehammer_Prover}.filter_used_facts false used_facts
         |      |> map (fn ((name, _), j) => name ^ "@" ^ string_of_int j)
         |      |> commas
         |      |> prefix ("Fact" ^ ${Sledgehammer_Util}.plural_s (length facts) ^ " in " ^ quote name ^
         |        " proof (of " ^ string_of_int (length facts) ^ "): ")
         |      |> writeln
         |
         |    fun spying_str_of_res ({outcome = NONE, used_facts, used_from, ...} : ${Sledgehammer_Prover}.prover_result) =
         |        let
         |          val num_used_facts = length used_facts
         |
         |          fun find_indices facts =
         |            tag_list 1 facts
         |            |> map (fn (j, fact) => fact |> apsnd (K j))
         |            |> ${Sledgehammer_Prover}.filter_used_facts false used_facts
         |            |> distinct (eq_fst (op =))
         |            |> map (prefix "@" o string_of_int o snd)
         |
         |          fun filter_info (fact_filter, facts) =
         |            let
         |              val indices = find_indices facts
         |              (* "Int.max" is there for robustness -- it shouldn't be necessary *)
         |              val unknowns = replicate (Int.max (0, num_used_facts - length indices)) "?"
         |            in
         |              (commas (indices @ unknowns), fact_filter)
         |            end
         |
         |          val filter_infos =
         |            map filter_info (("actual", used_from) :: factss)
         |            |> AList.group (op =)
         |            |> map (fn (indices, fact_filters) => commas fact_filters ^ ": " ^ indices)
         |        in
         |          "Success: Found proof with " ^ string_of_int num_used_facts ^ " of " ^
         |          string_of_int num_facts ^ " fact" ^ ${Sledgehammer_Util}.plural_s num_facts ^
         |          (if num_used_facts = 0 then "" else ": " ^ commas filter_infos)
         |        end
         |      | spying_str_of_res {outcome = SOME failure, ...} =
         |        "Failure: " ^ ${ATP_Proof}.string_of_atp_failure failure
         |
         |    fun really_go () =
         |      problem
         |      |> ${Sledgehammer_Prover_Minimize}.get_minimizing_prover ctxt mode learn name params
         |      |> verbose ? tap (fn {outcome = NONE, used_facts as _ :: _, used_from, ...} =>
         |          print_used_facts used_facts used_from
         |        | _ => ())
         |      |> spy ? tap (fn res => ${Sledgehammer_Util}.spying spy (fn () => (state, subgoal, name, spying_str_of_res res)))
         |      |> (fn {outcome, used_facts, preferred_methss, message, ...} =>
         |        (if outcome = SOME ${ATP_Proof}.TimedOut then ${Sledgehammer}.SH_Timeout
         |         else if is_some outcome then ${Sledgehammer}.SH_None
         |         else ${Sledgehammer}.SH_Some,
         |         fn () => message (fn () => ${Sledgehammer}.play_one_line_proof minimize preplay_timeout used_facts state
         |           subgoal preferred_methss)))
         |
         |    fun go () =
         |      let
         |        val (outcome_code, message) =
         |          if debug then
         |            really_go ()
         |          else
         |            (really_go ()
         |             handle
         |               ERROR msg => (${Sledgehammer}.SH_Unknown, fn () => "Error: " ^ msg ^ "\\n")
         |             | exn =>
         |               if Exn.is_interrupt exn then Exn.reraise exn
         |               else (${Sledgehammer}.SH_Unknown, fn () => "Internal error:\\n" ^ Runtime.exn_message exn ^ "\\n"))
         |
         |        val _ =
         |          (* The "expect" argument is deliberately ignored if the prover is
         |             missing so that the "Metis_Examples" can be processed on any
         |             machine. *)
         |          if expect = "" orelse outcome_code = expect orelse
         |             not (${Sledgehammer_Prover_Minimize}.is_prover_installed ctxt name) then
         |            ()
         |          else
         |            error ("Unexpected outcome: " ^ quote outcome_code)
         |      in (outcome_code, message) end
         |  in
         |    if mode = ${Sledgehammer_Prover}.Auto_Try then
         |      let val (outcome_code, message) = Timeout.apply timeout go () in
         |        (outcome_code, if outcome_code = ${Sledgehammer}.SH_Some then [message ()] else [])
         |      end
         |    else
         |      let
         |        val (outcome_code, message) = Timeout.apply hard_timeout go ()
         |        val outcome =
         |          if outcome_code = ${Sledgehammer}.SH_Some orelse mode = ${Sledgehammer_Prover}.Normal then quote name ^ ": " ^ message () else ""
         |        val _ =
         |          if outcome <> "" andalso is_some writeln_result then the writeln_result outcome
         |          else writeln outcome
         |      in (outcome_code, if outcome_code = ${Sledgehammer}.SH_Some then [message ()] else []) end
         |  end;
         |
         |  fun run_sledgehammer (params as {verbose, spy, provers, induction_rules, max_facts, max_proofs,
         |      slices, ...}) mode writeln_result i
         |    (fact_override as {only, ...}) state =
         |  if null provers then
         |    error "No prover is set"
         |  else
         |    (case ${Sledgehammer_Util}.subgoal_count state of
         |      0 => (error "No subgoal!"; (false, (${Sledgehammer}.SH_None, [])))
         |    | n =>
         |      let
         |        val _ = Proof.assert_backward state
         |        val print = if mode = ${Sledgehammer_Prover}.Normal andalso is_none writeln_result then writeln else K ()
         |        
         |        val found_proofs = Synchronized.var "found_proofs" 0
         |
         |        fun found_proof prover_name =
         |          if mode = Normal then
         |            (Synchronized.change found_proofs (fn n => n + 1);
         |             (the_default writeln writeln_result) (prover_name ^ " found a proof..."))
         |          else
         |            ()
         |
         |        val ctxt = Proof.context_of state
         |        val inst_inducts = induction_rules = SOME Instantiate
         |        val {facts = chained, goal, ...} = Proof.goal state
         |        val (_, hyp_ts, concl_t) = ${ATP_Util}.strip_subgoal goal i ctxt
         |        val _ =
         |          (case find_first (not o ${Sledgehammer_Prover_Minimize}.is_prover_supported ctxt) provers of
         |            SOME name => error ("No such prover: " ^ name)
         |          | NONE => ())
         |        val _ = print "Sledgehammering..."
         |        val _ = spying spy (fn () => (state, i, "***", "Starting " ^ str_of_mode mode ^ " mode"))
         |        val ({elapsed, ...}, all_facts) = Timing.timing
         |          (nearly_all_facts_of_context ctxt inst_inducts fact_override chained_thms hyp_ts) concl_t
         |        val _ = spying spy (fn () => (state, i, "All",
         |          "Extracting " ^ string_of_int (length all_facts) ^ " facts from background theory in " ^
         |          string_of_int (Time.toMilliseconds elapsed) ^ " ms"))
         |
         |        val spying_str_of_factss =
         |          commas o map (fn (filter, facts) => filter ^ ": " ^ string_of_int (length facts))
         |
         |        fun get_factss provers =
         |          let
         |            val max_max_facts =
         |              (case max_facts of
         |                SOME n => n
         |              | NONE =>
         |                fold (fn prover =>
         |                    fold (fn ((_, n, _), _) => Integer.max n) (get_slices ctxt prover))
         |                  provers 0)
         |              * 51 div 50  (* some slack to account for filtering of induction facts below *)
         |
         |            val ({elapsed, ...}, factss) = Timing.timing
         |              (relevant_facts ctxt params (hd provers) max_max_facts fact_override hyp_ts concl_t)
         |              all_facts
         |
         |            val induction_rules = the_default (if only then Include else Exclude) induction_rules
         |            val factss = map (apsnd (maybe_filter_out_induction_rules induction_rules)) factss
         |
         |            val () = spying spy (fn () => (state, i, "All",
         |              "Filtering facts in " ^ string_of_int (Time.toMilliseconds elapsed) ^
         |              " ms (MaSh algorithm: " ^ str_of_mash_algorithm (the_mash_algorithm ()) ^ ")"));
         |            val () = if verbose then print (string_of_factss factss) else ()
         |            val () = spying spy (fn () =>
         |              (state, i, "All", "Selected facts: " ^ spying_str_of_factss factss))
         |          in
         |            factss
         |          end
         |
         |        fun launch_provers () =
         |          let
         |            val factss = get_factss provers
         |            val problem =
         |              {comment = "", state = state, goal = goal, subgoal = i, subgoal_count = n,
         |               factss = factss, found_proof = found_proof}
         |            val learn = ${Sledgehammer_MaSh}.mash_learn_proof ctxt params (Thm.prop_of goal)
         |            val launch = launch_prover params mode writeln_result only learn
         |            val schedule =
         |              if mode = Auto_Try then provers
         |              else schedule_of_provers provers slices
         |            val prover_slices = prover_slices_of_schedule ctxt factss params schedule
         |
         |            val _ =
         |              if verbose then
         |                writeln ("Running " ^ commas (map fst prover_slices) ^ "...")
         |              else
         |                ()
         |          in
         |            if mode = ${Sledgehammer_Prover}.Auto_Try then
         |              (SH_Unknown, "")
         |              |> fold (fn (prover, slice) =>
         |                   fn accum as (SH_Some _, _) => accum
         |                     | _ => launch problem slice prover)
         |                prover_slices
         |            else
         |              (learn chained_thms;
         |               Par_List.map (fn (prover, slice) =>
         |                   if Synchronized.value found_proofs < max_proofs then
         |                     launch problem slice prover
         |                   else
         |                     (SH_None, ""))
         |                  prover_slices
         |                |> max_outcome)
         |          end
         |      in
         |        (launch_provers ()
         |         handle Timeout.TIMEOUT _ => (SH_Timeout, ""))
         |        |> `(fn (outcome, message) =>
         |          (case outcome of
         |            SH_Some _ => (the_default writeln writeln_result "QED"; true)
         |          | SH_Unknown => (the_default writeln writeln_result message; false)
         |          | SH_Timeout => (the_default writeln writeln_result "No proof found"; false)
         |          | SH_None => (the_default writeln writeln_result
         |                (if message = "" then "No proof found" else "Warning: " ^ message);
         |              false)))
         |      end);
         |
         |    fun go_run (state, thy) = 
         |        let
         |          val p_state = Toplevel.proof_of state;
         |          val ctxt = Proof.context_of p_state;
         |          val params = ${Sledgehammer_Commands}.default_params thy
         |                [("provers", "z3 cvc4 spass vampire e"),("timeout","30"),("preplay_timeout","5"),("minimize","false"),("isar_proofs","false"),("smt_proofs","true"),("learn","false")];
         |        in
         |          run_sledgehammer params ${Sledgehammer_Prover}.Normal NONE 1 override p_state
         |        end
         |    in Timeout.apply (Time.fromSeconds 35) go_run (state, thy) end
         |""".stripMargin
    )

  // val normal_with_Sledgehammer: MLFunction4[ToplevelState, Theory, List[String], List[String], (Boolean, (String, List[String]))] = 
  //   compileFunction[ToplevelState, Theory, List[String], List[String], (Boolean, (String, List[String]))](
  //     s""" fn (state, thy, adds, dels) => 
  //        |    let
  //        |       val override = {add=[],del=[],only=false};
  //        |       fun go_run (state, thy) = 
  //        |          let
  //        |             val p_state = Toplevel.proof_of state;
  //        |             val ctxt = Proof.context_of p_state;
  //        |             val params = ${Sledgehammer_Commands}.default_params thy
  //        |                [("provers", "e"),("timeout","30"),("verbose","true")];
  //        |             val results = ${Sledgehammer}.run_sledgehammer params ${Sledgehammer_Prover}.Normal NONE 1 override p_state;
  //        |             val (result, (outcome, step)) = results;
  //        |           in
  //        |             (result, (${Sledgehammer}.short_string_of_sledgehammer_outcome outcome, [step]))
  //        |           end;
  //        |    in 
  //        |      Timeout.apply (Time.fromSeconds 35) go_run (state, thy) end
  //        |""".stripMargin
  //   )

  var toplevel: ToplevelState = init_toplevel().force.retrieveNow
  println("Checkpoint 12")
  def reset_map(): Unit = {
    top_level_state_map = Map()
  }

  def reset_prob(): Unit = {
    thy1 = beginTheory(theoryStarter)
    toplevel = init_toplevel().force.retrieveNow
    reset_map()
  }

  def getFacts(stateString: String): String = {
    var facts: String = ""
    if (stateString.trim.nonEmpty) {
      // Limit the maximum number of local facts to be 5
      for (fact <- make_pretty_list_string_list(pretty_local_facts(toplevel, false)).retrieveNow.takeRight(5)) {
        facts = facts + fact + "<\\PISASEP>"
      }
    }
    facts
  }

  def getStateString(top_level_state: ToplevelState): String =
    toplevel_string_of_state(top_level_state).force.retrieveNow

  def getStateString: String = getStateString(toplevel)

  def is_done(top_level_state: ToplevelState): Boolean = {
    getProofLevel(top_level_state) == 0
  }

  def getProofLevel(top_level_state: ToplevelState): Int =
    proof_level(top_level_state).retrieveNow

  def getProofLevel: Int = getProofLevel(toplevel)

  def singleTransitionWith10sTimeout(single_transition: Transition.T, top_level_state: ToplevelState): ToplevelState = {
    command_exception_with_10s_timeout(true, single_transition, top_level_state).retrieveNow.force
  }

  def singleTransition(single_transition: Transition.T, top_level_state: ToplevelState): ToplevelState = {
    command_exception(true, single_transition, top_level_state).retrieveNow.force
  }

  def singleTransition(singTransition: Transition.T): String = {
    //    TODO: inlcude global facts
    toplevel = singleTransition(singTransition, toplevel)
    getStateString
  }

  def parseStateAction(isarString: String): String = {
    // Here we directly apply transitions to the theory repeatedly
    // to get the (last_observation, action, observation, reward, done) tuple
    var stateActionTotal: String = ""
    val continue = new Breaks
    // Initialising the state string
    var stateString = getStateString
    var proof_level_number = getProofLevel
    Breaks.breakable {
      for ((transition, text) <- parse_text(thy1, isarString).force.retrieveNow)
        continue.breakable {
          if (text.trim.isEmpty) continue.break
          else {
            stateActionTotal = stateActionTotal + (stateString + "<\\STATESEP>" + text.trim + "<\\STATESEP>" + s"$getProofLevel" + "<\\TRANSEP>")
            stateString = singleTransition(transition)
          }
        }
    }
    stateActionTotal
  }

  def parse: String = parseStateAction(fileContent)

  @throws(classOf[IsabelleException])
  @throws(classOf[TimeoutException])
  def step(isar_string: String, top_level_state: ToplevelState, timeout_in_millis: Int = 2000): ToplevelState = {
    println("Begin step")
    // Normal isabelle business
    var tls_to_return: ToplevelState = clone_tls_scala(top_level_state)
    var stateString: String = ""
    val continue = new Breaks
    println("Starting to step")
    val f_st = Future.apply {
      blocking {
        Breaks.breakable {
          println("start parsing")
          for ((transition, text) <- parse_text(thy1, isar_string).force.retrieveNow)
            continue.breakable {
              if (text.trim.isEmpty) continue.break
              // println("Small step: " + text)
              tls_to_return = singleTransitionWith10sTimeout(transition, tls_to_return)
              // println("Applied transition successfully")
            }
        }
      }
      "success"
    }
    println("inter")

    // Await for infinite amount of time
    Await.result(f_st, Duration.Inf)
    println(f_st)
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

  def normal_with_hammer(top_level_state: ToplevelState, added_names: List[String], deleted_names: List[String], timeout_in_millis: Int = 35000): (Boolean, List[String]) = {
    val f_res: Future[(Boolean, List[String])] = Future.apply {
      val first_result = normal_with_Sledgehammer(top_level_state, thy1, added_names, deleted_names).force.retrieveNow
      (first_result._1, first_result._2._2)
    }
    Await.result(f_res, Duration(timeout_in_millis, "millis"))
  }

  println("Checkpoint 13")
  val transitions_and_texts = parse_text(thy1, fileContent).force.retrieveNow
  var frontier_proceeding_index = 0
  println("Checkpoint 14")
  def accumulative_step_to_before_transition_starting(isar_string: String): String = {
    val sanitised_isar_string = isar_string.trim.replaceAll("\n", " ").replaceAll(" +", " ")
    val (transition, text) = transitions_and_texts(frontier_proceeding_index)
    val sanitised_text = text.trim.replaceAll("\n", " ").replaceAll(" +", " ")
    if (sanitised_text.trim.isEmpty) {
      frontier_proceeding_index += 1
      accumulative_step_to_before_transition_starting(sanitised_isar_string)
    } else if (sanitised_text.trim == sanitised_isar_string) {
      val top_level_proceeding_state = retrieve_tls("default")
      getStateString(top_level_proceeding_state)
    } else {
      frontier_proceeding_index += 1
      val top_level_proceeding_state = retrieve_tls("default")
      val resulting_state: ToplevelState = singleTransition(transition, top_level_proceeding_state)
      register_tls("default", resulting_state)
      accumulative_step_to_before_transition_starting(sanitised_isar_string)
    }
  }

  def step_to_transition_text(isar_string: String, after: Boolean = true): String = {
    var stateString: String = ""
    val continue = new Breaks
    Breaks.breakable {
      for ((transition, text) <- parse_text(thy1, fileContent).force.retrieveNow) {
        continue.breakable {
          if (text.trim.isEmpty) continue.break
          val trimmed_text = text.trim.replaceAll("\n", " ").replaceAll(" +", " ")
          if (trimmed_text == isar_string) {
            if (after) stateString = singleTransition(transition)
            return stateString
          }
          stateString = singleTransition(transition)
        }
      }
    }
    println("Did not find the text")
    stateString
  }

  // Manage top level states with the internal map
  def copy_tls: MLValue[ToplevelState] = toplevel.mlValue

  def clone_tls(tls_name: String): Unit = top_level_state_map += (tls_name -> copy_tls)

  def clone_tls(old_name: String, new_name: String): Unit = top_level_state_map += (new_name -> top_level_state_map(old_name))

  def clone_tls_scala(tls_scala: ToplevelState): ToplevelState = ToplevelState.instantiate(tls_scala.mlValue)

  def register_tls(name: String, tls: ToplevelState): Unit = top_level_state_map += (name -> tls.mlValue)

  def retrieve_tls(tls_name: String): ToplevelState = ToplevelState.instantiate(top_level_state_map(tls_name))
}