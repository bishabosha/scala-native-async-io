package example

import asyncio.scalanative.bsd.sys.event
import asyncio.scalanative.bsd.sys.event.kevent.KEventOps
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

object KQueueExampleSocket {
  def run(sock: String): Unit = {
    Bracket.fileResource(KqueueLoop.open())(KqueueLoop.close) { kq =>
      Bracket.fileResource(PosixSockets.open(Flavor.Unix, Transport.Stream, blocking = false))(PosixSockets.close) { clientFd =>
        println(s"opened file descriptor: $clientFd (socket-type)")
        PosixSockets.connectUnixAddr(clientFd, sock)
        println(s"connection in progress to path: $sock")
        KqueueLoop.createAndRegisterEvents(kq, 2) { events =>
          KqueueLoop.addFile(events, clientFd, read = true, clear = true)
          KqueueLoop.addFile(events + 1, clientFd, read = false, clear = true)
        }
        println("Event registered for socket write and read.")
        val polledEvents = stackalloc[event.kevent](255)
        var doPoll = true
        object state {
          type EventState = Int
          val SendingMessage: EventState = 0
          val SendHeader: EventState = 1
          val AwaitResponse: EventState = 2
        }
        var eventState: state.EventState = state.SendHeader
        val message = "Hello from Scala Native KQueue Example!\n"
        while (doPoll) {
          println("starting to poll...")
          val events = KqueueLoop.pollEventsForever(kq, polledEvents, 255)
          if (events == 0) {
            println("No events polled, continuing...")
          } else {
            var i = 0
            while (i < events && doPoll) {
              val evt = polledEvents + i
              if (evt.filter == event.EVFILT_WRITE) {
                assert(evt.ident.toInt == clientFd, "Unexpected file descriptor for write event.")
                println(s">>> Socket $clientFd is ready for writing.")
                val available = evt.data.toInt
                println(s"Bytes available to write: $available")
                if (eventState == state.AwaitResponse) {
                  println("Already waiting for a response, skipping write event.")
                } else if (eventState == state.SendingMessage) {
                  val bytes = message.getBytes()
                  val bytesWritten = unistd.write(clientFd, bytes.at(0), bytes.length.toCSize)
                  if (bytesWritten < 0) {
                    throw new IOException(s"Failed to write to socket: ${fromCString(string.strerror(perrno.errno))}")
                  }
                  println(s"Wrote $bytesWritten bytes to socket.")
                  eventState = state.AwaitResponse
                  // doPoll = false // Exit after writing
                } else {
                  assert(eventState == state.SendHeader, "Unexpected event state.")
                  val size = message.length
                  val buf = new Array[Byte](4)
                  buf(0) = ((size & 0xFF000000) >> 24).toByte
                  buf(1) = ((size & 0x00FF0000) >> 16).toByte
                  buf(2) = ((size & 0x0000FF00) >> 8).toByte
                  buf(3) = (size & 0x000000FF).toByte
                  println("ENDING MESSAGE")
                  val bytesWritten = unistd.write(clientFd, buf.at(0), buf.length.toCSize)
                  if (bytesWritten < 0) {
                    throw new IOException(s"Failed to write to socket: ${fromCString(string.strerror(perrno.errno))}")
                  }
                  println(s"Wrote $bytesWritten bytes to socket.")
                  eventState = state.SendingMessage
                }
              } else if (evt.filter == event.EVFILT_READ) {
                assert(evt.ident.toInt == clientFd, "Unexpected file descriptor for read event.")
                println(s"<<< Socket $clientFd is ready for reading.")
                if (eventState == state.AwaitResponse) {
                  val byteArray = new Array[Byte](1024)
                  val bytesRead = unistd.read(
                    clientFd,
                    byteArray.at(0),
                    (1024 `min` (byteArray.length - 0)).toCSize
                  )
                  if (bytesRead < 0) {
                    throw new IOException(s"Failed to read from socket: ${fromCString(string.strerror(perrno.errno))}")
                  } else if (bytesRead == 0) {
                    println("Connection closed by peer.")
                    doPoll = false // Exit the loop if the connection is closed
                  } else {
                    println(s"Read $bytesRead bytes from socket.")
                    // Process the received data
                    val receivedMessage = new String(byteArray, 0, bytesRead)
                    println(s"Received message: $receivedMessage")
                    doPoll = false // Exit after reading
                  }
                } else {
                  sys.error(s"Unexpected event state: $eventState")
                }
                // Handle reading from the socket
              } else {
                sys.error(s"Unexpected event filter: ${evt.filter}")
              }
              i += 1
            }
          }
        }
      }
    }
  }
}
