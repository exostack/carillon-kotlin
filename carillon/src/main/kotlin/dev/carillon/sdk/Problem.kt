package dev.carillon.sdk

/**
 * An error as the API states it: RFC 9457 Problem Details.
 *
 * Parsed rather than ignored because the registry that produces these writes
 * every message to say what to do next. Swallowing the body and logging
 * "registration failed" throws away the one sentence that would have told the
 * developer their key may not register a device, or which field the server
 * refused.
 */
internal class Problem
private constructor(
  val type: String,
  val title: String,
  val status: Int,
  val code: String,
  val detail: String?,
) {
  /** What goes in a log line: the class, then this occurrence. */
  val summary: String
    get() = detail?.let { "$code: $it" } ?: code

  companion object {
    /**
     * Null when the body is not a problem document — an edge answering with HTML,
     * most often. The status line still carries the verdict, so a failure to parse
     * is never a failure to act.
     */
    fun from(body: String): Problem? {
      val parsed = Json.parse(body) as? Map<*, *> ?: return null
      val type = parsed["type"] as? String ?: return null
      val title = parsed["title"] as? String ?: return null
      val status = (parsed["status"] as? Number)?.toInt() ?: return null
      val code = parsed["code"] as? String ?: return null

      return Problem(type, title, status, code, parsed["detail"] as? String)
    }
  }
}
