package dev.carillon.sdk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal object NotificationImage {
  private const val MAX_BYTES = 10 * 1024 * 1024

  /** The worker fetches through this so a test can hand it a bitmap without a server. */
  @Volatile var fetch: suspend (String) -> Bitmap? = ::download

  suspend fun download(source: String): Bitmap? = withTimeoutOrNull(10_000) {
    suspendCancellableCoroutine { continuation ->
      val executor = Executors.newSingleThreadExecutor()
      val connection = AtomicReference<HttpURLConnection?>()
      val task = executor.submit {
        val result = runCatching {
          var url = URL(source)
          var bytes: ByteArray? = null
          for (hop in 0..3) {
            if (!continuation.isActive || url.protocol != "https") break
            val request = url.openConnection() as HttpURLConnection
            connection.set(request)
            if (!continuation.isActive) { request.disconnect(); break }
            request.connectTimeout = 10_000
            request.readTimeout = 10_000
            request.instanceFollowRedirects = false
            try {
              if (request.responseCode in 300..399) {
                val location = request.getHeaderField("Location") ?: break
                url = URL(url, location)
                continue
              }
              if (request.responseCode !in 200..299 || request.contentLengthLong > MAX_BYTES) break
              val data = request.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= MAX_BYTES && continuation.isActive) {
                  val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                  if (count < 0) break
                  output.write(buffer, 0, count)
                }
                output.toByteArray()
              }
              if (data.size <= MAX_BYTES) bytes = data
              break
            } finally { request.disconnect() }
          }
          bytes?.let { data ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@let null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
          }
        }.getOrNull()
        if (continuation.isActive) continuation.resume(result)
        executor.shutdown()
      }
      continuation.invokeOnCancellation {
        connection.get()?.disconnect()
        task.cancel(true)
        executor.shutdownNow()
      }
    }
  }
}
