package ocli.tests

import ocli.OCli
import ocli.OCliAlternateNames
import java.io.File

data class Sample1(
    val verbose: Boolean,
    val firstFile: File,
    val secondFile: File? = null,

    @OCliAlternateNames("-p,-P") val prefix: String = "",
    @OCliAlternateNames("-s,-S") val suffix: String = "",
)

fun main(args: Array<out String>) {
    println("> $ cli "+ args.joinToString(" "))
    OCli.main<Sample1>(args) {
        println(it)
    }
}
