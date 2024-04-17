package ocli

import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.javaType


class Builder<out B: Any>(internal val kClass: KClass<@UnsafeVariance B>) {

    internal fun addConverter(id: String, converter: Converter<*>) = apply {
        moreConvertors.put(id, converter)
    }

    fun <T> addConverter(id: String, func: (String)-> T) = addConverter(id, Converter.of(func))
    fun <T> addConverter(id: String, map : Map<String,T>, ignoreCase: Boolean = true) = addConverter(id, Converter.of(map, ignoreCase))

    private val constructor = kClass.primaryConstructor!!
    private val moreConvertors = mutableMapOf<String, Converter<*> >()
    internal val params: Map<String, KParameter> = constructor.parameters.associateBy { it.name!! }


    @OptIn(ExperimentalStdlibApi::class)
    internal val items: List<Creator<*, B>> by lazy {

        params.flatMap { (name, it) ->
            val type: KType = it.type
            val kClass = it.kClass()

            val res = if (it.hasAnnotation<OCliOneOf>()) {
                val optionClass = it.findAnnotation<OCliOneOf>()!!.descriptionClass
                listOf(createCommandChoiceItem(it, optionClass))
            }  else if (it.hasAnnotation<OCliInnerMember>()) {
                val subBuilder = Builder(kClass as KClass<Any>)
                val creator = InnerMemberCreator( FieldId(it), subBuilder)
                subBuilder.items.map { item -> InnerMemberItemParser<Any,B>(creator, item as Creator<Any, B>) }
            } else if (type.toString().simpleName() == "Boolean") {
                listOf(BooleanItemParser(it, this))
            } else if (kClass.java.isEnum) {
                @Suppress("UNCHECKED_CAST")
                val values = kClass.java.enumConstants as Array<Enum<*>>
                val converter: Converter<Enum<*>> = Converter.of(values.associateBy { it.name })
                listOf(PrimitiveCreator(it, converter, this))
            } else {
                val converters = listOf(moreConvertors, OCli.globalConvertors, builtInConverters)
                val searchItems = listOf(name, type.javaType.typeName, type.toString(), type.toString().simpleName())
                    .map { it.removeSuffix("?") }.distinct()
                val converter = searchItems.mapNotNull { converters[it] }.firstOrNull()

                require(converter != null) { "no convertors defined for $name of type ${type}" }

                listOf(PrimitiveCreator(it, converter, this))
            }
            res
        }.apply { verify(this) }
    }

   private fun verify(itemParsers: List<Creator<*,B>>) {
       itemParsers.count { it is ChoiceItemParser<*,*> }.takeIf{ it >= 2 }?.let { throw OCliException("There can be at most one @OCliOneOf, but there are $it") }
   }


    private fun KParameter.kClass() = type.classifier as KClass<*>

    private fun createCommandChoiceItem(param: KParameter, optionClass: KClass<*>): Creator<*,B> {
        val choicesBuilder = Builder(optionClass)
        val choices = choicesBuilder.params.mapValues { (name, param) ->
            Builder(param.kClass())
        }
        return ChoiceItemParser<Any,B>(param, this, choices)
    }

    fun parse(args: Array<out String>) : ParseResult {

        items //to apply verify()

        val result: MutableList<Pair<FieldId, Any>> = mutableListOf()

        var list = args.toList()
        while (list.isNotEmpty()) {
            val candidate = list[0]
            val toMatch = candidate.split("=", limit = 2)[0]
            val found = items.any { o ->

                if ( o is Creator<*,*> ) {
                    if (toMatch in o.associatedNames()) {
                        val pair = o.matchItems(list)
                        result.add(o.field to pair.second!!)
                        list = list.drop(pair.first)
                        true
                    } else
                        false
                } else {
                    val pair = o.matchItems(list)
                    if (pair.first > 0) {
                        result.add(o.field to pair.second!!)
                        list = list.subList(pair.first, list.size)
                        true
                    } else
                        false
                }
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

    fun build(args: Array<out String>): B {
        return create(parse(args))
    }

}