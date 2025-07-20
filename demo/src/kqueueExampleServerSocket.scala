package example

import asyncio.unsafe.KqueueLoop

import asyncio.scalanative.bsd.sys.event
import asyncio.scalanative.bsd.sys.event.kevent.KEventOps
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

object KQueueExampleServerSocket {
  def run(sock: String): Unit = {
    Bracket.fileResource(KqueueLoop.open())(KqueueLoop.close) { kq =>
      Bracket.fileResource(PosixSockets.open(Flavor.Unix, Transport.Stream, blocking = false))(PosixSockets.close) { fd =>
        println(s"opened file descriptor: $fd (socket-type)")
        PosixSockets.bindUnixAddr(fd, sock, removeExisting = true)
        println(s"bound socket to path: $sock")
        PosixSockets.listen(fd, PosixSockets.maxConnections)
        println("Socket is now listening for connections.")
        KqueueLoop.createAndRegisterEvents(kq, 1) {
          KqueueLoop.addFile(_, fd, read = true, clear = true)
        }
        println("Event registered for socket accept.")
        val polledEvents = stackalloc[event.kevent](255)
        var clientFds: Set[Int] = Set.empty[Int]
        var deregisterBuf: Set[Int] = Set.empty[Int]
        var byteArray = null.asInstanceOf[Array[Byte]]
        var offset = 0
        def reset() = {
          byteArray = new Array[Byte](1024)
          offset = 0
        }
        def dbl() = {
          offset = 0
          byteArray = new Array[Byte](byteArray.length * 2)
        }
        reset() // initialize the byte array
        object states {
          type EventState = Int
          val ReadClientMessage: EventState = 0
          val ReadClientHeader: EventState = 1
          val SendResponse: EventState = 2
          val SendResponseHeader: EventState = 3
        }
        var eventStates: Map[Int, states.EventState] = Map.empty[Int, states.EventState]
        var eventHeaders: Map[Int, states.EventState] = Map.empty[Int, Int]
        // var bufferedWrites: Map[Int, Int] = Map.empty[Int, Int]
        def closeClient(clientFd: Int): Unit = {
          val writeExpected = eventStates.get(clientFd).contains(states.SendResponse)
          clientFds -= clientFd
          eventStates -= clientFd
          eventHeaders -= clientFd
          KqueueLoop.createAndRegisterEvents(kq, if (writeExpected) 2 else 1) { clientEvents =>
            KqueueLoop.deleteFile(clientEvents, clientFd, read = true)
            if (writeExpected) {
              KqueueLoop.deleteFile(clientEvents + 1, clientFd, read = false)
            }
          }
          deregisterBuf += clientFd
          println(s"Deregistering client socket: $clientFd")
        }
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
              val sockFd = evt.ident.toInt
              if (sockFd == fd) {
                assert(evt.filter == event.EVFILT_READ)
                println("@@@ server socket ready for accept.")
                val clientFd = socket.accept(fd, null, null)
                if (clientFd < 0) {
                  throw new IOException(
                    s"Failed to accept connection: ${fromCString(string.strerror(errno.errno))}"
                  )
                }
                println(s"Accepted new client connection on fd: $clientFd")
                clientFds += clientFd
                eventStates += (clientFd -> states.ReadClientHeader) // Initialize the event state for the new client
                // Register the new client socket for read events
                KqueueLoop.createAndRegisterEvents(kq, 1) { evt =>
                  KqueueLoop.addFile(evt, clientFd, read = true, clear = true)
                }
                println(s"Registered client socket $clientFd for read and write events.")
              } else {
                val clientFd = sockFd
                assert(evt.filter == event.EVFILT_READ || evt.filter == event.EVFILT_WRITE)
                if (deregisterBuf.contains(clientFd)) {
                  println(s"skipping deregistered client socket $clientFd")
                } else {
                  assert(clientFds.contains(clientFd), s"Unexpected client socket $clientFd")
                  val isErr = (evt.flags & event.EV_ERROR) != 0
                  if (isErr) {
                    println(s"Error on client socket $clientFd: ${fromCString(string.strerror(evt.data.toInt))}")
                    closeClient(clientFd)
                  } else if (evt.filter == event.EVFILT_READ) {
                    println(s"<<< client socket $clientFd ready for read.")
                    val available = evt.data.toInt
                    var continue = true
                    while (continue) {
                      val bytesRead = unistd.read(
                        clientFd,
                        byteArray.at(offset),
                        (1024 `min` (byteArray.length - offset)).toCSize
                      )

                      def processEnd(): Unit = {
                        val state = eventStates(clientFd)
                        if (state == states.ReadClientHeader) {
                          if (offset != 4) {
                            println(s"Unexpected EOF while reading header from client socket $clientFd. Expected 4 bytes, got $offset bytes.")
                            closeClient(clientFd)
                          } else {
                            println(s"Read header of size 4 bytes from client socket $clientFd.")
                            val size = byteArray.take(4).foldLeft(0)((acc, b) => (acc << 8) | (b & 0xFF))
                            println(s"message size: $size bytes")
                            eventHeaders += (clientFd -> size)
                            println("Now waiting for message body...")
                            eventStates += (clientFd -> states.ReadClientMessage)
                          }
                        } else if (state == states.ReadClientMessage) {
                          val expected = eventHeaders(clientFd)
                          assert(offset == expected, "Unexpected EOF while reading message. [TODO: handle larger messages]")
                          println(s"Read message of size $offset bytes from client socket $clientFd.")
                          println(s"message contents: `${new String(byteArray.take(offset))}`")
                          eventStates -= clientFd
                          eventHeaders -= clientFd
                          eventStates += (clientFd -> states.SendResponse) // Prepare to send response
                          // Register the client socket for write events
                          KqueueLoop.createAndRegisterEvents(kq, 1) { evt =>
                            KqueueLoop.addFile(evt, clientFd, read = false, clear = false)
                          }
                        }
                        reset() // Reset the byte array for the next read
                      }

                      if (bytesRead < 0) {
                        if (errno.errno != errno.EAGAIN && errno.errno != errno.EWOULDBLOCK) {
                          throw new IOException(
                            s"Failed to read from client socket: ${fromCString(string.strerror(errno.errno))}"
                          )
                        } else {
                          println("No more data available to read (EAGAIN or EWOULDBLOCK).")
                          processEnd()
                          continue = false // Exit the loop if no more data is available
                        }
                      } else if (bytesRead == 0) {
                        println(s"End of file reached on client socket $clientFd. Contents are $offset long.")
                        processEnd()
                        continue = false // Exit the loop if no more data is available
                        // deregisterBuf += clientFd
                        // println(s"will deregister client socket $clientFd after polling due to EOF.")
                        // for now, accept more connections, else // doPoll = false
                      } else {
                        println(s"Actually read $bytesRead bytes from client socket $clientFd")
                        offset += bytesRead.toInt
                        if (offset >= byteArray.length) {
                          dbl()
                        }
                      }
                    }
                  } else {
                    assert(evt.filter == event.EVFILT_WRITE, s"Unexpected filter: ${evt.filter}")
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
                    } else {
                      println(s"Socket $clientFd is not in SendResponse state, skipping write.")
                    }
                  }
                }
              }
              i += 1
            }
          }
          if (deregisterBuf.nonEmpty) {
            for (clientFd <- deregisterBuf) {
              println("Closing deregistered client socket: " + clientFd)
              if (unistd.close(clientFd) < 0) {
                throw new IOException(
                  s"Failed to close client socket $clientFd: ${fromCString(string.strerror(errno.errno))}"
                )
              }
            }
            deregisterBuf = Set.empty[Int]
          }
        }
      }
    }
  }
}
