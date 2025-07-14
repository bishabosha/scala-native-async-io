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
        Zone.acquire { implicit z =>
          val addr = z.alloc(sizeOf[un.sockaddr_un]).asInstanceOf[Ptr[un.sockaddr_un]]
          addr.sun_family = socket.AF_UNIX.toUShort
          val path = toCString(sock)
          val rmErr = unistd.unlink(path) // remove the socket file if it already exists
          if (rmErr < 0 && errno.errno != perrno.ENOENT) {
            throw new IOException(
              s"Failed to unlink existing socket at $sock: ${fromCString(string.strerror(errno.errno))}"
            )
          }
          string.strncpy(addr.sun_path.at(0), path, (sizeof[un._108] - 1.toCSize).min(string.strlen(path)))
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
                println("server socket ready for accept.")
                val clientFd = socket.accept(fd, null, null)
                if (clientFd < 0) {
                  throw new IOException(
                    s"Failed to accept connection: ${fromCString(string.strerror(errno.errno))}"
                  )
                }
                println(s"Accepted new client connection on fd: $clientFd")
                clientFds += clientFd
                // Register the new client socket for read events
                val clientEvents = stackalloc[event.kevent](2)
                event.EV_SET(
                  clientEvents,
                  clientFd.toUSize, // file descriptor
                  event.EVFILT_READ, // filter type
                  (event.EV_ADD | event.EV_ENABLE | event.EV_CLEAR).toUShort, // flags
                  0.toUInt, // fflags
                  0, // data
                  null // udata
                )
                event.EV_SET(
                  clientEvents + 1,
                  clientFd.toUSize, // file descriptor
                  event.EVFILT_WRITE, // filter type
                  (event.EV_ADD | event.EV_ENABLE | event.EV_CLEAR).toUShort, // flags
                  0.toUInt, // fflags
                  0, // data
                  null // udata
                )
                if (event.kevent(kq, clientEvents, 2, null, 0, null) < 0) {
                  throw new IOException(
                    s"Failed to register client event: ${fromCString(string.strerror(errno.errno))}"
                  )
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
                    clientFds -= clientFd
                    if (unistd.close(clientFd) < 0) {
                      throw new IOException(
                        s"Failed to close client socket $clientFd: ${fromCString(string.strerror(errno.errno))}"
                      )
                    }
                    println(s"Closed client socket $clientFd due to error.")
                  } else {
                    val noun = if (evt.filter == event.EVFILT_READ) "read" else "write"
                    println(s"client socket $clientFd ready for $noun.")
                    val available = evt.data.toInt
                    var continue = true
                    while (continue) {
                      val bytesRead = unistd.read(
                        clientFd,
                        byteArray.at(offset),
                        (1024 `min` (byteArray.length - offset)).toCSize
                      )
                      if (bytesRead < 0) {
                        if (errno.errno != perrno.EAGAIN && errno.errno != perrno.EWOULDBLOCK) {
                          throw new IOException(
                            s"Failed to read from client socket: ${fromCString(string.strerror(errno.errno))}"
                          )
                        } else {
                          println("No more data available to read (EAGAIN or EWOULDBLOCK).")
                          continue = false // Exit the loop if no more data is available
                        }
                      } else if (bytesRead == 0) {
                        println(s"End of file reached on client socket $clientFd. Contents are $offset long.")
                        println(s"message contents: `${new String(byteArray.take(offset))}`")
                        // TODO: send the message back to the client?
                        reset() // Reset the byte array for the next read
                        deregisterBuf += clientFd
                        println(s"will deregister client socket $clientFd after polling due to EOF.")
                        continue = false // Exit the loop if no more data is available
                        // for now, accept more connections, else // doPoll = false
                      } else {
                        println(s"Actually read $bytesRead bytes from client socket $clientFd")
                        offset += bytesRead.toInt
                        if (offset >= byteArray.length) {
                          dbl()
                        }
                      }
                    }
                  }
                }
              }
              i += 1
            }
          }
          if (deregisterBuf.nonEmpty) {
            for (clientFd <- deregisterBuf) {
              println("Deregistering client socket: " + clientFd)
              clientFds -= clientFd
              if (unistd.close(clientFd) < 0) {
                throw new IOException(
                  s"Failed to close client socket $clientFd: ${fromCString(string.strerror(errno.errno))}"
                )
              }
            }
            deregisterBuf = Set.empty[Int]
          }
        }

        // val changeEvent = stackalloc[event.kevent]()
        // event.EV_SET(
        //   changeEvent,
        //   fd.toUSize, // file descriptor
        //   event.EVFILT_READ, // filter type
        //   (event.EV_ADD | event.EV_ENABLE | event.EV_CLEAR).toUShort, // flags
        //   0.toUInt, // fflags
        //   0, // timeout in seconds
        //   null // data
        // )
        // if (event.kevent(kq, changeEvent, 1, null, 0, null) < 0) {
        //   throw new IOException(
        //     s"Failed to register event: ${fromCString(string.strerror(errno.errno))}"
        //   )
        // } else {
        //   println("Event registered for file read.")
        // }
        // var byteArray = null.asInstanceOf[Array[Byte]]
        // def reset() = {
        //   byteArray = new Array[Byte](1024)
        // }
        // var offset = 0
        // def dbl() = {
        //   offset = 0
        //   byteArray = new Array[Byte](byteArray.length * 2)
        // }
        // // val timeout = stackalloc[timespec]()
        // // timeout.tv_sec = 1 // seconds
        // // timeout.tv_nsec = 0 // nanoseconds
        // val polledEvents = stackalloc[event.kevent]()
        // reset() // initialize the byte array
        // try {
        //   while (true) {
        //     // Wait for the event to be triggered
        //     println(
        //       s"starting to poll..."
        //     )
        //     val nev =
        //       event.kevent(kq, null, 0, polledEvents, 1, null) // infinite wait
        //     if (nev < 0) {
        //       throw new IOException(
        //         s"Failed to wait for event: ${fromCString(string.strerror(errno.errno))}"
        //       )
        //     } else if (nev == 0) {
        //       println("No events triggered within the timeout period.")
        //     } else {
        //       println(
        //         s"Event triggered: ID = ${polledEvents.ident}, Filter = ${polledEvents.filter}, Data = ${polledEvents.data}"
        //       )
        //       assert(
        //         polledEvents.filter == event.EVFILT_READ && polledEvents.ident == fd.toUSize
        //       )
        //       val available = polledEvents.data.toInt
        //       val atEOF = (polledEvents.flags & event.EV_EOF) != 0
        //       println(s"Bytes available to read: $available (atEOF: $atEOF)")

        //       var continue = true
        //       while (continue) {
        //         // read all data until EOF or EAGAIN
        //         var bytesRead = unistd.read(
        //           fd,
        //           byteArray.at(offset),
        //           (1024 `min` (byteArray.length - offset)).toCSize
        //         )
        //         if (bytesRead < 0) {
        //           if (
        //             errno.errno != perrno.EAGAIN && errno.errno != perrno.EWOULDBLOCK
        //           ) {
        //             throw new IOException(
        //               s"Failed to read file: ${fromCString(string.strerror(errno.errno))}"
        //             )
        //           } else {
        //             println(
        //               "No more data available to read (EAGAIN or EWOULDBLOCK)."
        //             )
        //             continue = false // Exit the loop if no more data is available
        //           }
        //         } else if (bytesRead == 0) {
        //           println(s"End of file reached. Contents are $offset long.")
        //           println(
        //             s"File contents: `${new String(byteArray.take(offset))}`"
        //           )
        //           reset() // Reset the byte array for the next read
        //           continue = false // Exit the loop if no more data is available
        //         } else {
        //           println(s"actually read $bytesRead bytes")
        //           offset += bytesRead.toInt
        //           if (offset >= byteArray.length) {
        //             dbl()
        //           }
        //         }
        //       }
        //     }
        //   }
        // }
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
