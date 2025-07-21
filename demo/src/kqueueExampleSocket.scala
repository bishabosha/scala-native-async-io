package example

// import asyncio.scalanative.bsd.sys.event
// import asyncio.scalanative.bsd.sys.event.kevent.KEventOps
import asyncio.unsafe.Bracket
import asyncio.unsafe.KqueueLoop
import asyncio.unsafe.PosixSockets
import asyncio.unsafe.Sockets
import asyncio.unsafe.Sockets.Flavor
import asyncio.unsafe.Sockets.Transport

import java.io.IOException
import scala.scalanative.libc.errno
import scala.scalanative.libc.string
import scala.scalanative.posix.errno as perrno
import scala.scalanative.posix.fcntl
import scala.scalanative.posix.sys.socket
import scala.scalanative.posix.sys.un
import scala.scalanative.posix.sys.unOps.{*, given}
import scala.scalanative.posix.time.timespec
import scala.scalanative.posix.timeOps.*
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsafe.Ptr
import scala.scalanative.unsafe.UnsafeRichArray
import scala.scalanative.unsafe.Zone
import scala.scalanative.unsafe.fromCString
import scala.scalanative.unsafe.sizeOf
import scala.scalanative.unsafe.stackalloc
import scala.scalanative.unsafe.toCString
import scala.scalanative.unsigned.UInt
import scala.scalanative.unsigned.UnsignedRichInt
import asyncio.unsafe.PosixFileOps

object KQueueExampleSocket {
  def run(sock: String): Unit = {
    Bracket.fileResource(KqueueLoop.open())(KqueueLoop.close) { kq =>
      Bracket.fileResource(PosixSockets.open(Flavor.Unix, Transport.Stream))(PosixSockets.close) { clientFd =>
        println(s"opened file descriptor: $clientFd (socket-type)")
        PosixSockets.setNonBlocking(clientFd)
        PosixSockets.connectUnixAddr(clientFd, sock)
        println(s"connection in progress to path: $sock")
        KqueueLoop.createAndRegisterEvents(kq, 1) { events =>
          KqueueLoop.addFile(events, clientFd, read = false, clear = true) // add write event
        }
        println("Event registered for socket write and read.")
        object state {
          type EventState = Int
          val SendingMessage: EventState = 0
          val SendHeader: EventState = 1
          val AwaitResponse: EventState = 2
        }
        var eventState: state.EventState = state.SendHeader
        val message = "Hello from Scala Native KQueue Example!\n"

        def writeEvent(evt: KqueueLoop.Event): Boolean = {
          assert(KqueueLoop.fileIdent(evt) == clientFd, "Unexpected file descriptor for write event.")
          println(s">>> Socket $clientFd is ready for writing.")
          val available = KqueueLoop.rwAvailable(evt)
          println(s"Bytes available to write: $available")
          if (eventState == state.SendingMessage) {
            val bytes = message.getBytes()
            val bytesWritten = unistd.write(clientFd, bytes.at(0), bytes.length.toCSize)
            if (bytesWritten < 0) {
              throw new IOException(s"Failed to write to socket: ${fromCString(string.strerror(perrno.errno))}")
            }
            println(s"Wrote $bytesWritten bytes to socket.")
            eventState = state.AwaitResponse
            KqueueLoop.createAndRegisterEvents(kq, 2) { events =>
              KqueueLoop.deleteFile(events, clientFd, read = false) // remove write event
              KqueueLoop.addFile(events + 1, clientFd, read = true, clear = true) // add read event
            }
            true // Continue polling
          } else {
            assert(eventState == state.SendHeader, "Unexpected event state.")
            val size = message.length
            val buf = stackalloc[Byte](4) // 4 bytes for size header
            buf(0) = ((size & 0xFF000000) >> 24).toByte
            buf(1) = ((size & 0x00FF0000) >> 16).toByte
            buf(2) = ((size & 0x0000FF00) >> 8).toByte
            buf(3) = (size & 0x000000FF).toByte
            println("ENDING MESSAGE")
            val bytesWritten = unistd.write(clientFd, buf, 4.toCSize)
            if (bytesWritten < 0) {
              throw new IOException(s"Failed to write to socket: ${fromCString(string.strerror(perrno.errno))}")
            }
            println(s"Wrote $bytesWritten bytes to socket.")
            eventState = state.SendingMessage
            true // Continue polling
          }
        }

        def readEvent(evt: KqueueLoop.Event): Boolean = {
          assert(KqueueLoop.fileIdent(evt) == clientFd, "Unexpected file descriptor for read event.")
          println(s"<<< Socket $clientFd is ready for reading.")
          if (eventState == state.AwaitResponse) {
            val available = KqueueLoop.rwAvailable(evt)
            PosixFileOps.readBuffer(clientFd, available) { (buf, bytesRead) =>
              if (bytesRead == 0) {
                println("Connection closed by peer.")
              } else {
                println(s"Read $bytesRead bytes from socket.")
                // Process the received data
                val receivedMessage = fromCStringSlice(buf, bytesRead)
                println(s"Received message: `$receivedMessage`")
              }
            }
            false // don't continue polling, we are done reading
          } else {
            sys.error(s"Unexpected event state: $eventState")
          }
        }

        KqueueLoop.pollQueue(255) { polledEvents =>
          var doPoll = true
          while (doPoll) {
            println("starting to poll...")
            val events = KqueueLoop.pollEventsForever(kq, polledEvents, 255)
            if (events == 0) {
              println("No events polled, continuing...")
            } else {
              var i = 0
              while (i < events && doPoll) {
                val evt = polledEvents + i
                if (KqueueLoop.isError(evt)) {
                  println(s"Error event: ${KqueueLoop.debugEvent(evt)}")
                  doPoll = false // Exit on error
                } else if (KqueueLoop.isWriteEvent(evt)) {
                  doPoll &= writeEvent(evt)
                } else if (KqueueLoop.isReadEvent(evt)) {
                  doPoll &= readEvent(evt)
                } else {
                  sys.error(s"Unexpected event filter: ${KqueueLoop.debugEvent(evt)}")
                }
                i += 1
              }
            }
          }
        }
      }
    }
  }
}
