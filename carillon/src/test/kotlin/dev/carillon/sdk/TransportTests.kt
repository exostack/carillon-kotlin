package dev.carillon.sdk

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The real transport, against a real server.
 *
 * The JDK ships one, so the request this SDK actually puts on a socket —
 * headers, body, the error stream a 4xx answers on — is exercised without a
 * dependency and without a device.
 */
class TransportTests {
  private class Recording(val server: HttpServer) {
    var method: String? = null
    var path: String? = null
    var authorization: String? = null
    var contentType: String? = null
    var userAgent: String? = null
    var body: String? = null

    val endpoint: String
      get() = "http://127.0.0.1:${server.address.port}"

    fun stop() = server.stop(0)
  }

  private fun serving(status: Int, response: String): Recording {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val recording = Recording(server)

    server.createContext("/") { exchange ->
      recording.method = exchange.requestMethod
      recording.path = exchange.requestURI.path
      recording.authorization = exchange.requestHeaders.getFirst("Authorization")
      recording.contentType = exchange.requestHeaders.getFirst("Content-Type")
      recording.userAgent = exchange.requestHeaders.getFirst("User-Agent")
      recording.body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)

      val bytes = response.toByteArray(Charsets.UTF_8)
      exchange.sendResponseHeaders(status, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()

    return recording
  }

  @Test
  fun buildsTheRequestTheApiExpects() = runBlocking {
    val recording = serving(200, """{"id":"01937b1e-0000-7000-8000-000000000001"}""")

    try {
      val transport = UrlConnectionTransport(recording.endpoint)
      val outcome =
        transport.send(
          HttpRequest("POST", "/v1/devices", """{"token":"abc"}""", "carillon_mk_live_abc")
        )

      assertEquals("POST", recording.method)
      assertEquals("/v1/devices", recording.path)
      assertEquals("Bearer carillon_mk_live_abc", recording.authorization)
      assertEquals("application/json", recording.contentType)
      assertTrue(recording.userAgent?.startsWith("carillon-kotlin/") == true)
      assertEquals("""{"token":"abc"}""", recording.body)

      val response = outcome as HttpOutcome.Response
      assertEquals(200, response.status)
      assertEquals("01937b1e-0000-7000-8000-000000000001", (Json.parse(response.body) as Map<*, *>)["id"])
    } finally {
      recording.stop()
    }
  }

  @Test
  fun readsTheProblemDocumentOffTheErrorStream() = runBlocking {
    // The input stream throws on a 4xx and the body arrives on the error stream
    // instead. Reading the wrong one is how an RFC 9457 detail — the sentence
    // that says what to do next — ends up in the bin.
    val recording = serving(422, problemBody("invalid_request", 422, detail = "Send a real token."))

    try {
      val transport = UrlConnectionTransport(recording.endpoint)
      val outcome = transport.send(HttpRequest("POST", "/v1/devices", "{}", "k"))
      val verdict = Verdict.of(outcome) as Verdict.Refused

      assertEquals(422, verdict.status)
      assertEquals("invalid_request: Send a real token.", verdict.problem?.summary)
    } finally {
      recording.stop()
    }
  }

  @Test
  fun keepsThePathWhenTheEndpointCarriesATrailingSlash() = runBlocking {
    val recording = serving(202, "{}")

    try {
      val transport = UrlConnectionTransport(recording.endpoint + "/")
      transport.send(HttpRequest("POST", "/v1/events", "{}", "k"))

      assertEquals("/v1/events", recording.path)
    } finally {
      recording.stop()
    }
  }

  @Test
  fun reportsAFailureToReachTheServerRatherThanThrowing() = runBlocking {
    // Nothing is listening on this port. An unreachable server has to read as no
    // answer — which retries — rather than as a response, or as a crash in the
    // host app.
    val transport = UrlConnectionTransport("http://127.0.0.1:1", connectTimeoutMs = 300)
    val outcome = transport.send(HttpRequest("POST", "/v1/devices", "{}", "k"))

    assertTrue(outcome is HttpOutcome.Failure)
  }
}

class VerdictTests {
  @Test
  fun acceptsAnythingInTheTwoHundreds() {
    listOf(200, 202, 204).forEach {
      assertTrue(Verdict.of(HttpOutcome.Response(it, "")) is Verdict.Accepted, "$it is a success")
    }
  }

  @Test
  fun retriesWhatTimeMightFix() {
    // No answer, a rate limit, and a server having a bad minute. None of the
    // three says anything is wrong with the request.
    val outcomes =
      listOf(
        HttpOutcome.Failure("offline"),
        HttpOutcome.Response(429, ""),
        HttpOutcome.Response(500, ""),
        HttpOutcome.Response(503, ""),
      )

    outcomes.forEach { assertTrue(Verdict.of(it) is Verdict.Retry, "worth retrying") }
  }

  @Test
  fun refusesTheRestOfTheFourHundredsOnceAndForAll() {
    listOf(400, 401, 403, 404, 422).forEach {
      assertTrue(
        Verdict.of(HttpOutcome.Response(it, "")) is Verdict.Refused,
        "$it will not be fixed by trying again",
      )
    }
  }

  @Test
  fun carriesTheProblemSoTheLogCanSayWhatToDoNext() {
    val body =
      problemBody(
        "invalid_request",
        422,
        title = "The request could not be understood",
        detail = "Send the FCM token as it was returned.",
      )
    val verdict = Verdict.of(HttpOutcome.Response(422, body)) as Verdict.Refused

    assertEquals(422, verdict.status)
    assertEquals("invalid_request", verdict.problem?.code)
    assertEquals("The request could not be understood", verdict.problem?.title)
    assertEquals("Send the FCM token as it was returned.", verdict.problem?.detail)
    assertEquals(
      "invalid_request: Send the FCM token as it was returned.",
      verdict.problem?.summary,
    )
  }

  @Test
  fun survivesABodyThatIsNotAProblemDocument() {
    // An edge answering with HTML, most often. The status line still carries the
    // verdict, so failing to parse must never be failing to act.
    val verdict = Verdict.of(HttpOutcome.Response(403, "<html>Forbidden</html>")) as Verdict.Refused

    assertNull(verdict.problem)
    assertEquals(403, verdict.status)
  }

  @Test
  fun aProblemWithoutADetailStillNamesItsClass() {
    assertEquals("rate_limited", Problem.from(problemBody("rate_limited", 429))?.summary)
  }
}
