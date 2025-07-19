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

object KQueueExampleServerSocket {
  def run(sock: String): Unit = {
    val kq = event.kqueue()
    if (kq < 0) {
      throw new IOException(
        s"Failed to create kqueue: ${fromCString(string.strerror(errno.errno))}"
      )
    }
    try {
      val fd = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM, 0)
      if (fd < 0) {
        throw new IOException(
          s"Failed to open file: ${fromCString(string.strerror(errno.errno))}"
        )
      }
      try {
        val err = fcntl.fcntl(fd, fcntl.F_SETFL, fcntl.O_NONBLOCK)
        if (err < 0) {
          throw new IOException(
            s"Failed to set socket descriptor to non-blocking: ${fromCString(string.strerror(errno.errno))}"
          )
        }
        println(s"opened file descriptor: $fd (socket-type)")
        val maxPathLength = sizeOf[CArray[CChar, un._108]] - 1
        if (sock.length > maxPathLength) {
          throw new IOException(
            s"Socket path exceeds maximum length of $maxPathLength characters: $sock"
          )
        }
        Zone.acquire { implicit z =>
          val path = toCString(sock)
          val addr = z.alloc(sizeOf[un.sockaddr_un]).asInstanceOf[Ptr[un.sockaddr_un]]
          addr.sun_family = socket.AF_UNIX.toUShort
          val rmErr = unistd.unlink(path) // remove the socket file if it already exists
          if (rmErr < 0 && errno.errno != perrno.ENOENT) {
            throw new IOException(
              s"Failed to unlink existing socket at $sock: ${fromCString(string.strerror(errno.errno))}"
            )
          }
          string.memcpy(addr.sun_path.at(0), path, sock.length.toCSize)
          addr.sun_path(sock.length) = 0.toByte // null-terminate the path
          val err0 = socket.bind(fd, addr.asInstanceOf[Ptr[socket.sockaddr]], sizeOf[un.sockaddr_un].toUInt)
          if (err0 < 0) {
            throw new IOException(
              s"Failed to bind socket: ${fromCString(string.strerror(errno.errno))}"
            )
          }
        }
        // sys.addShutdownHook {
        //   // SIGINT bypasses finally blocks, so all cleanup must be done here
        //   val res = Zone.acquire { implicit z =>
        //     val path = toCString(sock)
        //     unistd.unlink(path)
        //   }
        //   if (res < 0) {
        //     throw new IOException(
        //       s"Failed to unlink socket: ${fromCString(string.strerror(errno.errno))}"
        //     )
        //   }
        // }
        println(s"bound socket to path: $sock")
        val listenRes = socket.listen(fd, socket.SOMAXCONN) // Listen for incoming connections
        if (listenRes < 0) {
          throw new IOException(
            s"Failed to listen on socket: ${fromCString(string.strerror(errno.errno))}"
          )
        }
        println("Socket is now listening for connections.")
        // set up kqueue for the socket
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
          println("Event registered for socket accept.")
        }
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
          Zone.acquire { implicit z =>
            val len = if (writeExpected) 2 else 1
            val clientEvents = z.alloc(sizeOf[event.kevent] * len).asInstanceOf[Ptr[event.kevent]]
            event.EV_SET(
              clientEvents,
              clientFd.toUSize, // file descriptor
              event.EVFILT_READ, // filter type
              event.EV_DELETE.toUShort, // flags
              0.toUInt, // fflags
              0, // data
              null // udata
            )
            if (writeExpected) {
              // If we expect a write event, also remove it
              event.EV_SET(
                clientEvents + 1,
                clientFd.toUSize, // file descriptor
                event.EVFILT_WRITE, // filter type
                event.EV_DELETE.toUShort, // flags
                0.toUInt, // fflags
                0, // data
                null // udata
              )
            }
            if (event.kevent(kq, clientEvents, len, null, 0, null) < 0) {
              throw new IOException(
                s"Failed to clear client events: ${fromCString(string.strerror(errno.errno))}"
              )
            }
          }
          deregisterBuf += clientFd
          println(s"Deregistering client socket: $clientFd")
        }
        var doPoll = true
        while (doPoll) {
          println(s"starting to poll...")
          val events = event.kevent(kq, null, 0, polledEvents, 255, null) // infinite wait
          if (events < 0) {
            throw new IOException(
              s"Failed to wait for event: ${fromCString(string.strerror(errno.errno))}"
            )
          } else if (events == 0) {
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
                Zone.acquire { implicit z =>
                  val clientEvents = z.alloc(sizeOf[event.kevent]).asInstanceOf[Ptr[event.kevent]]
                  event.EV_SET(
                    clientEvents,
                    clientFd.toUSize, // file descriptor
                    event.EVFILT_READ, // filter type
                    (event.EV_ADD | event.EV_ENABLE | event.EV_CLEAR).toUShort, // flags
                    0.toUInt, // fflags
                    0, // data
                    null // udata
                  )
                  if (event.kevent(kq, clientEvents, 1, null, 0, null) < 0) {
                    throw new IOException(
                      s"Failed to register client read event: ${fromCString(string.strerror(errno.errno))}"
                    )
                  }
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
                          Zone.acquire { implicit z =>
                            val clientEvents = z.alloc(sizeOf[event.kevent]).asInstanceOf[Ptr[event.kevent]]
                            event.EV_SET(
                              clientEvents,
                              clientFd.toUSize, // file descriptor
                              event.EVFILT_WRITE, // filter type
                              (event.EV_ADD | event.EV_ENABLE).toUShort, // flags
                              0.toUInt, // fflags
                              0, // data
                              null // udata
                            )
                            if (event.kevent(kq, clientEvents, 1, null, 0, null) < 0) {
                              throw new IOException(
                                s"Failed to register client write event: ${fromCString(string.strerror(errno.errno))}"
                              )
                            }
                          }
                        }
                        reset() // Reset the byte array for the next read
                      }

                      if (bytesRead < 0) {
                        if (errno.errno != perrno.EAGAIN && errno.errno != perrno.EWOULDBLOCK) {
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
                        throw new IOException(s"Failed to write to socket: ${fromCString(string.strerror(perrno.errno))}")
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
      } finally {
        val st = unistd.close(fd)
        if (st < 0) {
          throw new IOException(
            s"Failed to close file descriptor: ${fromCString(string.strerror(errno.errno))}"
          )
        }
      }
    } finally {
      val st = unistd.close(kq)
      if (st < 0) {
        throw new IOException(
          s"Failed to close kqueue: ${fromCString(string.strerror(errno.errno))}"
        )
      }
    }
  }
}
