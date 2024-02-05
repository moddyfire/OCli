# OCli
Simple CLI for Kotlin  

This is a very simple CLI tool.
It converts seemlessly command line arguments to an object.

## Example:

    data class Sample1(
        val verbose: Boolean,
        val firstFile: File,
        val secondFile: File? = null,
        
        @OCliAlternateNames("-p,-P") val prefix: String = "",
        @OCliAlternateNames("-s,-S") val suffix: String = "",
    )

    fun main(args: Array<out String>) {
        OCli.main<Data>(args) {
            println(it)
        }
    }

> $ cli --verbose --first-file=C:/Users/Moddy   
Sample1(verbose=true, firstFile=C:\Users\Moddy, secondFile=null, prefix=, suffix=)

> $ cli --no-verbose --first-file=C:/Users/Moddy -P 55
Sample1(verbose=false, firstFile=C:\Users\Moddy, secondFile=null, prefix=55, suffix=)

