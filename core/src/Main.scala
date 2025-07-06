package example

import mainargs.{main, ParserForMethods, arg}

object Main {
  @main
  def file(@arg fifo: String): Unit = {
    KQueueExampleFile.run(fifo)
  }
  @main
  def timer(): Unit = {
    KQueueExampleTimer.run()
  }
  def main(args: Array[String]): Unit = {
    ParserForMethods(this).runOrExit(args)
  }
}
