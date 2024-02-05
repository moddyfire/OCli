package ocli


import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation


internal fun String.simpleName() = split(".").last()

data class FieldId(val name: String, val type: KType, val parents: List<SubItemCreator> = listOf()) {
    fun withoutSub() = FieldId(name, type, parents.subList(0, parents.size -1 ))
}

fun FieldId(param: KParameter) = FieldId(param.name!!, param.type)
fun FieldId(base: FieldId, parent:SubItemCreator) = FieldId(base.name, base.type, base.parents + parent)

class SubItemCreator(val field: FieldId, val builder: Builder<*>) {
    fun create(parseResult: ParseResult): Pair<FieldId, Any> {
        try {
            return field to builder.create(parseResult)
        } catch (e:InvocationTargetException) {
            if (e.targetException is OCliException)
                throw e.cause!!
            else
                throw e
        }
    }
}

sealed interface ItemParser {
    fun matchItems(args: List<String>) : Pair<Int, Any?>
    val field: FieldId
}

abstract class DataItemParser<T>(val param: KParameter) : ItemParser {
    override val field = FieldId(param)

    protected fun alternateName() = param.findAnnotation<OCliAlternateNames>()
        ?.names?.split(",")?.map { it.trim().split("=", limit = 2) }  ?: listOf()

    protected fun keepDefault() = param.findAnnotation<OCliAlternateNames>()?.keepDefault ?: true

    protected fun camel() = param.name?.split(camelCase)?.joinToString("-"){ it.lowercase()} ?: throw RuntimeException(param.toString())
    private val camelCase = Regex("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])")
}

class SubcommandItemParser<T>( parent: SubItemCreator, val subItem: ItemParser) : ItemParser {
    override fun matchItems(args: List<String>): Pair<Int, Any?>  = subItem.matchItems(args)
    override val field = FieldId(subItem.field, parent)
}

class BooleanItemParser(param: KParameter) : DataItemParser<Boolean>(param) {

    val paramNames : Map<String, Boolean>
    init {
        val map = mutableMapOf<String, Boolean>()
        alternateName().forEach { parts ->
            val name = parts[0]
            if (parts.size > 1 ) {
                val res: Boolean = parts[1].lowercase().toBooleanStrictOrNull()
                    ?: throw OCliException("AlternateNames for ${field.name} has non-boolean value ${parts.joinToString("=")}")
                map.put(name, res)
            } else if (name.startsWith("-")) {
                map.put(name, true)
            } else if (name.startsWith("+")) {
                map.put(name, true)
                map.put("-" +name.substring(1), false)
            } else
                throw OCliException("AlternateNames for ${field.name}: it is not clear if $name means 'true' or 'false")
        }
        if (keepDefault()) {
            val camel = camel()
            val camelTrue =  if (camel.length == 1) "-$camel" else "--$camel"

            map.put(camelTrue, true)
            map.put("--no-$camel", false)
        }
        if (map.isEmpty())
            throw OCliException("No Names for ${field.name}")
        paramNames = map.toMap()
    }

    override fun matchItems(args: List<String>): Pair<Int, Boolean?> {
        when (paramNames[args[0]]) {
            null -> return 0 to null
            true -> return 1 to true
            false -> return 1 to false
        }
    }

}

class PrimitiveItemParser<T>(param: KParameter, val converter: Converter<T>) :
    DataItemParser<T>(param) {

    val names: Set<String>
    val predefined: Map<String, T>

    override fun matchItems(args: List<String>): Pair<Int, T?> {
        val candidate = args[0]

        if (names.contains(candidate)) {
            if (args.size < 2)
                throw OCliException("$candidate must be followed by a value")
            else
                return 2 to convert(args[1])
        } else if (candidate.contains("=")) {
            val split = candidate.split(Regex("="), limit = 2)
            if (names.contains(split[0]))
                return 1 to convert(split[1])
        } else if (predefined.containsKey(candidate)) {
            return 1 to predefined[candidate]!!
        }
        return 0 to null
    }

    fun convert(param: String) = try {
        converter.parse(param)
    } catch (e: OCliException) {
        OCliException.fail(e.message!!
            .replace("%n", field.name)
            .replace("%t", field.type.toString()), e.cause)
    } catch (e: Exception) {
        OCliException.fail("value $param can't be converted for field ${field.name} of type ${field.type}")
    }

    init {

         val defaultName = if (keepDefault())
             listOf(camel().let { if (it.length == 1) "-$it" else "--$it"} )
         else
             listOf()

        val withAnnotation = alternateName().filter{ it.size == 1}.map { it[0] }
        predefined = alternateName().filter{ it.size > 1}.associate { it[0] to converter.parse(it[1]) }

        names = (withAnnotation + defaultName).toSet()
    }

    override fun toString(): String {
        return "ItemParser ${param.name} for ${param.type}"
    }
}

interface Converter<T> {
    fun parse(param: String) : T
    fun toString(item: T) : String = item?.toString() ?: ""

    companion object {
        fun <T> of(func: (String) -> T) = object : Converter<T> {
            override fun parse(param: String) = func(param)
        }

        fun <T> of(map : Map<String,T>, ignoreCase: Boolean = true) = object : Converter<T> {
            val myMap = if (ignoreCase) map.mapKeys { it.key.lowercase() } else map

            override fun parse(param: String) : T {
                val cased =  if (ignoreCase) param.lowercase() else param
                return myMap[cased] ?: throw OCliException("value $param is not legal for item %n. Possible values are ${map.keys}")
            }
        }
    }
}

internal val convertorsByType: Map<String, Converter<*> > = mapOf(
    "String" to Converter.of{x -> x},
    "Short" to Converter.of(String::toShort),
    "Int" to Converter.of(String::toInt),
    "Long" to Converter.of(String::toLong),
    "Float" to Converter.of(String::toFloat),
    "Double" to Converter.of(String::toDouble),
    "File" to Converter.of{ x -> File(x)},
)


