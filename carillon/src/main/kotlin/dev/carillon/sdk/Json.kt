package dev.carillon.sdk

/**
 * JSON, written here rather than taken from anywhere.
 *
 * Android ships `org.json`, and on a device it would do. In a JVM unit test it
 * is a stub whose every method throws, so a build with no Robolectric — which is
 * a third party, and this SDK has one dependency — cannot exercise a single line
 * that touches it. The bodies this SDK sends are its entire contract with the
 * server; testing them is not optional, so the encoder is ours.
 *
 * Keys are sorted on the way out for the reason iOS sorts them: the state
 * fingerprint is a serialised body, and a map that serialised in a different
 * order on the next launch would make every cold start look like a change and
 * re-register the whole park.
 */
internal object Json {
  fun encode(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

  private fun write(value: Any?, out: StringBuilder) {
    when (value) {
      null -> out.append("null")
      is String -> writeString(value, out)
      is Boolean -> out.append(if (value) "true" else "false")
      is Int, is Long -> out.append(value.toString())
      is Double, is Float -> writeNumber((value as Number).toDouble(), out)
      is Map<*, *> -> writeObject(value, out)
      is List<*> -> writeArray(value, out)
      else -> writeString(value.toString(), out)
    }
  }

  private fun writeObject(value: Map<*, *>, out: StringBuilder) {
    out.append('{')
    value.entries
      .map { it.key.toString() to it.value }
      .sortedBy { it.first }
      .forEachIndexed { index, (name, entry) ->
        if (index > 0) out.append(',')
        writeString(name, out)
        out.append(':')
        write(entry, out)
      }
    out.append('}')
  }

  private fun writeArray(value: List<*>, out: StringBuilder) {
    out.append('[')
    value.forEachIndexed { index, entry ->
      if (index > 0) out.append(',')
      write(entry, out)
    }
    out.append(']')
  }

  /**
   * A whole number is written without its decimal point.
   *
   * `Double.toString()` renders 5.0 as "5.0", and a tag that went in as a whole
   * number would arrive at the server as a fraction. The server accepts both, but
   * the customer filtering an audience on `seats = 5` would not recognise it.
   */
  private fun writeNumber(value: Double, out: StringBuilder) {
    if (value.isFinite() && value == Math.floor(value) && Math.abs(value) < 1e15) {
      out.append(value.toLong().toString())
    } else {
      out.append(value.toString())
    }
  }

  private fun writeString(value: String, out: StringBuilder) {
    out.append('"')
    for (character in value) {
      when (character) {
        '"' -> out.append("\\\"")
        '\\' -> out.append("\\\\")
        '\n' -> out.append("\\n")
        '\r' -> out.append("\\r")
        '\t' -> out.append("\\t")
        '\b' -> out.append("\\b")
        '' -> out.append("\\f")
        else ->
          if (character < ' ') {
            out.append("\\u").append(String.format("%04x", character.code))
          } else {
            out.append(character)
          }
      }
    }
    out.append('"')
  }

  /**
   * Parses into maps, lists, strings, doubles, booleans and null.
   *
   * Returns null on anything malformed rather than throwing: every caller is
   * reading something it did not write — a problem document from an edge that
   * answered with HTML, a notification payload assembled by a server it cannot
   * see — and none of them has a better answer to a parse failure than to carry
   * on without it.
   */
  fun parse(text: String): Any? =
    try {
      Reader(text).let { reader ->
        val value = reader.readValue()
        reader.skipWhitespace()
        if (reader.done()) value else null
      }
    } catch (_: IllegalStateException) {
      null
    } catch (_: IndexOutOfBoundsException) {
      null
    } catch (_: NumberFormatException) {
      null
    }

  private class Reader(private val text: String) {
    private var index = 0

    fun done(): Boolean = index >= text.length

    fun skipWhitespace() {
      while (index < text.length && text[index].isWhitespace()) index++
    }

    fun readValue(): Any? {
      skipWhitespace()

      return when (val character = text[index]) {
        '{' -> readObject()
        '[' -> readArray()
        '"' -> readString()
        't' -> readLiteral("true", true)
        'f' -> readLiteral("false", false)
        'n' -> readLiteral("null", null)
        else -> if (character == '-' || character.isDigit()) readNumber() else error("unexpected")
      }
    }

    private fun readObject(): Map<String, Any?> {
      val entries = LinkedHashMap<String, Any?>()
      index++
      skipWhitespace()

      if (text[index] == '}') {
        index++

        return entries
      }

      while (true) {
        skipWhitespace()
        val name = readString()
        skipWhitespace()
        check(text[index] == ':') { "expected a colon" }
        index++
        entries[name] = readValue()
        skipWhitespace()

        when (text[index]) {
          ',' -> index++
          '}' -> {
            index++

            return entries
          }
          else -> error("expected a comma or a closing brace")
        }
      }
    }

    private fun readArray(): List<Any?> {
      val entries = ArrayList<Any?>()
      index++
      skipWhitespace()

      if (text[index] == ']') {
        index++

        return entries
      }

      while (true) {
        entries.add(readValue())
        skipWhitespace()

        when (text[index]) {
          ',' -> index++
          ']' -> {
            index++

            return entries
          }
          else -> error("expected a comma or a closing bracket")
        }
      }
    }

    private fun readString(): String {
      check(text[index] == '"') { "expected a string" }
      index++
      val out = StringBuilder()

      while (true) {
        when (val character = text[index++]) {
          '"' -> return out.toString()
          '\\' ->
            when (val escaped = text[index++]) {
              '"' -> out.append('"')
              '\\' -> out.append('\\')
              '/' -> out.append('/')
              'n' -> out.append('\n')
              'r' -> out.append('\r')
              't' -> out.append('\t')
              'b' -> out.append('\b')
              'f' -> out.append('')
              'u' -> {
                out.append(text.substring(index, index + 4).toInt(16).toChar())
                index += 4
              }
              else -> out.append(escaped)
            }
          else -> out.append(character)
        }
      }
    }

    private fun readNumber(): Double {
      val start = index
      while (index < text.length && (text[index].isDigit() || text[index] in "-+.eE")) index++

      return text.substring(start, index).toDouble()
    }

    private fun <T> readLiteral(word: String, value: T): T {
      check(text.startsWith(word, index)) { "expected $word" }
      index += word.length

      return value
    }
  }
}
