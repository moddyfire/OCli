package ocli

import kotlin.reflect.*
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.primaryConstructor

annotation class OCliAlternateNames( val names: String, val overrideDefault: Boolean = false)
annotation class OCliSubcommand

class OCliException(msg: String, val exitCode: Int = 1, e: Throwable?=null) : RuntimeException(msg, e) {

    companion object {
        fun print(msg: String) : Nothing = throw  OCliException(msg, 0)
        fun fail(msg: String,e: Throwable?=null ) : Nothing = throw  OCliException(msg,1, e)
    }
}

typealias ParseResult = List<Pair<FieldId, Any>>

object OCli {

    inline fun <reified B : Any> builder(): Builder<B> = Builder<B>(B::class)

    class Builder<B : Any>(private val kClass: KClass<B>) {

        internal fun addConverter(id: String, converter: Converter<*>) = apply {
            moreConvertors.put(id, converter)
        }

        fun <T> addConverter(id: String, func: (String)-> T) = addConverter(id, Converter.of(func))
        fun <T> addConverter(id: String, map : Map<String,T>, ignoreCase: Boolean = true) = addConverter(id, Converter.of(map, ignoreCase))

        private val constructor = kClass.primaryConstructor!!
        private val moreConvertors = mutableMapOf<String, Converter<*> >()
        private val params: Map<String, KParameter> = constructor.parameters.associateBy { it.name!! }

        @OptIn(ExperimentalStdlibApi::class)
        internal val items: List<ItemParser> by lazy {
            params.flatMap { (name, it) ->
                val type: KType = it.type
                val kClass = type.classifier as KClass<*>
                if (it.hasAnnotation<OCliSubcommand>()) {
                    val subBuilder = Builder(kClass  as KClass<Any>)
                    val creater = SubItemCreator( FieldId(name, type), subBuilder)
                    subBuilder.items.map { item -> SubcommandItemParser<Any>(creater, item) }
                } else if (type.toString().lastItem() == "Boolean") {
                    listOf(BooleanItemParser(it))
                } else if (kClass.java.isEnum) {
                    @Suppress("UNCHECKED_CAST")
                    val values = kClass.java.enumConstants as Array<Enum<*>>
                    val converter: Converter<Enum<*>> = Converter.of(values.associateBy { it.name })
                    listOf(PrimitiveItemParser(it, converter))
                } else {
                    val searchItems = listOf(name, type.javaType.typeName, type.toString(), type.toString().lastItem())
                        .map { it.removeSuffix("?") }.distinct()
                    val converter = searchItems.mapNotNull { moreConvertors[it] ?: convertorsByType[it] }.firstOrNull()

                    require(converter != null) { "no convertors defined for $name of type ${type}" }
                    listOf(PrimitiveItemParser(it, converter))
                }
            }
        }

        fun build(vararg args: String): B {
            return create(parse(*args))
        }

        fun parse(vararg args: String): ParseResult {

            val result: MutableList<Pair<FieldId, Any>> = mutableListOf()

            var list = args.toList()
            while (list.isNotEmpty()) {
                val found = items.any { o ->
                    val pair = o.matchItems(list)
                    if (pair.first > 0) {
                        result.add(o.field to pair.second!!)
                        list = list.subList(pair.first, list.size)
                        true
                    } else
                        false
                }
                if (!found)
                    throw OCliException("${list[0]} is not a known option")
            }
            return result
        }

        fun create(parseResult: ParseResult): B {

            val bySubCommands = parseResult.filterNot { it.first.parents.isEmpty() }
                .groupBy { it.first.parents.first()  }

            val subValues : ParseResult = bySubCommands.map { (subBuilder, subParseResult: ParseResult) ->

                val moveDown = subParseResult.map { (field, value) -> field.withoutSub() to value }
                subBuilder.create(moveDown)
            }

            val selfValues =  parseResult.filter { it.first.parents.isEmpty() }

            val map: Map<KParameter, Any?> = (selfValues + subValues).map { (field, value) ->
                params[field.name]!! to value
            }.toMap()

            val required = params.values
                .filterNot { it.isOptional }
                .filterNot { map.containsKey(it) }
            if (required.isNotEmpty())
                throw OCliException("Required parameters not provided:" +
                        " ${required.joinToString { it.name!! }}")

            return kClass.primaryConstructor!!.callBy(map)
        }
    }
}

