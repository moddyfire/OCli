# OCli
Simple CLI for Kotlin  

This is a very simple CLI tool. It converts seemlessly command line arguments to an object.

## Example:

      data class Data(
          val numberOne: Int,
          val file: File,
          val string: String = "Nothing",
          val long: Long,
           @OCliAlternateNames("-s,-S") val myShort: Short = 34
      )`

      fun main(args: Array<out String>) {
        val data = OCli.builder<Data>().make(args)
        println(data)
      }

> $ cli --number-one 7 --file=hello.txt -S 15 --long=1  
> Data(numberOne=7, file=hello.txt, string=Nothing, long=1, myShort=15)


