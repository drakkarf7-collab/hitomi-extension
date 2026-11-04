package eu.kanade.tachiyomi.extension.all.hitomi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun JsonElement?.jsonObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray) ?: emptyList()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** Hitomi encodes booleans as "1", 1, "" or null. */
internal fun JsonObject.flag(key: String): Boolean = str(key).let { it == "1" || it == "true" }

internal fun JsonObject.names(key: String, field: String): List<String> =
    arr(key).mapNotNull { it.jsonObjectOrNull()?.str(field) }
