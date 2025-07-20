package asyncio.unsafe

import scala.scalanative.unsafe.*
import scala.scalanative.posix.string
import scala.scalanative.posix.errno

object PosixErr {
  def cError(): String = {
    fromCString(string.strerror(errno.errno))
  }
}
