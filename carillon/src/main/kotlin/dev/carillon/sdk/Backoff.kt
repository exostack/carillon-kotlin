package dev.carillon.sdk

/**
 * How long to wait before the next attempt.
 *
 * Doubling from one second to a ceiling of five minutes. The ceiling matters
 * more than the curve: a device that has been offline all day must keep trying
 * often enough to register within a minute of coming back, and an SDK that has
 * backed off to an hour looks exactly like an SDK that is broken.
 *
 * A pure function of the attempt number, so the schedule is a thing that can be
 * asserted rather than a behaviour that has to be waited for.
 */
internal object Backoff {
  const val BASE_MS: Long = 1_000
  const val CEILING_MS: Long = 300_000

  /** [failures] is the number of failures so far, so the first retry is [BASE_MS]. */
  fun delayMs(failures: Int): Long {
    if (failures <= 0) return 0

    // Capped before the shift, not after: 1 shl 62 seconds is not a long wait,
    // it is an overflow trap.
    val doublings = minOf(failures - 1, 16)

    return minOf(BASE_MS shl doublings, CEILING_MS)
  }
}
