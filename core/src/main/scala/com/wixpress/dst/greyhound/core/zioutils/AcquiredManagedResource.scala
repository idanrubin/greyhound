package com.wixpress.dst.greyhound.core.zioutils

import ZManagedSyntax._
import zio._
import zio.clock.Clock
import zio.duration.{Duration, durationInt}

import scala.concurrent.TimeoutException

case class AcquiredManagedResource[T](resource: T, onRelease: UIO[Unit], runtime: zio.Runtime[Any]) {
  def release(): Unit = runtime.unsafeRunTask(onRelease)
}

object AcquiredManagedResource {
  def acquire[R <: Has[_] : zio.Tag, T](resources: ZManaged[R, Throwable, T],
                                        releaseTimeout: Duration = 10.seconds): ZIO[Clock with R, Throwable, AcquiredManagedResource[T]] = for {
    runtime <- ZIO.runtime[Any]
    clock <- ZIO.environment[Clock]
    r <- resources.reserve
  } yield {
    val releaseWithTimeout = r.release(Exit.unit)
      .timeoutFail(new TimeoutException("release timed out"))(releaseTimeout)
      .provide(clock)
      .orDie.unit
    AcquiredManagedResource(r.acquired, releaseWithTimeout, runtime)
  }}
