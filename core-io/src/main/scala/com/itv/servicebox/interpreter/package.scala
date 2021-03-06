package com.itv.servicebox

import java.util.concurrent.{TimeUnit, TimeoutException}

import cats.effect.{IO, Timer}
import cats.syntax.flatMap._
import com.itv.servicebox.algebra._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

package object interpreter {
  implicit val ioEffect: ImpureEffect[IO] = new ImpureEffect[IO]() {
    override def lift[A](a: => A): IO[A]      = IO(a)
    override def runSync[A](effect: IO[A]): A = effect.unsafeRunSync()
  }

  implicit def ioScheduler(implicit logger: Logger[IO]): Scheduler[IO] = new Scheduler[IO](logger) {
    override def retry[A](f: () => IO[A], interval: FiniteDuration, timeout: FiniteDuration, label: String)(
        implicit ec: ExecutionContext): IO[A] = {

      val timer                        = implicitly[Timer[IO]]
      def getRealTime                  = timer.clockRealTime(TimeUnit.MILLISECONDS)
      def elapsedTime(startTime: Long) = getRealTime.map(_ - startTime)

      def attemptAction(timeTaken: Long): IO[A] =
        for {
          _ <- if (timeTaken > timeout.toMillis)
            IO.raiseError(new TimeoutException(s"Ready check timed out for $label after $timeout"))
          else IO.unit
          currentTime <- getRealTime
          _           <- logger.debug(s"running ready-check for $label ...")
          reAttempt = elapsedTime(currentTime) flatMap (interval => attemptAction(timeTaken + interval))
          result <- IO.race(f().attempt, timer.sleep(interval))
          outcome <- result.fold(
            _.fold(err => logger.warn(s"Ready check failed for $label: $err...") >> reAttempt, IO.pure),
            _ => logger.debug(s"interval timed out after after $interval") >> reAttempt
          )
        } yield outcome

      attemptAction(0L)
    }
  }
}
