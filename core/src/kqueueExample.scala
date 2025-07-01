package example

import asyncio.scalanative.bsd.sys.event
import asyncio.scalanative.bsd.sys.event.kevent.KEventOps
import scala.scalanative.libc.errno
import scala.scalanative.libc.string
import scala.scalanative.unsafe.fromCString
import scala.scalanative.unsafe.stackalloc
import scala.scalanative.unsigned.UnsignedRichInt
import scala.scalanative.posix.unistd
import scala.scalanative.posix.time.timespec
import scala.scalanative.posix.timeOps._

object Foo {
  def main(args: Array[String]): Unit = {
    val kq = event.kqueue()
    try {
      if (kq < 0) {
        throw new RuntimeException(
          s"Failed to create kqueue: ${fromCString(string.strerror(errno.errno))}"
        )
      }
      val tevent = stackalloc[event.kevent]()
      event.EV_SET(
        tevent,
        1.toUSize, // timer ID
        event.EVFILT_TIMER, // filter type
        (event.EV_ADD | event.EV_ONESHOT).toUShort, // flags
        event.NOTE_SECONDS, // fflags
        5, // timeout in seconds
        null // data
      )

      if (event.kevent(kq, tevent, 1, null, 0, null) < 0) {
        throw new RuntimeException(
          s"Failed to register event: ${fromCString(string.strerror(errno.errno))}"
        )
      }

      println("Event registered successfully. Waiting for it to trigger...")
      val events = stackalloc[event.kevent](1)
      val timeout = stackalloc[timespec]()
      timeout.tv_sec = 1 // 1 seconds
      timeout.tv_nsec = 0 // 0 milliseconds
      println("calling kevent to wait for events...")
      var seconds = 0
      while (true) {
        val nev = event.kevent(kq, null, 0, events, 1, timeout)
        seconds += 1
        println(s"waited for $seconds seconds")
        if (nev < 0) {
          throw new RuntimeException(
            s"Failed to wait for event: ${fromCString(string.strerror(errno.errno))}"
          )
        } else if (nev == 0) {
          println("No events triggered within the timeout period.")
        } else {
          println(
            s"Event triggered: ID = ${events.ident}, Filter = ${events.filter}, Data = ${events.data}"
          )
          return () // Exit after handling the event
        }
      }
    } finally {
      unistd.close(kq)
    }
  }
}
