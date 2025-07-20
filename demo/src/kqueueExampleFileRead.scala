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
object KQueueExampleFileRead {
  def run(fifo: String): Unit = {
    Bracket.fileResource(KqueueLoop.open())(KqueueLoop.close) { kq =>
      val fd = Zone.acquire { implicit z =>
        println(s"attempt to open file ${fifo}")
        val path = toCString(fifo)
        fcntl.open(path, fcntl.O_RDONLY | fcntl.O_NONBLOCK)
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
        event.EVFILT_READ, // filter type
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
      var byteArray = null.asInstanceOf[Array[Byte]]
      def reset() = {
        byteArray = new Array[Byte](1024)
      }
      var offset = 0
      def dbl() = {
        offset = 0
        byteArray = new Array[Byte](byteArray.length * 2)
      }
      // val timeout = stackalloc[timespec]()
      // timeout.tv_sec = 1 // seconds
      // timeout.tv_nsec = 0 // nanoseconds
      val polledEvents = stackalloc[event.kevent]()
      reset() // initialize the byte array
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
              polledEvents.filter == event.EVFILT_READ && polledEvents.ident == fd.toUSize
            )
            val available = polledEvents.data.toInt
            val atEOF = (polledEvents.flags & event.EV_EOF) != 0
            println(s"Bytes available to read: $available (atEOF: $atEOF)")

            var continue = true
            while (continue) {
              // read all data until EOF or EAGAIN
              var bytesRead = unistd.read(
                fd,
                byteArray.at(offset),
                (1024 `min` (byteArray.length - offset)).toCSize
              )
              if (bytesRead < 0) {
                if (
                  errno.errno != perrno.EAGAIN && errno.errno != perrno.EWOULDBLOCK
                ) {
                  throw new IOException(
                    s"Failed to read file: ${fromCString(string.strerror(errno.errno))}"
                  )
                } else {
                  println(
                    "No more data available to read (EAGAIN or EWOULDBLOCK)."
                  )
                  continue = false // Exit the loop if no more data is available
                }
              } else if (bytesRead == 0) {
                println(s"End of file reached. Contents are $offset long.")
                println(
                  s"File contents: `${new String(byteArray.take(offset))}`"
                )
                reset() // Reset the byte array for the next read
                continue = false // Exit the loop if no more data is available
              } else {
                println(s"actually read $bytesRead bytes")
                offset += bytesRead.toInt
                if (offset >= byteArray.length) {
                  dbl()
                }
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
