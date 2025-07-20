package asyncio.unsafe

import Sockets.Flavor
import Sockets.Transport
import scala.scalanative.posix.sys.socket
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.scalanative.posix.sys.un
import scala.scalanative.posix.sys.unOps.{*, given}
import scala.scalanative.posix.errno
import java.io.IOException
import PosixErr.cError
import scala.scalanative.posix.unistd
import scala.scalanative.posix.fcntl
import scala.scalanative.posix.string
import scala.scalanative.annotation.alwaysinline

object PosixSockets {
  def open(flavor: Flavor, transport: Transport, blocking: Boolean): Int = {
    val posixFlavor = flavor match {
      case Flavor.Unix  => socket.AF_UNIX
      case Flavor.IPv4  => socket.AF_INET
      case Flavor.IPv6  => socket.AF_INET6
    }
    val posixTransport = transport match {
      case Transport.Stream  => socket.SOCK_STREAM
      case Transport.Datagram => socket.SOCK_DGRAM
    }
    val fd = socket.socket(posixFlavor, posixTransport, 0)
    if (fd < 0) {
      throw new IOException(
        s"Failed to open socket: ${cError()}"
      )
    }
    if (!blocking && fcntl.fcntl(fd, fcntl.F_SETFL, fcntl.O_NONBLOCK) < 0) {
      throw new IOException(
        s"Failed to set socket descriptor to non-blocking: ${cError()}"
      )
    }
    fd
  }

  def maxConnections: Int = socket.SOMAXCONN

  def bindUnixAddr(fd: Int, sock: String, removeExisting: Boolean): Unit = {
    Sockets.checkUnixPathLength(sock)
    Zone.acquire { implicit z =>
      val path = toCString(sock)
      if (removeExisting) {
        PosixFileOps.safeUnlink(path) // Ensure the socket path is clean before binding
      }
      val addr = createUnixSocketAddr(path, sock.length)
      val err = socket.bind(fd, addr, sizeOf[un.sockaddr_un].toUInt)
      if (err < 0) {
        throw new IOException(
          s"Failed to bind socket: ${cError()}"
        )
      }
    }
  }

  def listen(fd: Int, backlog: Int): Unit = {
    if (socket.listen(fd, backlog) < 0) {
      throw new IOException(
        s"Failed to listen on socket: ${cError()}"
      )
    }
  }

  def connectUnixAddr(fd: Int, sock: String): Unit = {
    Sockets.checkUnixPathLength(sock)
    Zone.acquire { implicit z =>
      val path = toCString(sock)
      val addr = createUnixSocketAddr(path, sock.length)
      val err = socket.connect(fd, addr, sizeOf[un.sockaddr_un].toUInt)
      if (err < 0 && errno.errno != errno.EINPROGRESS) {
        throw new IOException(s"Failed to connect socket: ${cError()}")
      }
    }
  }

  @alwaysinline
  private def createUnixSocketAddr(path: CString, length: Int)(implicit z: Zone): Ptr[socket.sockaddr] = {
    val addr = z.alloc(sizeOf[un.sockaddr_un]).asInstanceOf[Ptr[un.sockaddr_un]]
    addr.sun_family = socket.AF_UNIX.toUShort
    string.memcpy(addr.sun_path.at(0), path, length.toCSize)
    addr.sun_path(length) = 0.toByte // null-terminate the path
    addr.asInstanceOf[Ptr[socket.sockaddr]]
  }

  def close(fd: Int): Unit = {
    if (unistd.close(fd) < 0) {
      throw new IOException(
        s"Failed to close socket: ${cError()}"
      )
    }
  }

  def maxPathLength: Int = sizeOf[CArray[CChar, un._108]]
}
