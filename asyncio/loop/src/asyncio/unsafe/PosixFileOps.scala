package asyncio.unsafe

import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import java.io.IOException
import scala.scalanative.posix.errno
import PosixErr.cError
import scala.scalanative.posix.string

object PosixFileOps {
  def safeUnlink(path: CString): Unit = {
    val rmErr = unistd.unlink(path) // remove the socket file if it already exists
    if (rmErr < 0 && errno.errno != errno.ENOENT) {
      throw new IOException(
        s"Failed to unlink existing socket at $path: ${cError()}"
      )
    }
  }

  private def nextPowOfTwo(x: Int): CSize = {
    val maximum = maxBufferSize
    assert(x >= 0, "Size must be non-negative")
    val x0 = x.toCSize
    if (x0 > maximum) maximum else {
      var n = 1.toCSize
      while (n < x0) n <<= 1
      n
    }
  }

  final val maxBufferSize: CSize = 8096.toCSize

  def readBuffer(fd: Int, available: Int)(op: (Ptr[Byte], CSize) => Unit): Unit = {
    val availableCSize = available.toCSize
    Zone.acquire { implicit z =>
      var bufSize = nextPowOfTwo(available)
      var buf = alloc[Byte](bufSize)
      var offset = 0.toCSize
      var continue = true
      while (continue) {
        val bytesRead = unistd.read(
          fd,
          buf + offset,
          (availableCSize `min` (bufSize - offset))
        )
        if (bytesRead < 0) {
          if (errno.errno != errno.EAGAIN && errno.errno != errno.EWOULDBLOCK) {
            throw new IOException(
              s"Failed to read from file descriptor $fd: ${cError()}"
            )
          } else {
            println("No more data available to read (EAGAIN or EWOULDBLOCK).")
            continue = false // Exit the loop if no more data is available
          }
        } else if (bytesRead == 0) {
          println(s"End of file reached on file descriptor $fd. Contents are $offset long.")
          continue = false // Exit the loop if no more data is available
        } else {
          println(s"Actually read $bytesRead bytes from file descriptor $fd (buffer size is $bufSize).")
          offset += bytesRead.toCSize
          if (offset == availableCSize) {
            continue = false // Stop reading if we have read enough data
          } else {
            if (offset == bufSize) {
              if (bufSize == maxBufferSize) {
                // TODO: handle larger messages, possibly with a streaming approach
                throw new IOException(
                  s"Buffer overflow while reading from file descriptor $fd. " +
                    s"Read $offset bytes, but buffer size is fixed at $maxBufferSize bytes."
                )
              } else {
                throw new AssertionError(
                  "expected buffer to be clamped to maxSize"
                )
              }
            }
            // otherwise, perhaps we read less than we expected from available,
            // so we can continue reading.
          }
        }
      }
      op(buf, offset)
    }
  }
}
