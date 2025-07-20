package example

import asyncio.scalanative.bsd.sys.event
import asyncio.scalanative.bsd.sys.event.kevent.KEventOps
import scala.scalanative.libc.errno
import scala.scalanative.posix.{errno => perrno}
import scala.scalanative.libc.string
import scala.scalanative.unsafe.fromCString
import scala.scalanative.unsafe.UnsafeRichArray
import scala.scalanative.unsafe.toCString
import scala.scalanative.unsafe.stackalloc
import scala.scalanative.unsigned.UnsignedRichInt
import scala.scalanative.posix.unistd
import scala.scalanative.posix.time.timespec
import scala.scalanative.posix.timeOps._
import scala.scalanative.posix.fcntl
import scala.scalanative.unsafe.Zone
import java.io.IOException
import asyncio.unsafe.KqueueLoop
import asyncio.unsafe.Bracket

/** actually useless for "real" files, it always blocks, so use with a FIFO for
  * example
  */
object KQueueExampleFileWrite {
  def run(fifo: String, msg0: String): Unit = {
    Bracket.fileResource(KqueueLoop.open())(KqueueLoop.close) { kq =>
      val fd = Zone.acquire { implicit z =>
        println(s"attempt to open file ${fifo}")
        val path = toCString(fifo)
        fcntl.open(path, fcntl.O_WRONLY | fcntl.O_NONBLOCK)
      }
      if (fd < 0) {
        throw new IOException(
          s"Failed to open file: ${fromCString(string.strerror(errno.errno))}"
        )
      }
      println(s"opened file descriptor: $fd (file: ${fifo})")
      val changeEvent = stackalloc[event.kevent]()
      event.EV_SET(
        changeEvent,
        fd.toUSize, // file descriptor
        event.EVFILT_WRITE, // filter type
        (event.EV_ADD | event.EV_ENABLE | event.EV_CLEAR).toUShort, // flags
        0.toUInt, // fflags
        0, // timeout in seconds
        null // data
      )
      if (event.kevent(kq, changeEvent, 1, null, 0, null) < 0) {
        throw new IOException(
          s"Failed to register event: ${fromCString(string.strerror(errno.errno))}"
        )
      } else {
        println("Event registered for file read.")
      }
      // val timeout = stackalloc[timespec]()
      // timeout.tv_sec = 1 // seconds
      // timeout.tv_nsec = 0 // nanoseconds
      val polledEvents = stackalloc[event.kevent]()
      try {
        while (true) {
          // Wait for the event to be triggered
          println(
            s"starting to poll..."
          )
          val nev =
            event.kevent(kq, null, 0, polledEvents, 1, null) // infinite wait
          if (nev < 0) {
            throw new IOException(
              s"Failed to wait for event: ${fromCString(string.strerror(errno.errno))}"
            )
          } else if (nev == 0) {
            println("No events triggered within the timeout period.")
          } else {
            println(
              s"Event triggered: ID = ${polledEvents.ident}, Filter = ${polledEvents.filter}, Data = ${polledEvents.data}"
            )
            assert(
              polledEvents.filter == event.EVFILT_WRITE && polledEvents.ident == fd.toUSize
            )
            val available = polledEvents.data.toInt
            println(s"Bytes available to write: $available")
            polledEvents(0) = null // Reset the event
            val msg = msg0.getBytes()
            assert(
              msg.length <= available,
              s"Message length (${msg.length}) exceeds available bytes ($available)"
            )
            var continue = true
            while (continue) {
              // read all data until EOF or EAGAIN
              val buf = msg.at(0)
              var bytesWritten = unistd.write(
                fd,
                buf,
                msg.length.toCSize
              )
              if (bytesWritten < 0) {
                if (
                  errno.errno != perrno.EAGAIN && errno.errno != perrno.EWOULDBLOCK
                ) {
                  throw new IOException(
                    s"Failed to write file: ${fromCString(string.strerror(errno.errno))}"
                  )
                } else {
                  println(
                    "No data available to write (EAGAIN or EWOULDBLOCK)."
                  )
                  continue = false // Exit the loop if no more data is available
                }
              } else {
                println(s"actually wrote $bytesWritten bytes")
                continue = false // Exit after writing the message
              }
            }
          }
        }
      } finally {
        val st = unistd.close(fd)
        if (st < 0) {
          throw new IOException(
            s"Failed to close file descriptor: ${fromCString(string.strerror(errno.errno))}"
          )
        }
      }
    }
  }
}
