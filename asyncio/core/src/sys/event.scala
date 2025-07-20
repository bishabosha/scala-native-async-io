package asyncio.scalanative.bsd.sys

import scala.scalanative.unsafe.extern
import scala.scalanative.unsafe.CInt
import scala.scalanative.unsafe.CUnsignedInt
import scala.scalanative.unsafe.CShort
import scala.scalanative.unsafe.CUnsignedShort
import scala.scalanative.unsafe.CVoidPtr
import scala.scalanative.unsafe.Size
import scala.scalanative.unsigned.USize
import scala.scalanative.unsafe.Ptr
import scala.scalanative.unsafe.CStruct6
import scala.scalanative.unsafe.Tag.USize

import scala.scalanative.posix.time.timespec
import scala.scalanative.unsafe.name
import scala.scalanative.unsafe.blocking

@extern
object event {

  // struct kevent {
  //         uintptr_t       ident;          /* identifier for this event */
  //         int16_t         filter;         /* filter for event */
  //         uint16_t        flags;          /* general flags */
  //         uint32_t        fflags;         /* filter-specific flags */
  //         intptr_t        data;           /* filter-specific data */
  //         void            *udata;         /* opaque user data identifier */
  // };
  type kevent = CStruct6[
    USize, // ident
    CShort, // filter
    CUnsignedShort, // flags
    CUnsignedInt, // fflags
    Size, // data
    CVoidPtr // udata
  ]

  object kevent {
    implicit final class KEventOps(val event: Ptr[kevent]) extends AnyVal {
      def ident: USize = event._1
      def filter: CShort = event._2
      def flags: CUnsignedShort = event._3
      def fflags: CUnsignedInt = event._4
      def data: Size = event._5
      def udata: CVoidPtr = event._6

      def ident_=(value: USize): Unit = event._1 = value
      def filter_=(value: CShort): Unit = event._2 = value
      def flags_=(value: CUnsignedShort): Unit = event._3 = value
      def fflags_=(value: CUnsignedInt): Unit = event._4 = value
      def data_=(value: Size): Unit = event._5 = value
      def udata_=(value: CVoidPtr): Unit = event._6 = value
    }
  }

  // int kqueue(void);
  @blocking
  def kqueue(): CInt = extern

  // int kevent(int kq, const struct kevent *changelist, int nchanges, struct kevent *eventlist, int nevents, const struct timespec *timeout);
  @blocking
  def kevent(
      kq: CInt,
      changelist: Ptr[kevent],
      nchanges: CInt,
      eventlist: Ptr[kevent],
      nevents: CInt,
      timeout: Ptr[timespec]
  ): CInt = extern

  @name("asyncio_scalanative_EV_SET")
  def EV_SET(
      kev: Ptr[kevent],
      ident: USize,
      filter: CShort,
      flags: CUnsignedShort,
      fflags: CUnsignedInt,
      data: Size,
      udata: CVoidPtr
  ): Unit = extern

  // Flags
  /** EV_ADD Adds the event to the kqueue. Re-adding an existing event will
    * modify the parameters of the original event, and not result in a duplicate
    * entry. Adding an event automatically enables it, unless overridden by the
    * EV_DISABLE flag.
    */
  @name("asyncio_scalanative_EV_ADD")
  def EV_ADD: CUnsignedShort = extern

  /** EV_ENABLE Permit kevent,() kevent64() and kevent_qos() to return the event
    * if it is triggered.
    */
  @name("asyncio_scalanative_EV_ENABLE")
  def EV_ENABLE: CUnsignedShort = extern

  /** EV_DISABLE Disable the event so kevent,() kevent64() and kevent_qos() will
    * not return it. The filter itself is not disabled.
    */
  @name("asyncio_scalanative_EV_DISABLE")
  def EV_DISABLE: CUnsignedShort = extern

  /** EV_DELETE Removes the event from the kqueue. Events which are attached to
    * file descriptors are automatically deleted on the last close of the
    * descriptor.
    */
  @name("asyncio_scalanative_EV_DELETE")
  def EV_DELETE: CUnsignedShort = extern

  /** EV_RECEIPT This flag is useful for making bulk changes to a kqueue without
    * draining any pending events. When passed as input, it forces EV_ERROR to
    * always be returned. When a filter is successfully added, the data field
    * will be zero.
    */
  @name("asyncio_scalanative_EV_RECEIPT")
  def EV_RECEIPT: CUnsignedShort = extern

  /** EV_ONESHOT Causes the event to return only the first occurrence of the
    * filter being triggered. After the user retrieves the event from the
    * kqueue, it is deleted.
    */
  @name("asyncio_scalanative_EV_ONESHOT")
  def EV_ONESHOT: CUnsignedShort = extern

  /** EV_CLEAR After the event is retrieved by the user, its state is reset.
    * This is useful for filters which report state transitions instead of the
    * current state. Note that some filters may automatically set this flag
    * internally.
    */
  @name("asyncio_scalanative_EV_CLEAR")
  def EV_CLEAR: CUnsignedShort = extern

  /** EV_EOF Filters may set this flag to indicate filter-specific EOF
    * condition.
    */
  @name("asyncio_scalanative_EV_EOF")
  def EV_EOF: CUnsignedShort = extern

  /** EV_OOBAND Read filter on socket may set this flag to indicate the presence
    * of out of band data on the descriptor.
    */
  @name("asyncio_scalanative_EV_OOBAND")
  def EV_OOBAND: CUnsignedShort = extern

  /** EV_ERROR       See RETURN VALUES below. */
  @name("asyncio_scalanative_EV_ERROR")
  def EV_ERROR: CUnsignedShort = extern

  // Filter values

