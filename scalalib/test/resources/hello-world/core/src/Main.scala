import scala.collection._
import java.nio.file.{Files, Paths}

import Main.{args, greeting}
object Main0{
  def apply(s: String, greeting: String) = {
    val resultPath = Paths.get(s)
    Files.createDirectories(resultPath.getParent)
    Files.write(resultPath, greeting.getBytes)
  }
}
object Main extends App {

  val person = Person.fromString("rockjam:25")
  val greeting = s"hello ${person.name}, your age is: ${person.age}"
  println(greeting)
  Main0(args(0), greeting)
}
