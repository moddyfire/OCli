package ocli

import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

internal fun String.simpleName() = split(".").last()



sealed interface Creator<T, out H: Any> {
    fun associatedNames() : List<String>
    fun matchItems(args: List<String>) : Pair<Int, T?>

    fun write( defaults: Map<String, Any?>, hostObject: @UnsafeVariance H) : List<String>

    val field: FieldId
}

internal operator fun<K,V> List<Map<K,V>>.get(key: K): V? {
    return map { it[key]}.firstNotNullOfOrNull { it }
}

data class FieldId(val name: String, val type: KType, val parents: List<InnerMemberCreator> = listOf()) {
    fun withoutSub() = FieldId(name, type, parents.subList(0, parents.size -1 ))
}

fun FieldId(param: KParameter) = FieldId(param.name!!, param.type)
fun FieldId(base: FieldId, parent:InnerMemberCreator) = FieldId(base.name, base.type, base.parents + parent)



abstract class DataItemParser<T, H:Any>(val param: KParameter, val host: Builder<H>) : Creator<T,H> {
    override val field = FieldId(param)

    val asProperty: KProperty1<H, T> = host.kClass.memberProperties.first{ it.name == param.name} as KProperty1<H, T>

    protected fun alternateName() = param.findAnnotation<OCliAlternateNames>()
        ?.names?.split(",")?.map { it.trim().split("=", limit = 2) }  ?: listOf()

    protected fun keepDefault() = param.findAnnotation<OCliAlternateNames>()?.keepDefault ?: true

    protected fun camel() = param.name?.split(camelCase)?.joinToString("-"){ it.lowercase()} ?: throw RuntimeException(param.toString())
    private val camelCase = Regex("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])")
}

class InnerMemberItemParser<T,H:Any>(parent: InnerMemberCreator, val subItem: Creator<T,*>) : Creator<T,H> {


    override fun write(defaults: Map<String, Any?>, hostObject: H): List<String> {
        val value = asProperty.invoke(hostObject)


    }

    override fun associatedNames(): List<String>  = subItem.associatedNames()

    override fun matchItems(args: List<String>): Pair<Int, T?> = subItem.matchItems(args)

    override val field = FieldId(subItem.field, parent)

}

class ChoiceItemParser<T,H:Any>(param: KParameter, host: Builder<H>, val options: Map<String, Builder<*>>)
    : DataItemParser<T, H>(param, host)
{
    override fun associatedNames() = options.keys.toList()

    override fun matchItems(args: List<String>): Pair<Int, T?> {
        val chosen = options.get(args[0])
        if (chosen == null)
            return 0 to null
        else {
            val command = chosen.build(args.subList(1, args.size).toTypedArray()) as T?
            return args.size to command
        }
    }

    override fun write(defaults: Map<String, Any?>, hostObject: H): List<String> {
        TODO("Not yet implemented")
    }

}



class BooleanItemParser<H: Any>(param: KParameter, host: Builder<H>) : DataItemParser<Boolean,H>(param, host), Creator<Boolean, H> {


    override fun associatedNames(): List<String> = paramNames.keys.toList()

    override fun write(defaults: Map<String, Any?>, hostObject: H): List<String> {
        val value = asProperty.invoke(hostObject)
        return listOf(paramNames.entries.first{it.value == value}.key)
    }

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


class PrimitiveCreator<T, H:Any>(param: KParameter, anyConverter: Converter<*>, host: Builder<H>)
    : DataItemParser<T,H>(param, host),  Creator<T,H>
{
    val converter: Converter<T> = anyConverter as Converter<T>

    override fun associatedNames(): List<String> = names + predefined.keys

    override fun write(defaults: Map<String, Any?>, hostObject: H): List<String> {
        val value = asProperty.invoke(hostObject)
        if (param.name in defaults && defaults[param.name] == value)
            return listOf()
        else {
            val p = predefined.entries.firstOrNull { it.value == value }
            if ( p == null)
//                return listOf( names.first(), value.toString())
                  return listOf( "${names.first()}=$value")
            else
                return listOf(p.key)
        }
    }

    val names: List<String>
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

        names = (withAnnotation + defaultName).distinct()
    }

    override fun toString(): String {
        return "ItemParser ${param.name} for ${param.type}"
    }
}

class InnerMemberCreator(val field: FieldId, val builder: Builder<*>) {
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

internal val builtInConverters: Map<String, Converter<*> > = mapOf(
    "String" to Converter.of{x -> x},
    "Short" to Converter.of(String::toShort),
    "Int" to Converter.of(String::toInt),
    "Long" to Converter.of(String::toLong),
    "Float" to Converter.of(String::toFloat),
    "Double" to Converter.of(String::toDouble),
    "File" to Converter.of{ x -> File(x)},
)


