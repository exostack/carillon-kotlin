package dev.carillon.sdk

internal fun decodeNotificationData(data: Map<String, String>): Map<String, Any?> {
  val stamp = data["carillon"]?.let(Json::parse) as? Map<*, *>
  val keys = stamp?.get("json_keys") as? List<*> ?: return data
  return data.mapValues { (key, value) ->
    if (key != "carillon" && key in keys) {
      when (val decoded = Json.parse(value)) {
        is Map<*, *>, is List<*> -> decoded
        else -> value
      }
    } else value
  }
}
