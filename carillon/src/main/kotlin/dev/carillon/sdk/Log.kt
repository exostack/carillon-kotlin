package dev.carillon.sdk

/**
 * Verbose logging, and the rule that a release build cannot turn it on.
 *
 * Enforcement is at runtime here rather than at compile time as on iOS: an
 * Android library is compiled once and published, so it cannot read the host
 * app's `BuildConfig`. What it can read is the application's own debuggability
 * flag, which the system sets and a release build does not carry. A `debug =
 * true` left in shipped code therefore logs nothing, whatever the code says.
 *
 * `debugInfo()` stays available everywhere: it answers when asked, where this
 * speaks unprompted.
 */
internal object Log {
  const val TAG = "Carillon"

  /**
   * A second destination for the same lines, so a test bench can show them in the
   * app rather than sending the developer to logcat. Behind the same flag, so it
   * cannot become a way to read the SDK's log in production.
   */
  var onLine: ((String) -> Unit)? = null

  fun write(enabled: Boolean, message: () -> String) {
    if (!enabled) return

    val line = message()
    android.util.Log.d(TAG, line)
    onLine?.invoke(line)
  }
}
