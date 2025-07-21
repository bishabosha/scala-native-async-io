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
  def open(flavor: Flavor, transport: Transport): Int = {
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
    fd
  }

  def setNonBlocking(fd: Int): Unit = {
    if (fcntl.fcntl(fd, fcntl.F_SETFL, fcntl.O_NONBLOCK) < 0) {
      throw new IOException(
        s"Failed to set socket descriptor to non-blocking: ${cError()}"
      )
    }
  }

  def maxConnections: Int = socket.SOMAXCONN

  @alwaysinline
  private def unixSocketAddrLen: socket.socklen_t =
    sizeOf[un.sockaddr_un].toUInt

  def bindUnixAddr(fd: Int, sock: String, removeExisting: Boolean): Unit = {
    Sockets.checkUnixPathLength(sock)
    Zone.acquire { implicit z =>
      val path = toCString(sock)
      if (removeExisting) {
        PosixFileOps.safeUnlink(path) // Ensure the socket path is clean before binding
      }
      val addr = createUnixSocketAddr(path, sock.length)
      val err = socket.bind(fd, addr, unixSocketAddrLen)
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
      val err = socket.connect(fd, addr, unixSocketAddrLen)
      if (err < 0 && errno.errno != errno.EINPROGRESS) {
        throw new IOException(s"Failed to connect socket: ${cError()}")
      }
    }
  }

  /** Accept a Unix domain socket connection.
   *
   * If the socket is non-blocking, it will throw if a connection is un-available. Use within an
   * event loop to be notified when a connection is available.
   */
  def acceptUnix(fd: Int): Int = {
    val clientFd = socket.accept(fd, null, null)
    if (clientFd < 0) {
      if (errno.errno == errno.EAGAIN || errno.errno == errno.EWOULDBLOCK)
        // TODO: perhaps between the event and calling accept the socket was closed?
        // So test this.
        throw new IOException("No pending connections to accept")
      else
        throw new IOException(s"Failed to accept connection: ${cError()}")
    }
    clientFd
  }

  @alwaysinline
  private def createUnixSocketAddr(path: CString, length: Int)(implicit z: Zone): Ptr[socket.sockaddr] = {
    val addr = alloc[un.sockaddr_un]()
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
