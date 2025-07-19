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
import scala.scalanative.unsigned.UInt

object KQueueExampleSocket {
  def run(sock: String): Unit = {
    val kq = event.kqueue()
    if (kq == -1) {
      throw new IOException(s"Failed to create kqueue: ${fromCString(string.strerror(perrno.errno))}")
    }
    try {
      val clientFd = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM, 0)
      if (clientFd < 0) {
        throw new IOException(s"Failed to create socket: ${fromCString(string.strerror(perrno.errno))}")
      }
      try {
        if (fcntl.fcntl(clientFd, fcntl.F_SETFL, fcntl.O_NONBLOCK) < 0) {
          throw new IOException(s"Failed to set socket to non-blocking: ${fromCString(string.strerror(perrno.errno))}")
        }
        val kev = stackalloc[event.kevent](2)
        event.EV_SET(
          (kev + 0),
          clientFd.toUInt,
          event.EVFILT_WRITE.toShort,
          (event.EV_ADD | event.EV_ENABLE | event.EV_CLEAR).toUShort,
          0.toUShort,
          0,
          null
        )
        event.EV_SET(
          (kev + 1),
          clientFd.toUInt,
          event.EVFILT_READ.toShort,
          (event.EV_ADD | event.EV_ENABLE | event.EV_CLEAR).toUShort,
          0.toUShort,
          0,
          null
        )
        if (event.kevent(kq, kev, 2, null, 0, null) < 0) {
          throw new IOException(s"Failed to register events: ${fromCString(string.strerror(perrno.errno))}")
        }
        println("Event registered for socket write and read.")
        println(s"opened file descriptor: $clientFd (socket-type)")
        val maxPathLength = sizeOf[CArray[CChar, un._108]] - 1
        if (sock.length > maxPathLength) {
          throw new IOException(
            s"Socket path exceeds maximum length of $maxPathLength characters: $sock"
          )
        }
        Zone.acquire { implicit z =>
          val addr = z.alloc(sizeOf[un.sockaddr_un]).asInstanceOf[Ptr[un.sockaddr_un]]
          addr.sun_family = socket.AF_UNIX.toUShort
          val path = toCString(sock)
          string.memcpy(addr.sun_path.at(0), path, sock.length.toCSize)
          addr.sun_path(sock.length) = 0.toByte // null-terminate the path
          val errConn = socket.connect(clientFd, addr.asInstanceOf[Ptr[socket.sockaddr]], sizeOf[un.sockaddr_un].toUInt)
          if (errConn < 0 && errno.errno != perrno.EINPROGRESS) {
            throw new IOException(s"Failed to connect socket: ${fromCString(string.strerror(errno.errno))}")
          }
        }
        println(s"connection in progress to path: $sock")
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
          val events = event.kevent(kq, null, 0, polledEvents, 255, null) // infinite wait
          if (events < 0) {
            throw new IOException(s"Failed to poll events: ${fromCString(string.strerror(perrno.errno))}")
          }
          if (events == 0) {
            println("No events polled, continuing...")
          } else {
            var i = 0
            while (i < events) {
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
      } finally {
        if (unistd.close(clientFd) < 0) {
          throw new IOException(s"Failed to close client socket: ${fromCString(string.strerror(perrno.errno))}")
        }
      }
    } finally {
      if (unistd.close(kq) < 0) {
        throw new IOException(s"Failed to close kqueue: ${fromCString(string.strerror(perrno.errno))}")
      }
    }
  }
}