  /** EVFILT_READ Takes a file descriptor as the identifier, and returns
    * whenever there is data available to read. The behavior of the filter is
    * slightly different depending on the descriptor type.
    *
    * Sockets: Sockets which have previously been passed to listen() return when
    * there is an incoming connection pending. data contains the size of the
    * listen backlog.
    *
    * Other socket descriptors return when there is data to be read, subject to
    * the SO_RCVLOWAT value of the socket buffer. This may be overridden with a
    * per-filter low water mark at the time the filter is added by setting the
    * NOTE_LOWAT flag in fflags, and specifying the new low water mark in data.
    * The derived per filter low water mark value is, however, bounded by socket
    * receive buffer's high and low water mark values. On return, data contains
    * the number of bytes of protocol data available to read.
    *
    * The presence of EV_OOBAND in flags, indicates the presence of out of band
    * data on the socket data equal to the potential number of OOB bytes
    * availble to read.
    *
    * If the read direction of the socket has shutdown, then the filter also
    * sets EV_EOF in flags, and returns the socket error (if any) in fflags. It
    * is possible for EOF to be returned (indicating the connection is gone)
    * while there is still data pending in the socket buffer.
    *
    * Vnodes: Returns when the file pointer is not at the end of file. data
    * contains the offset from current position to end of file, and may be
    * negative.
    *
    * Fifos, Pipes: Returns when there is data to read; data contains the number
    * of bytes available.
    *
    * When the last writer disconnects, the filter will set EV_EOF in flags.
    * This may be cleared by passing in EV_CLEAR, at which point the filter will
    * resume waiting for data to become available before returning.
    *
    * Device nodes: Returns when there is data to read from the device; data
    * contains the number of bytes available. If the device does not support
    * returning number of bytes, it will not allow the filter to be attached.
    * However, if the NOTE_LOWAT flag is specified and the data field contains 1
    * on input, those devices will attach - but cannot be relied upon to provide
    * an accurate count of bytes to be read on output.
    */
  @name("asyncio_scalanative_EVFILT_READ")
  def EVFILT_READ: CShort = extern

  /** EVFILT_EXCEPT Takes a descriptor as the identifier, and returns whenever
    * one of the specified exceptional conditions has occurred on the
    * descriptor. Conditions are specified in fflags. Currently, this filter can
    * be used to monitor the arrival of out-of-band data on a socket descriptor
    * using the filter flag NOTE_OOB.
    *
    * If the read direction of the socket has shutdown, then the filter also
    * sets EV_EOF in flags, and returns the socket error (if any) in fflags.
    */
  @name("asyncio_scalanative_EVFILT_EXCEPT")
  def EVFILT_EXCEPT: CShort = extern

  /** EVFILT_WRITE Takes a file descriptor as the identifier, and returns
    * whenever it is possible to write to the descriptor. For sockets, pipes and
    * fifos, data will contain the amount of space remaining in the write
    * buffer. The filter will set EV_EOF when the reader disconnects, and for
    * the fifo case, this may be cleared by use of EV_CLEAR. Note that this
    * filter is not supported for vnodes.
    *
    * For sockets, the low water mark and socket error handling is identical to
    * the EVFILT_READ case.
    */
  @name("asyncio_scalanative_EVFILT_WRITE")
  def EVFILT_WRITE: CShort = extern

  /** EVFILT_TIMER Establishes an interval timer identified by ident where data
    * specifies the timeout period (in milliseconds).
    *
    * fflags can include one of the following flags to specify a different unit:
    *
    * NOTE_SECONDS data is in seconds
    *
    * NOTE_USECONDS data is in microseconds
    *
    * NOTE_NSECONDS data is in nanoseconds
    *
    * NOTE_MACHTIME data is in Mach absolute time units
    *
    * fflags can also include NOTE_ABSOLUTE, which establishes an EV_ONESHOT
    * timer with an absolute deadline instead of an interval. The absolute
    * deadline is expressed in terms of gettimeofday(2). With NOTE_MACHTIME, the
    * deadline is expressed in terms of mach_absolute_time().
    *
    * The timer can be coalesced with other timers to save power. The following
    * flags can be set in fflags to modify this behavior:
    *
    * NOTE_CRITICAL override default power-saving techniques to more strictly
    * respect the leeway value
    *
    * NOTE_BACKGROUND apply more power-saving techniques to coalesce this timer
    * with other timers
    *
    * NOTE_LEEWAY ext[1] holds user-supplied slop in deadline for timer
    * coalescing.
    *
    * The timer will be periodic unless EV_ONESHOT is specified. On return, data
    * contains the number of times the timeout has expired since the last arming
    * or last delivery of the timer event.
    *
    * This filter automatically sets the EV_CLEAR flag.
    */
  @name("asyncio_scalanative_EVFILT_TIMER")
  def EVFILT_TIMER: CShort = extern

  // notes

  /** NOTE_SECONDS data is in seconds */
  @name("asyncio_scalanative_NOTE_SECONDS")
  def NOTE_SECONDS: CUnsignedInt = extern

  /** NOTE_USECONDS data is in microseconds */
  @name("asyncio_scalanative_NOTE_USECONDS")
  def NOTE_USECONDS: CUnsignedInt = extern

  /** NOTE_NSECONDS data is in nanoseconds */
  @name("asyncio_scalanative_NOTE_NSECONDS")
  def NOTE_NSECONDS: CUnsignedInt = extern

  /** NOTE_MACHTIME data is in Mach absolute time units */
  @name("asyncio_scalanative_NOTE_MACHTIME")
  def NOTE_MACHTIME: CUnsignedInt = extern

  /** NOTE_BACKGROUND apply more power-saving techniques to coalesce this timer
    * with other timers
    */
  @name("asyncio_scalanative_NOTE_BACKGROUND")
  def NOTE_BACKGROUND: CUnsignedInt = extern
}
