package com.shev.agent.tools

import com.google.gson.JsonParser

enum class ToolInputType { STRING, INTEGER, BOOLEAN }

data class ToolInputField(
    val name: String,
    val type: ToolInputType,
    val required: Boolean = true,
    val maxLength: Int = 2048,
    val allowedValues: Set<String> = emptySet(),
    val minInteger: Long? = null,
    val maxInteger: Long? = null
) {
    init {
        require(name.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")))
        require(maxLength in 1..8192)
        require(minInteger == null || maxInteger == null || minInteger <= maxInteger)
        require(type == ToolInputType.STRING || allowedValues.isEmpty())
        require(type == ToolInputType.INTEGER || (minInteger == null && maxInteger == null))
    }
}

enum class ToolInputRejection {
    INVALID_JSON, NOT_OBJECT, UNKNOWN_FIELD, MISSING_REQUIRED_FIELD,
    WRONG_TYPE, STRING_TOO_LONG, VALUE_NOT_ALLOWED, INTEGER_OUT_OF_RANGE
}

data class ToolInputValidation(
    val accepted: Boolean,
    val rejection: ToolInputRejection? = null,
    val field: String? = null
)

/**
 * Strict JSON-object contract for one tool. Unknown keys are rejected.
 * This validates shape only; handlers must still validate domain semantics.
 */
class ToolInputSchema(fields: List<ToolInputField>) {
    private val fieldsByName: Map<String, ToolInputField>

    init {
        require(fields.isNotEmpty())
        require(fields.map { it.name }.distinct().size == fields.size)
        fieldsByName = fields.associateBy { it.name }
    }

    fun validate(input: String): ToolInputValidation {
        val element = try {
            JsonParser.parseString(input)
        } catch (_: Exception) {
            return ToolInputValidation(false, ToolInputRejection.INVALID_JSON)
        }
        if (!element.isJsonObject) return ToolInputValidation(false, ToolInputRejection.NOT_OBJECT)
        val obj = element.asJsonObject

        for (key in obj.keySet()) {
            if (key !in fieldsByName) {
                return ToolInputValidation(false, ToolInputRejection.UNKNOWN_FIELD, key)
            }
        }

        for (field in fieldsByName.values) {
            val value = obj.get(field.name)
            if (value == null || value.isJsonNull) {
                if (field.required) {
                    return ToolInputValidation(false, ToolInputRejection.MISSING_REQUIRED_FIELD, field.name)
                }
                continue
            }

            when (field.type) {
                ToolInputType.STRING -> {
                    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
                        return ToolInputValidation(false, ToolInputRejection.WRONG_TYPE, field.name)
                    }
                    val text = value.asString
                    if (text.length > field.maxLength) {
                        return ToolInputValidation(false, ToolInputRejection.STRING_TOO_LONG, field.name)
                    }
                    if (field.allowedValues.isNotEmpty() && text !in field.allowedValues) {
                        return ToolInputValidation(false, ToolInputRejection.VALUE_NOT_ALLOWED, field.name)
                    }
                }
                ToolInputType.INTEGER -> {
                    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
                        return ToolInputValidation(false, ToolInputRejection.WRONG_TYPE, field.name)
                    }
                    val number = runCatching { value.asLong }.getOrNull()
                        ?: return ToolInputValidation(false, ToolInputRejection.WRONG_TYPE, field.name)
                    if (value.asString.contains('.') || value.asString.contains('e', true)) {
                        return ToolInputValidation(false, ToolInputRejection.WRONG_TYPE, field.name)
                    }
                    if ((field.minInteger != null && number < field.minInteger) ||
                        (field.maxInteger != null && number > field.maxInteger)) {
                        return ToolInputValidation(false, ToolInputRejection.INTEGER_OUT_OF_RANGE, field.name)
                    }
                }
                ToolInputType.BOOLEAN -> {
                    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
                        return ToolInputValidation(false, ToolInputRejection.WRONG_TYPE, field.name)
                    }
                }
            }
        }
        return ToolInputValidation(true)
    }
}
