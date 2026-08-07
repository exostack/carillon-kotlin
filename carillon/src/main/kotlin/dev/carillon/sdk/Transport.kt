package dev.carillon.sdk

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * One call to the API.
 *
 * The key travels as a bearer token. It is a mobile key — public by
 * construction, since anyone can pull it out of an APK — which is why it is
 * allowed to do so little: register its own device and report that device's
 * events, and nothing else.
 */
internal class HttpRequest(
  val method: String,
  /**
   * Path only. The endpoint is configuration, and a test that had to guess the
   * host in order to assert the path would be asserting the host.
   */
  val path: String,
  val body: String,
  val key: String,
)

/** What came back, before anything decides what it means. */
internal sealed class HttpOutcome {
  class Response(val status: Int, val body: String) : HttpOutcome()

  /**
   * No answer at all: offline, DNS, a timeout. Carries the system's own
   * description, which is what a developer reads in the log.
   */
  class Failure(val reason: String) : HttpOutcome()
}

internal interface Transport {
  suspend fun send(request: HttpRequest): HttpOutcome
}

/**
 * What to do about an outcome. The whole retry policy, as one decision.
 *
 * Retry on no answer, on 429 and on 5xx; never on any other 4xx. A 422 retried
 * forever is a bug loop rather than resilience: the payload will be just as
 * invalid in five minutes, and the queue holding it grows for the life of the
 * install.
 */
internal sealed class Verdict {
  class Accepted(val body: String) : Verdict()

  class Retry(val reason: String) : Verdict()

  class Refused(val problem: Problem?, val status: Int) : Verdict()

  companion object {
    fun of(outcome: HttpOutcome): Verdict =
      when (outcome) {
        is HttpOutcome.Failure -> Retry(outcome.reason)
        is HttpOutcome.Response ->
          when {
            outcome.status in 200..299 -> Accepted(outcome.body)
            outcome.status == 429 || outcome.status >= 500 ->
              Retry(Problem.from(outcome.body)?.summary ?: "HTTP ${outcome.status}")
            else -> Refused(Problem.from(outcome.body), outcome.status)
          }
      }
  }
}

/**
 * `HttpURLConnection`, which is the platform's own client.
 *
 * A dependency-free answer, and a sufficient one: this SDK makes two kinds of
 * small POST, neither of them on a hot path. Reaching for a third-party client
 * would buy connection pooling nothing here would notice and cost the posture
 * the whole SDK is built on.
 */
internal class UrlConnectionTransport(
  private val endpoint: String,
  private val connectTimeoutMs: Int = 15_000,
  private val readTimeoutMs: Int = 20_000,
) : Transport {
  override suspend fun send(request: HttpRequest): HttpOutcome {
    val url =
      try {
        URL(URL(endpoint), request.path)
      } catch (error: Exception) {
        return HttpOutcome.Failure("the endpoint could not be combined with ${request.path}")
      }

    var connection: HttpURLConnection? = null

    return try {
      connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = request.method
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Authorization", "Bearer ${request.key}")
        setRequestProperty("User-Agent", "carillon-kotlin/${Carillon.SDK_VERSION}")
      }

      connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }

      val status = connection.responseCode
      // The error stream carries the problem document on a 4xx or 5xx, and the
      // input stream throws there. Reading the right one is what keeps an RFC
      // 9457 detail out of the bin.
      val stream = if (status in 200..399) connection.inputStream else connection.errorStream
      val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

      HttpOutcome.Response(status, body)
    } catch (error: IOException) {
      HttpOutcome.Failure(error.message ?: "the request did not complete")
    } catch (error: Exception) {
      HttpOutcome.Failure(error.message ?: "the request did not complete")
    } finally {
      connection?.disconnect()
    }
  }
}
