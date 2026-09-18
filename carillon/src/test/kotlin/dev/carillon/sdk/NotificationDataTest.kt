package dev.carillon.sdk

import org.junit.Test
import kotlin.test.assertEquals

class NotificationDataTest {
  @Test fun decodesOnlyMarkedObjectsAndArrays() {
    val raw = mapOf(
      "carillon" to """{"delivery_id":"delivery","json_keys":["object","array","invalid","scalar","missing","carillon"]}""",
      "object" to """{"items":[true,null,{"name":"é"}]}""",
      "array" to "[]",
      "literal" to """{"a":1}""",
      "invalid" to "not json",
      "scalar" to "42",
    )
    val decoded = decodeNotificationData(raw)
    assertEquals(mapOf("items" to listOf(true, null, mapOf("name" to "é"))), decoded["object"])
    assertEquals(emptyList<Any>(), decoded["array"])
    for (key in listOf("literal", "invalid", "scalar", "carillon")) assertEquals(raw[key], decoded[key])
    assertEquals(raw, OpenedNotification("delivery", raw, 0).data)
    assertEquals(decoded, OpenedNotification("delivery", raw, 0).structuredData)
  }

  @Test fun legacyMessagesStayUntouched() {
    for (raw in listOf(mapOf("a" to "{}"), mapOf("carillon" to "invalid"), mapOf("carillon" to "{}"))) {
      assertEquals(raw, decodeNotificationData(raw))
    }
  }
}
