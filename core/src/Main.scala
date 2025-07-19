package example

import mainargs.{main, ParserForMethods, arg}

object Main {
  @main
  def fileRead(@arg fifo: String): Unit = {
    KQueueExampleFileRead.run(fifo)
  }
  @main
  def fileWrite(@arg fifo: String, @arg(short='m') message: String): Unit = {
    KQueueExampleFileWrite.run(fifo, message)
  }
  @main
  def timer(): Unit = {
    KQueueExampleTimer.run()
  }
  @main
  def sockServe(@arg sock: String): Unit = {
    KQueueExampleServerSocket.run(sock)
  }
  @main
  def sock(@arg sock: String): Unit = {
    KQueueExampleSocket.run(sock)
  }
  @main
  def echo(@arg msg: String): Unit = {
    println(msg)
  }
  @main def targetInfo(): Unit = {
    (
      s"target.arch: ${scalanative.meta.LinktimeInfo.target.arch}",
      s"target.vendor: ${scalanative.meta.LinktimeInfo.target.vendor}",
      s"target.os: ${scalanative.meta.LinktimeInfo.target.os}",
      s"target.env: ${scalanative.meta.LinktimeInfo.target.env}",
      s"continuations: ${scalanative.meta.LinktimeInfo.isContinuationsSupported}",
      s"gc: ${scalanative.meta.LinktimeInfo.garbageCollector}",
      s"multithreaded: ${scalanative.meta.LinktimeInfo.isMultithreadingEnabled}",
      s"debug: ${scalanative.meta.LinktimeInfo.debugMode}",
      s"release: ${scalanative.meta.LinktimeInfo.releaseMode}",
    ).productIterator.foreach(println)
  }
  def main(args: Array[String]): Unit = {
    ParserForMethods(this).runOrExit(args)
  }
}
