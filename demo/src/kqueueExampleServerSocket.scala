package example

import asyncio.unsafe.KqueueLoop

import scala.scalanative.posix.errno
import scala.scalanative.libc.string
import scala.scalanative.unsafe.fromCString
import scala.scalanative.unsafe.UnsafeRichArray
import scala.scalanative.unsafe.toCString
import scala.scalanative.unsafe.stackalloc
import scala.scalanative.unsafe.sizeOf
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.UnsignedRichInt
import scala.scalanative.posix.unistd
import scala.scalanative.posix.time.timespec
import scala.scalanative.posix.timeOps._
import scala.scalanative.posix.fcntl
import scala.scalanative.posix.sys.socket
import scala.scalanative.posix.sys.un
import scala.scalanative.posix.sys.unOps.{*, given}
import scala.scalanative.unsafe.Zone
import java.io.IOException
import scala.scalanative.unsafe.Ptr
import asyncio.unsafe.Bracket
import asyncio.unsafe.PosixSockets
import asyncio.unsafe.Sockets.Flavor
import asyncio.unsafe.Sockets
import asyncio.unsafe.Sockets.Transport
import asyncio.unsafe.PosixFileOps
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object KQueueExampleServerSocket {
  def run(sock: String): Unit = {
    Bracket.fileResource(KqueueLoop.open())(KqueueLoop.close) { kq =>
      Bracket.fileResource(PosixSockets.open(Flavor.Unix, Transport.Stream))(PosixSockets.close) { fd =>
        println(s"opened file descriptor: $fd (socket-type)")
        PosixSockets.setNonBlocking(fd)
        PosixSockets.bindUnixAddr(fd, sock, removeExisting = true)
        println(s"bound socket to path: $sock")
        PosixSockets.listen(fd, PosixSockets.maxConnections)
        println("Socket is now listening for connections.")
        KqueueLoop.createAndRegisterEvents(kq, 1) {
          KqueueLoop.addFile(_, fd, read = true, clear = true) // server-socket read event
        }
        println("Event registered for socket accept.")
        var deregisterBuf: Set[Int] = Set.empty[Int]
        object states {
          type EventState = Int
          val ReadClientMessage: EventState = 0
          val ReadClientHeader: EventState = 1
          val SendResponse: EventState = 2
          val SendResponseHeader: EventState = 3
        }
        var eventStates: Map[Int, states.EventState] = Map.empty[Int, states.EventState]
        var eventHeaders: Map[Int, states.EventState] = Map.empty[Int, Int]

        def closeClient(clientFd: Int): Unit = {
          val writeExpected = eventStates.get(clientFd).contains(states.SendResponse)
          eventStates -= clientFd
          eventHeaders -= clientFd
          KqueueLoop.createAndRegisterEvents(kq, 1) {
            KqueueLoop.deleteFile(_, clientFd, read = !writeExpected)
          }
          println(s"removed ${if (writeExpected) "write" else "read"} event for client socket: $clientFd")
          println(s"Deregistering client socket: $clientFd")
          deregisterBuf += clientFd
        }

        def acceptConnection(evt: KqueueLoop.Event): Boolean = {
          println("@@@ server socket ready for accept.")
          val clientFd = PosixSockets.acceptUnix(fd)
          println(s"Accepted new client connection on fd: $clientFd")
          eventStates += (clientFd -> states.ReadClientHeader)
          KqueueLoop.createAndRegisterEvents(kq, 1) { evt =>
            // Register the new client socket for read events
            KqueueLoop.addFile(evt, clientFd, read = true, clear = true)
          }
          println(s"Registered client socket $clientFd for read events.")
          true // Continue polling
        }

        def clientRead(evt: KqueueLoop.Event, clientFd: Int): Boolean = {
          def processEnd(buf: Ptr[Byte], read: CSize): Unit = {
            val state = eventStates(clientFd)
            if (state == states.ReadClientHeader) {
              if (read != 4) {
                println(s"Unexpected EOF while reading header from client socket $clientFd. Expected 4 bytes, got $read bytes.")
                closeClient(clientFd)
              } else {
                println(s"Read header of size 4 bytes from client socket $clientFd.")
                val b0 = buf(0) & 0xFF
                val b1 = buf(1) & 0xFF
                val b2 = buf(2) & 0xFF
                val b3 = buf(3) & 0xFF
                val size = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
                println(s"message size: $size bytes")
                eventHeaders += (clientFd -> size)
                println("Now waiting for message body...")
                eventStates += (clientFd -> states.ReadClientMessage)
              }
            } else if (state == states.ReadClientMessage) {
              val expected = eventHeaders(clientFd)
              assert(read == expected, "Unexpected EOF while reading message. [TODO: handle larger messages]")
              println(s"Read message of size $expected bytes from client socket $clientFd.")
              println(s"message contents: `${fromCStringSlice(buf, read)}`")
              eventStates -= clientFd
              eventHeaders -= clientFd
              eventStates += (clientFd -> states.SendResponse) // Prepare to send response
              // Register the client socket for write events
              KqueueLoop.createAndRegisterEvents(kq, 2) { evts =>
                KqueueLoop.deleteFile(evts, clientFd, read = true) // remove read event
                KqueueLoop.addFile(evts + 1, clientFd, read = false, clear = false) // add write event
              }
              println(s"Registered client socket $clientFd for write events (dropped read).")
            }
          }
          if (deregisterBuf.contains(clientFd)) {
            println(s"skipping deregistered client socket $clientFd")
            true // Continue polling
          } else {
            assert(eventStates.contains(clientFd), s"Unexpected client socket $clientFd")
            val available = KqueueLoop.rwAvailable(evt)
            println(s"<<< client socket $clientFd ready for read ($available bytes available).")
            PosixFileOps.readBuffer(clientFd, available)(processEnd)
            true // Continue polling
          }
        }

        def readEvent(evt: KqueueLoop.Event): Boolean = {
          val sockFd = KqueueLoop.fileIdent(evt)
          if (sockFd == fd) {
            acceptConnection(evt) // Handle server socket read events
          } else {
            clientRead(evt, clientFd = sockFd) // Handle client socket read events
          }
        }

        def writeEvent(evt: KqueueLoop.Event): Boolean = {
          val clientFd = KqueueLoop.fileIdent(evt)
          assert(clientFd != fd, s"Unexpected file descriptor for write event: $clientFd")
          println(s">>> client socket $clientFd ready for write.")
          if (eventStates.get(clientFd).contains(states.SendResponse)) {
            println(s"Socket $clientFd is in SendResponse state, writing response.")
            val bytes = "Just pinging back!\n".getBytes()
            val bytesWritten = unistd.write(clientFd, bytes.at(0), bytes.length.toCSize)
            if (bytesWritten < 0) {
              throw new IOException(s"Failed to write to socket: ${fromCString(string.strerror(errno.errno))}")
            }
            println(s"Wrote $bytesWritten bytes to socket $clientFd.")
            closeClient(clientFd) // Close the client socket after sending the response
            true // Continue polling
          } else {
            println(s"Socket $clientFd is not in SendResponse state, skipping write.")
            true // Continue polling
          }
        }

        KqueueLoop.pollQueue(255) { polledEvents =>
          var doPoll = true
          while (doPoll) {
            println(s"starting to poll...")
            val events = KqueueLoop.pollEventsForever(kq, polledEvents, 255)
            if (events == 0) {
              println("No events triggered within the timeout period.")
            } else {
              var i = 0
              while (doPoll && i < events) {
                val evt = polledEvents + i
                if (KqueueLoop.isError(evt)) {
                  Console.err.println(s"Error event: ${fromCString(string.strerror(KqueueLoop.errno(evt)))}")
                  doPoll = false // Exit on error
                } else if (KqueueLoop.isReadEvent(evt)) {
                  doPoll &= readEvent(evt)
                } else if (KqueueLoop.isWriteEvent(evt)) {
                  doPoll &= writeEvent(evt)
                } else {
                  Console.err.println(s"Unexpected event filter: ${KqueueLoop.debugEvent(evt)}")
                  doPoll = false // Exit on unexpected event
                }
                i += 1
              }
            }
            if (deregisterBuf.nonEmpty) {
              for (clientFd <- deregisterBuf) {
                println("Closing deregistered client socket: " + clientFd)
                PosixSockets.close(clientFd)
              }
              deregisterBuf = Set.empty[Int]
            }
          }
        }
      }
    }
  }
}
