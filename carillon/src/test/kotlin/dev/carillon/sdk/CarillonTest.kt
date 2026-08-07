package dev.carillon.sdk

import kotlin.test.Test
import kotlin.test.assertEquals

class CarillonTest {
  @Test
  fun versionIsSemver() {
    assertEquals(3, Carillon.SDK_VERSION.split(".").size)
  }
}
