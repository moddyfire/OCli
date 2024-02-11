package ocli

import kotlin.reflect.*
import kotlin.system.exitProcess

/**
 * Specifies alternative names for this Cli item
 *
 * @param names comma-separated list of names,  including a prefix (e.g. --)
 * @param keepDefault if 'true' (the default) use also the automatically created names
 */
annotation class OCliAlternateNames( val names: String, val keepDefault: Boolean = true)

/**
 * Indicates that the Cli item is an object
 *
 * The inner member's items are flatenned in the Cli.
 */
annotation class OCliInnerMember

/**
 * Indicates that the Cli item is an object
 *
 * The inner member's items are flatenned in the Cli.
 */
annotation class OCliOneOf(val descriptionClass: KClass<*> )


/**
 * Exception thrown by OCli
 *
 * OCliException is thrown by the parser if the command line doesn't match the specification.
 *
 * It may also be thrown by the user-supplied converters, validators or constructors.
 *
 *  @property msg the message
 *  @property exitCode the code for exitProcess()
 *  @property cause optional cause
 *
 */
class OCliException(msg: String, val exitCode: Int = 1, cause: Throwable?=null) : RuntimeException(msg, cause) {

    companion object {

        /**
         * Throw a Success exception
         *
         * If process runs with Builder.main(), print the message and exit the process with exit-code 0
         */
        fun print(msg: String) : Nothing = throw OCliException(msg, 0)

        /**
         * Throw a Fail exception
         *
         * If process runs with Builder.main(), print the message and exit the process with exit-code 1
         */
        fun fail(msg: String,e: Throwable?=null ) : Nothing = throw  OCliException(msg,1, e)
    }
}

typealias ParseResult = List<Pair<FieldId, Any>>


interface OCliCommandChoice {
}

object OCli {

    /**
     * create a Builder for object of type B
     */
    inline fun <reified B : Any> builder(): Builder<B> = Builder<B>(B::class)

    /**
     * run main with a simple builder
     */
    inline fun <reified B : Any> main(args: Array<out String>, noinline mainProc: (B) -> Unit) {
      builder<B>().main(args, mainProc)
    }

    /**
     * run main
     */
    fun<B:Any> Builder<B>.main(args: Array<out String>, mainProc: (B) -> Unit) {
        val parsed = try {
             build(args)
        } catch (e: OCliException) {
            val writer = if (e.exitCode == 0) System.out else System.err
            writer.println(e.message)
            if (e.cause != null)
                e.printStackTrace()
            exitProcess(e.exitCode)
        }
        mainProc.invoke(parsed)
    }
}

