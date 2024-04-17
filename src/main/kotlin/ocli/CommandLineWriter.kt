package ocli

import kotlin.reflect.full.memberProperties

class CommandLineWriter<B: Any>(val builder: Builder<B>, val default: B) {

    val nonRequiredDefaults: Map<String, Any?> = let {
        builder.kClass.memberProperties.mapNotNull {
            if (it.name in builder.params.keys) {
                val required = !builder.params[it.name]!!.isOptional
                if (required)
                    null
                else
                    it.name to it.invoke(default)
            } else
                null
        }.associate { it }
    }

    fun toArgs(data: @UnsafeVariance B): List<String> {

        return builder.items.flatMap {
                val creator = it as Creator<*,B>
                creator.write(nonRequiredDefaults, data)
            }
    }

}