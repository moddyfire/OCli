package ocli.tests

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ocli.*
import java.io.File

internal fun<B: Any> Builder<B>.build(vararg args: String) = build(args)

class OCliTest : FunSpec({

    test("predefined types") {

        data class Data(
            val numberTwo: Int,
            val file: File? = null,
            val n: String = "Nothing",
            val long: Long,
            @OCliAlternateNames("-s,-S") val myShort: Short = 34
        )

        val builder = OCli.builder<Data>()

        builder.build("--number-two", "7", "--file=hello.txt", "-S", "15", "--long=1") shouldBe
                Data(numberTwo = 7, file = File("hello.txt"), long=1, myShort=15)

        builder.build("--number-two", "3", "-n", "John", "--long=5") shouldBe
                Data(numberTwo = 3, n = "John", long=5)
    }


    test("inner member") {

        data class Inner(
            val x: Int,
            val y: Int = 3,
        )

        data class Data(
            val file: File,
            @OCliInnerMember val sub: Inner
        )
        val builder = OCli.builder<Data>()
        val data = builder.build("-x", "7", "--file=hello.txt",)

        data shouldBe Data(File("hello.txt"), Inner(7))
    }

    test("version") {

        data class Version(
            val version: Boolean = false,
            val verbose: Boolean = false
        ){
            init {
                if (version)
                    if (verbose)
                        OCliException.print("version is 1.8.0")
                    else
                        OCliException.print("1.8.0")
            }
        }

        data class Data(
            val x: Int,
            @OCliInnerMember val sub: Version
        )

        val builder = OCli.builder<Data>()
        val e = shouldThrow<OCliException> {
            builder.build("--version")
        }
        e.message shouldBe "1.8.0"
        e.exitCode shouldBe 0

        val e2 = shouldThrow<OCliException> {
            builder.build("--version", "--verbose")
        }
        e2.message shouldBe "version is 1.8.0"
        e2.exitCode shouldBe 0
    }

    test("booleans") {
        data class Data(val flag: Boolean, val dummy: Int = 0)

        OCli.builder<Data>()
            .build("--flag") shouldBe Data(true)
        OCli.builder<Data>()
            .build("--no-flag") shouldBe Data(false)

        val e = shouldThrow<OCliException> {
            OCli.builder<Data>().build("--dummy", "4")
        }
        e.message shouldBe "Required parameters not provided: flag"
    }

    test("booleans alternate names") {
        data class Data(
            @OCliAlternateNames("-q") val quiet: Boolean=false,
            @OCliAlternateNames("-v", keepDefault = false) val verbose: Boolean=false,
            @OCliAlternateNames("--json=true,--text=false", keepDefault = false) val json: Boolean=false,
            @OCliAlternateNames("+c") val ignoreCase: Boolean=true)
        OCli.builder<Data>().build() shouldBe Data()
        OCli.builder<Data>().build("-q") shouldBe Data(quiet=true)
        OCli.builder<Data>().build("--quiet") shouldBe Data(quiet=true)
        OCli.builder<Data>().build("-v") shouldBe Data(verbose=true)
        OCli.builder<Data>().build("-c") shouldBe Data(ignoreCase=false)
        OCli.builder<Data>().build("+c") shouldBe Data(ignoreCase=true)
        OCli.builder<Data>().build("--json") shouldBe Data(json=true)
        OCli.builder<Data>().build("--text") shouldBe Data(json=false)

        val e = shouldThrow<OCliException> {
            OCli.builder<Data>().build("--verbose")
        }
        e.message shouldBe "--verbose is not a known option"
    }

    test("custom values") {

        data class Data(
            @OCliAlternateNames("--one=1, --two=2")
            val number: Int)

        OCli.builder<Data>()
            .build("--one") shouldBe Data(1)
        OCli.builder<Data>()
            .build("--two") shouldBe Data(2)
    }

    test("global converter") {

        try {
            data class Data(val number: Int)

            OCli.addConverter("number") { it.toInt() + 3 }
            OCli.builder<Data>().build("--number", "7") shouldBe Data(10)

            OCli.addConverter("number") { it.toInt() - 3 }
            OCli.builder<Data>().build("--number", "7") shouldBe Data(4)

        } finally {
            OCli.clearConverters()
        }
    }

    test("custom converter by name") {

        data class Data(val number: Int)
        val data = OCli.builder<Data>()
            .addConverter("number", {it.toInt()+1 })
            .build("--number", "7")
        data shouldBe Data(8)
    }

    test("custom converter by type") {

        data class Data(val number: Int)
        val data = OCli.builder<Data>()
            .addConverter("Int", {it.toInt()+1 })
            .build("--number", "7")
        data shouldBe Data(8)
    }

    test("bad number conversion") {
        data class Data(val number: Int)
        val e = shouldThrow<OCliException> {
            OCli.builder<Data>().build("--number", "hello")
        }

        e.message shouldBe "value hello can't be converted for field number of type kotlin.Int"
     }

    test("no value") {
        data class Data(val number: Int, val string: String)
        val e = shouldThrow<OCliException> {
            OCli.builder<Data>().build("--number", "4", "--string")
        }
        e.message shouldBe "--string must be followed by a value"
    }

    test("required") {
        data class Data(val number: Int, val string: String)
        val e = shouldThrow<OCliException> {
            OCli.builder<Data>().build("--number", "4")
        }
        e.message shouldBe "Required parameters not provided: string"
    }

    test("required 2") {
        data class Data(val index: Int, val label: String)
        val e = shouldThrow<OCliException> {
            OCli.builder<Data>().build()
        }
        e.message shouldBe "Required parameters not provided: index, label"
    }

    test("enumeration") {

        data class Data(val loggerFormat: Format? = null)
        OCli.builder<Data>().build("--logger-format=XML") shouldBe Data(Format.XML)
        OCli.builder<Data>().build("--logger-format","JSON") shouldBe Data(Format.JSON)
        OCli.builder<Data>().build() shouldBe Data()
    }
})

private enum class Format { XML, JSON}
