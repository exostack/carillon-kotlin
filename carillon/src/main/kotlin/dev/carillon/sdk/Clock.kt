package dev.carillon.sdk

import kotlinx.coroutines.delay

/**
 * The passage of time, injected.
 *
 * Both halves are here for the same reason: a backoff schedule that can only be
 * verified by waiting five minutes is a schedule nobody verifies. Reading the
 * instant is separated too, because an event carries the moment it happened and
 * a test asserting that field needs to know what that moment was.
 */
internal interface Clock {
  fun nowMs(): Long

  suspend fun sleep(milliseconds: Long)
}

internal class SystemClock : Clock {
  override fun nowMs(): Long = System.currentTimeMillis()

  override suspend fun sleep(milliseconds: Long) {
    if (milliseconds > 0) delay(milliseconds)
  }
}
