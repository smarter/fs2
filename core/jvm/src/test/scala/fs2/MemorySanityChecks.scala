package fs2

import scala.concurrent.ExecutionContext
import cats.effect.IO

// Sanity tests - not run as part of unit tests, but these should run forever
// at constant memory.

object ResourceTrackerSanityTest extends App {
  val big = Stream.constant(1).flatMap { n =>
    Stream.bracket(IO(()))(_ => IO(())).flatMap(_ => Stream.emits(List(1, 2, 3)))
  }
  big.compile.drain.unsafeRunSync()
}

object RepeatPullSanityTest extends App {
  def id[A]: Pipe[Pure, A, A] = _.repeatPull {
    _.uncons1.flatMap {
      case Some((h, t)) => Pull.output1(h).as(Some(t));
      case None         => Pull.pure(None)
    }
  }
  Stream.constant(1).covary[IO].through(id[Int]).compile.drain.unsafeRunSync()
}

object RepeatEvalSanityTest extends App {
  def id[A]: Pipe[Pure, A, A] = {
    def go(s: Stream[Pure, A]): Pull[Pure, A, Unit] =
      s.pull.uncons1.flatMap {
        case Some((h, t)) => Pull.output1(h) >> go(t); case None => Pull.done
      }
    in =>
      go(in).stream
  }
  Stream.repeatEval(IO(1)).through(id[Int]).compile.drain.unsafeRunSync()
}

object AppendSanityTest extends App {
  (Stream.constant(1).covary[IO] ++ Stream.empty).pull.echo.stream.compile.drain
    .unsafeRunSync()
}

object DrainOnCompleteSanityTest extends App {
  import ExecutionContext.Implicits.global
  val s = Stream.repeatEval(IO(1)).pull.echo.stream.drain ++ Stream.eval_(IO(println("done")))
  Stream.empty.covary[IO].merge(s).compile.drain.unsafeRunSync()
}

object ConcurrentJoinSanityTest extends App {
  import ExecutionContext.Implicits.global
  Stream
    .constant(Stream.empty[IO])
    .covary[IO]
    .join(5)
    .compile
    .drain
    .unsafeRunSync
}

object DanglingDequeueSanityTest extends App {
  import ExecutionContext.Implicits.global
  Stream
    .eval(async.unboundedQueue[IO, Int])
    .flatMap { q =>
      Stream.constant(1).flatMap { _ =>
        Stream.empty[IO].mergeHaltBoth(q.dequeue)
      }
    }
    .compile
    .drain
    .unsafeRunSync
}

object AwakeEverySanityTest extends App {
  import scala.concurrent.duration._
  import ExecutionContext.Implicits.global
  Stream
    .awakeEvery[IO](1.millis)
    .flatMap { _ =>
      Stream.eval(IO(()))
    }
    .compile
    .drain
    .unsafeRunSync
}

object SignalDiscreteSanityTest extends App {
  import ExecutionContext.Implicits.global
  Stream
    .eval(async.signalOf[IO, Unit](()))
    .flatMap { signal =>
      signal.discrete.evalMap(a => signal.set(a))
    }
    .compile
    .drain
    .unsafeRunSync
}

object SignalContinuousSanityTest extends App {
  import ExecutionContext.Implicits.global
  Stream
    .eval(async.signalOf[IO, Unit](()))
    .flatMap { signal =>
      signal.continuous.evalMap(a => signal.set(a))
    }
    .compile
    .drain
    .unsafeRunSync
}

object ConstantEvalSanityTest extends App {
  var cnt = 0
  var start = System.currentTimeMillis
  Stream
    .constant(())
    .flatMap { _ =>
      Stream.eval(IO {
        cnt = (cnt + 1) % 1000000
        if (cnt == 0) {
          val now = System.currentTimeMillis
          println("Elapsed: " + (now - start))
          start = now
        }
      })
    }
    .compile
    .drain
    .unsafeRunSync
}

object RecursiveFlatMapTest extends App {
  def loop: Stream[IO, Unit] = Stream(()).covary[IO].flatMap(_ => loop)
  loop.compile.drain.unsafeRunSync
}

object StepperSanityTest extends App {
  import Pipe.Stepper
  def id[I, O](p: Pipe[Pure, I, O]): Pipe[Pure, I, O] = {
    def go(stepper: Stepper[I, O], s: Stream[Pure, I]): Pull[Pure, O, Unit] =
      stepper.step match {
        case Stepper.Done      => Pull.done
        case Stepper.Fail(err) => Pull.raiseError(err)
        case Stepper.Emits(segment, next) =>
          Pull.output(segment) >> go(next, s)
        case Stepper.Await(receive) =>
          s.pull.uncons.flatMap {
            case Some((hd, tl)) => go(receive(Some(hd)), tl)
            case None           => go(receive(None), Stream.empty)
          }
      }
    s =>
      go(Pipe.stepper(p), s).stream
  }
  val incr: Pipe[Pure, Int, Int] = _.map(_ + 1)
  Stream.constant(0).covary[IO].through(id(incr)).compile.drain.unsafeRunSync
}

object StepperSanityTest2 extends App {
  import Pipe.Stepper
  def go[I, O](i: I)(s: Stepper[I, O]): Unit =
    s.step match {
      case Stepper.Done        => ()
      case Stepper.Fail(err)   => throw err
      case Stepper.Emits(s, n) => go(i)(n)
      case Stepper.Await(r)    => go(i)(r(Some(Segment(i))))
    }
  go(0)(Pipe.stepper(_.map(_ + 1)))
}

object EvalFlatMapMapTest extends App {
  Stream
    .eval(IO(()))
    .flatMap(_ => Stream.emits(Seq()))
    .map(x => x)
    .repeat
    .compile
    .drain
    .unsafeRunSync()
}

object QueueTest extends App {
  import ExecutionContext.Implicits.global
  Stream
    .eval(async.boundedQueue[IO, Either[Throwable, Option[Int]]](10))
    .flatMap { queue =>
      queue.dequeueAvailable.rethrow.unNoneTerminate
        .concurrently(
          Stream
            .constant(1, 128)
            .covary[IO]
            .noneTerminate
            .attempt
            .evalMap(queue.enqueue1(_))
        )
        .evalMap(_ => IO.unit)
    }
    .compile
    .drain
    .unsafeRunSync()
}

object ProgressMerge extends App {
  import ExecutionContext.Implicits.global
  val progress = Stream.constant(1, 128).covary[IO]
  progress.merge(progress).compile.drain.unsafeRunSync()
}

object HungMerge extends App {
  import ExecutionContext.Implicits.global
  val hung = Stream.eval(IO.async[Int](_ => ()))
  val progress = Stream.constant(1, 128).covary[IO]
  hung.merge(progress).compile.drain.unsafeRunSync()
}

object ZipThenBindThenJoin extends App {
  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.duration._

  val sources: Stream[IO, Stream[IO, Int]] = Stream(Stream.empty[IO]).repeat

  Stream
    .fixedDelay[IO](1.milliseconds)
    .zip(sources)
    .flatMap {
      case (_, s) =>
        s.map(Stream.constant(_).covary).joinUnbounded
    }
    .compile
    .drain
    .unsafeRunSync()
}
