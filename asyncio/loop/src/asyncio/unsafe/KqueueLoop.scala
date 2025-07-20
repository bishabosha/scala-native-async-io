package asyncio.unsafe

import asyncio.scalanative.bsd.sys.event
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.{*, given}
import scala.scalanative.posix.string
import scala.scalanative.posix.errno
import scala.scalanative.posix.unistd
import scala.scalanative.posix.time.timespec
import scala.scalanative.posix.timeOps.*
import java.io.IOException
import scala.scalanative.unsigned.USize
import PosixErr.cError

object KqueueLoop {

  def addTimerOneShot(evt: Ptr[event.kevent], id: USize, unit: CUnsignedInt, length: Size): Unit = {
    event.EV_SET(
      evt,
      id, // timer ID
      event.EVFILT_TIMER, // filter type
      (event.EV_ADD | event.EV_ONESHOT).toUShort, // flags
      unit, // fflags
      length, // timeout in seconds
      null // data
    )
  }

  def addFile(evt: Ptr[event.kevent], fd: Int, read: Boolean, clear: Boolean): Unit = {
    var flags = event.EV_ADD | event.EV_ENABLE
    if (clear) {
      flags |= event.EV_CLEAR
    }
    event.EV_SET(
      evt,
      fd.toUSize, // file descriptor
      if (read) event.EVFILT_READ else event.EVFILT_WRITE, // filter type
      flags.toUShort, // flags
      0.toUInt, // fflags
      0, // timeout in seconds
      null // data
    )
  }

  def deleteFile(evt: Ptr[event.kevent], fd: Int, read: Boolean): Unit = {
    event.EV_SET(
      evt,
      fd.toUSize, // file descriptor
      if (read) event.EVFILT_READ else event.EVFILT_WRITE, // filter type
      event.EV_DELETE.toUShort, // flags
      0.toUInt, // fflags
      0, // timeout in seconds
      null // data
    )
  }

  def createAndRegisterEvents(
      kq: Int,
      nEvents: Int
  )(f: Ptr[event.kevent] => Unit): Unit = Zone.acquire { implicit z =>
    val events = z.alloc(sizeOf[event.kevent] * nEvents).asInstanceOf[Ptr[event.kevent]]
    f(events)
    registerEvents(kq, events, nEvents)
  }

  def registerEvents(
      kq: Int,
      events: Ptr[event.kevent],
      nEvents: Int
  ): Unit = {
    if (event.kevent(kq, events, nEvents, null, 0, null) < 0) {
      throw new IOException(
        s"Failed to register events: ${cError()}"
      )
    }
  }

  def pollEventsNow(
      kq: Int,
      events: Ptr[event.kevent],
      nEvents: Int
  ): Int = pollEventsTimeout(kq, events, nEvents, 0, 0)

  def pollEventsForever(
      kq: Int,
      events: Ptr[event.kevent],
      nEvents: Int
  ): Int = {
    val polledEvents = event.kevent(kq, null, 0, events, nEvents, null)
    if (polledEvents < 0) {
      throw new IOException(
        s"Failed to poll events: ${cError()}"
      )
    }
    polledEvents
  }

  def pollEventsTimeout(
      kq: Int,
      events: Ptr[event.kevent],
      nEvents: Int,
      seconds: Int,
      nanoseconds: Int
  ): Int = {
    val timeout = stackalloc[timespec]()
    timeout.tv_sec = seconds
    timeout.tv_nsec = nanoseconds
    val polledEvents = event.kevent(kq, null, 0, events, nEvents, timeout)
    if (polledEvents < 0) {
      throw new IOException(
        s"Failed to poll events: ${cError()}"
      )
    }
    polledEvents
  }

  /** Opens a kqueue.
   */
  def open(): Int = {
    val kq = event.kqueue()
    if (kq < 0) {
      throw new IOException(
        s"Failed to create kqueue: ${cError()}"
      )
    }
    kq
  }

  def close(kq: Int): Unit = {
    if (unistd.close(kq) < 0) {
      throw new IOException(
        s"Failed to close kqueue: ${cError()}"
      )
    }
  }
}
