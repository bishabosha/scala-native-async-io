package example

import asyncio.unsafe.KqueueLoop
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
import asyncio.unsafe.KqueueLoop.pollEventsTimeout
import asyncio.unsafe.Bracket

object KQueueExampleTimer {
  def run(): Unit = {
    Bracket.fileResource(KqueueLoop.open())(KqueueLoop.close) { kq =>
      KqueueLoop.createAndRegisterEvents(kq, 1) { events =>
        println("Creating and registering timer event...")
        KqueueLoop.addTimerOneShot(
          events,
          1.toUSize, // timer ID
          event.NOTE_SECONDS, // flags
          5 // timeout in seconds
        )
      }
      println("Event registered successfully. Waiting for it to trigger...")
      println("calling kevent to wait for events...")
      var seconds = 0
      var doLoop = true
      val events = stackalloc[event.kevent]()
      while (doLoop) {
        val nev = pollEventsTimeout(kq, events, nEvents = 1, seconds = 1, 0)
        seconds += 1
        println(s"waited for $seconds seconds")
        if (nev == 0) {
          println("No events triggered within the timeout period.")
        } else {
          println(
            s"Event triggered: ID = ${events.ident}, Filter = ${events.filter}, Data = ${events.data}"
          )
          doLoop = false // Exit after handling the event
        }
      }
    }
  }
}
