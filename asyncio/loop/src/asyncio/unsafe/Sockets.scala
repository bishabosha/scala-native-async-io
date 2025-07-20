package asyncio.unsafe

import scala.scalanative.unsafe.Size
import scala.scalanative.posix.sys.socket
import scala.scalanative.meta.LinktimeInfo
import java.io.IOException

object Sockets {
  type Flavor = Byte
  object Flavor {
    val Unix: Flavor = 1
    val IPv4: Flavor = 2
    val IPv6: Flavor = 3
  }
  type Transport = Byte
  object Transport {
    val Stream: Transport = 100
    val Datagram: Transport = 99
  }

  def checkUnixPathLength(sock: String): Unit = {
    if (sock.length > maxPathLength - 1) {
      throw new IOException(
        s"Socket path exceeds maximum length of ${maxPathLength - 1} characters: $sock"
      )
    }
  }

  def maxPathLength: Int = {
    if (LinktimeInfo.isMac) {
      PosixSockets.maxPathLength
    } else {
      0 // Placeholder for other platforms, adjust as needed
    }
  }
}
