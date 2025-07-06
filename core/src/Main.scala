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
  def main(args: Array[String]): Unit = {
    ParserForMethods(this).runOrExit(args)
  }
}
