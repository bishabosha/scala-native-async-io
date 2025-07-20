package asyncio.unsafe

import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import java.io.IOException
import scala.scalanative.posix.errno
import PosixErr.cError

object PosixFileOps {
  def safeUnlink(path: CString): Unit = {
    val rmErr = unistd.unlink(path) // remove the socket file if it already exists
    if (rmErr < 0 && errno.errno != errno.ENOENT) {
      throw new IOException(
        s"Failed to unlink existing socket at $path: ${cError()}"
      )
    }
  }
}
