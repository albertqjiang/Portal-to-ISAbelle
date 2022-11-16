package pisa.agent


import de.unruh.isabelle.control.Isabelle
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._
import de.unruh.isabelle.pure.ToplevelState
import pisa.server.PisaOS

import scala.concurrent._
import scala.concurrent.ExecutionContext
import scala.concurrent.blocking
import scala.concurrent.ExecutionContext.Implicits._
import scala.util.control.Breaks
import java.util.concurrent.CancellationException
import java.io._
import java.util.Base64
import java.nio.charset.StandardCharsets.UTF_8

object RefactorTest {
  val path_to_isa_bin: String = "/home/qj213/Isabelle2021"
  val working_directory: String = "/home/qj213/afp-2021-10-22/thys/RefinementReactive"
  val path_to_file: String = "/home/qj213/afp-2021-10-22/thys/RefinementReactive/Temporal.thy"
  val theorem_string = """lemma until_always: "(INF n. (SUP i \<in> {i. i < n} . - p i) \<squnion> ((p :: nat \<Rightarrow> 'a) n)) \<le> p n"""".stripMargin

  def main(args: Array[String]): Unit = {
    val pisaos = new PisaOS(
      path_to_isa_bin=path_to_isa_bin,
      path_to_file=path_to_file,
      working_directory=working_directory
    )
    implicit val isabelle: Isabelle = pisaos.isabelle
    implicit val ec: ExecutionContext = pisaos.ec

    pisaos.step_to_transition_text(theorem_string, after = true)

    println(pisaos.exp_with_hammer(pisaos.toplevel))
  }
}


final class Interrupt extends (() => Boolean) {
  // We need a state-machine to track the progress.
  // It can have the following states:
  // a null reference means execution has not started.
  // a Thread reference means that the execution has started but is not done.
  // a this reference means that it is already cancelled or is already too late.
  private[this] final var state: AnyRef = null

  /**
   * This is the signal to cancel the execution of the logic.
   * Returns whether the cancellation signal was successully issued or not.
   **/
  override final def apply(): Boolean = this.synchronized {
    state match {
      case null        =>
        state = this
        true
      case _: this.type => false
      case t: Thread   =>
        state = this
        t.interrupt()
        true
    }
  }

  // Initializes right before execution of logic and
  // allows to not run the logic at all if already cancelled.
  private[this] final def enter(): Boolean =
   this.synchronized {
     state match {
        case _: this.type => false
        case null =>
         state = Thread.currentThread
         true
     }
  }

  // Cleans up after the logic has executed
  // Prevents cancellation to occur "too late"
  private[this] final def exit(): Boolean =
   this.synchronized {
     state match {
       case _: this.type => false
       case t: Thread =>
         state = this
         true
     }
  }

  /**
   * Executes the suplied block of logic and returns the result.
   * Throws CancellationException if the block was interrupted.
   **/
  def interruptibly[T](block: =>T): T =
    if (enter()) {
      try block catch {
        case ie: InterruptedException => throw new CancellationException()
      } finally {
        if(!exit() && Thread.interrupted()) 
          () // If we were interrupted and flag was not cleared
      }
    } else throw new CancellationException()
}

class FutureInterrupt(val future: Future.type) extends AnyVal {
  def interruptibly[T](block: => T)(implicit ec: ExecutionContext): (Future[T], () => Boolean) = {
    val interrupt = new Interrupt()
    (Future(interrupt.interruptibly(block))(ec), interrupt)
  }
}

object TimeoutTest {
  def main(args: Array[String]): Unit = {
    val ph = new FutureInterrupt(Future)
    val (f, cancel) = ph.interruptibly {
        blocking { Thread.sleep(1000000) }
        println("foo")
    }
    println(f)
    println(f.isCompleted)
    val timeout_future = Future {
      Thread.sleep(5000); "timeout"
    }
    val result = Await.result(Future.firstCompletedOf(Seq(f, timeout_future)), scala.concurrent.duration.Duration.Inf)

    if (result == "timeout") {
      cancel()
    }

    Thread.sleep(500)
    println(f)
    println(f.isCompleted)
  }
}