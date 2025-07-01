#if (defined(__APPLE__) && defined(__MACH__))
#include <sys/types.h>
#include <sys/event.h>
#include <sys/time.h>

// wrap EV_SET macro
void asyncio_scalanative_EV_SET(
    struct kevent *kev,
    uintptr_t ident,
    int16_t filter,
    uint16_t flags,
    uint32_t fflags,
    intptr_t data,
    void *udata) {
  EV_SET(kev, ident, filter, flags, fflags, data, udata);
}

// flags
uint16_t asyncio_scalanative_EV_ADD() {
  return EV_ADD;
}
uint16_t asyncio_scalanative_EV_ENABLE() {
  return EV_ENABLE;
}
uint16_t asyncio_scalanative_EV_DISABLE() {
  return EV_DISABLE;
}
uint16_t asyncio_scalanative_EV_DELETE() {
  return EV_DELETE;
}
uint16_t asyncio_scalanative_EV_RECEIPT() {
  return EV_RECEIPT;
}
uint16_t asyncio_scalanative_EV_ONESHOT() {
  return EV_ONESHOT;
}
uint16_t asyncio_scalanative_EV_CLEAR() {
  return EV_CLEAR;
}
uint16_t asyncio_scalanative_EV_EOF() {
  return EV_EOF;
}
uint16_t asyncio_scalanative_EV_OOBAND() {
  return EV_OOBAND;
}
uint16_t asyncio_scalanative_EV_ERROR() {
  return EV_ERROR;
}

// filters
int16_t asyncio_scalanative_EVFILT_READ() {
  return EVFILT_READ;
}
int16_t asyncio_scalanative_EVFILT_EXCEPT() {
  return EVFILT_EXCEPT;
}
int16_t asyncio_scalanative_EVFILT_TIMER() {
  return EVFILT_TIMER;
}

// notes
int32_t asyncio_scalanative_NOTE_SECONDS() {
  return NOTE_SECONDS;
}
int32_t asyncio_scalanative_NOTE_USECONDS() {
  return NOTE_USECONDS;
}
int32_t asyncio_scalanative_NOTE_NSECONDS() {
  return NOTE_NSECONDS;
}
int32_t asyncio_scalanative_NOTE_MACHTIME() {
  return NOTE_MACHTIME;
}

int32_t asyncio_scalanative_NOTE_BACKGROUND() {
  return NOTE_BACKGROUND;
}

#endif