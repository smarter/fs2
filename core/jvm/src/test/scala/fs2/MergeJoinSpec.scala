package fs2

import scala.concurrent.duration._
import cats.effect.IO
import cats.implicits._

import TestUtil._

class MergeJoinSpec extends Fs2Spec {

  "concurrent" - {

    "either" in forAll { (s1: PureStream[Int], s2: PureStream[Int]) =>
      val _ = s1.get.either(s2.get.covary[IO])
      val es = runLog { s1.get.covary[IO].either(s2.get) }
      es.collect { case Left(i)  => i } shouldBe runLog(s1.get)
      es.collect { case Right(i) => i } shouldBe runLog(s2.get)
    }

    "merge" in forAll { (s1: PureStream[Int], s2: PureStream[Int]) =>
      runLog { s1.get.merge(s2.get.covary[IO]) }.toSet shouldBe
        (runLog(s1.get).toSet ++ runLog(s2.get).toSet)
    }

    "merge (left/right identity)" in forAll { (s1: PureStream[Int]) =>
      runLog { s1.get.covary[IO].merge(Stream.empty) } shouldBe runLog(s1.get)
      runLog { Stream.empty.merge(s1.get.covary[IO]) } shouldBe runLog(s1.get)
    }

    "merge/join consistency" in forAll { (s1: PureStream[Int], s2: PureStream[Int]) =>
      runLog { s1.get.covary[IO].merge(s2.get) }.toSet shouldBe
        runLog { Stream(s1.get.covary[IO], s2.get.covary[IO]).covary[IO].join(2) }.toSet
    }

    "join (1)" in forAll { (s1: PureStream[Int]) =>
      runLog { s1.get.covary[IO].map(Stream.emit(_).covary[IO]).join(1) }.toSet shouldBe runLog {
        s1.get
      }.toSet
    }

    "join (2)" in forAll { (s1: PureStream[Int], n: SmallPositive) =>
      runLog { s1.get.covary[IO].map(Stream.emit(_).covary[IO]).join(n.get) }.toSet shouldBe
        runLog { s1.get }.toSet
    }

    "join (3)" in forAll { (s1: PureStream[PureStream[Int]], n: SmallPositive) =>
      runLog { s1.get.map(_.get.covary[IO]).covary[IO].join(n.get) }.toSet shouldBe
        runLog { s1.get.flatMap(_.get) }.toSet
    }

    "join - resources acquired in outer stream are released after inner streams complete" in {
      val bracketed =
        Stream.bracket(IO(new java.util.concurrent.atomic.AtomicBoolean(true)))(b =>
          IO(b.set(false)))
      // Starts an inner stream which fails if the resource b is finalized
      val s: Stream[IO, Stream[IO, Unit]] = bracketed.map { b =>
        Stream
          .eval(IO(b.get))
          .flatMap(b => if (b) Stream(()) else Stream.raiseError(new Err))
          .repeat
          .take(10000)
      }
      s.joinUnbounded.compile.drain.unsafeRunSync()
    }

    "merge (left/right failure)" in {
      pending
      forAll { (s1: PureStream[Int], f: Failure) =>
        an[Err] should be thrownBy {
          s1.get.merge(f.get).compile.drain.unsafeRunSync()
        }
      }
    }

    "merge (left/right failure) never-ending flatMap, failure after emit" in {
      pending
      forAll { (s1: PureStream[Int], f: Failure) =>
        an[Err] should be thrownBy {
          s1.get
          // To ensure errors are generated before the merge occurs, we chunk/unchunk here to force any segment computations
          // This is necessary b/c this test depends on any errors in f occurring before the first flatMapped output of the
          // merged stream
            .merge(f.get.chunks.flatMap(Stream.chunk(_)))
            .flatMap { _ =>
              Stream.eval(IO.async[Unit](_ => ()))
            }
            .compile
            .drain
            .unsafeRunSync()
        }
      }
    }

    "merge (left/right failure) constant flatMap, failure after emit" in {
      pending
      forAll { (s1: PureStream[Int], f: Failure) =>
        an[Err] should be thrownBy {
          s1.get
          // To ensure errors are generated before the merge occurs, we chunk/unchunk here to force any segment computations
          // This is necessary b/c this test depends on any errors in f occurring before the first flatMapped output of the
          // merged stream
            .merge(f.get.chunks.flatMap(Stream.chunk(_)))
            .flatMap { _ =>
              Stream.constant(true)
            }
            .compile
            .drain
            .unsafeRunSync()
        }
      }
    }

    "hanging awaits" - {

      val full = Stream.constant(42).covary[IO]
      val hang = Stream.repeatEval(IO.async[Unit] { cb =>
        ()
      }) // never call `cb`!
      val hang2: Stream[IO, Nothing] = full.drain
      val hang3: Stream[IO, Nothing] =
        Stream
          .repeatEval[IO, Unit](IO.async[Unit] { cb =>
            cb(Right(()))
          } *> IO.shift)
          .drain

      "merge" in {
        runLog(full.merge(hang).take(1)) shouldBe Vector(42)
        runLog(full.merge(hang2).take(1)) shouldBe Vector(42)
        runLog(full.merge(hang3).take(1)) shouldBe Vector(42)
        runLog(hang.merge(full).take(1)) shouldBe Vector(42)
        runLog(hang2.merge(full).take(1)) shouldBe Vector(42)
        runLog(hang3.merge(full).take(1)) shouldBe Vector(42)
      }

      "join" in {
        runLog(Stream(full, hang).covary[IO].join(10).take(1)) shouldBe Vector(42)
        runLog(Stream(full, hang2).covary[IO].join(10).take(1)) shouldBe Vector(42)
        runLog(Stream(full, hang3).covary[IO].join(10).take(1)) shouldBe Vector(42)
        runLog(Stream(hang3, hang2, full).covary[IO].join(10).take(1)) shouldBe Vector(42)
      }
    }

    "join - outer-failed" in {
      an[Err] should be thrownBy {
        runLog(
          Stream(Stream.sleep_[IO](1 minute), Stream.raiseError(new Err).covary[IO])
            .covary[IO]
            .joinUnbounded)
      }
    }
  }
}
