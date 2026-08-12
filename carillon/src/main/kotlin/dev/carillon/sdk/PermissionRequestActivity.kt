package dev.carillon.sdk

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred

/**
 * The screen that asks, and is never seen.
 *
 * Android delivers a permission answer to `Activity.onRequestPermissionsResult`
 * and nowhere else. A library that wants to *await* the answer therefore needs
 * an activity of its own — the alternatives are androidx's activity-result APIs,
 * which this SDK's one-dependency posture rules out, or asking the customer to
 * forward yet another callback, which is a line every integration would have to
 * get right for `requestPermission()` to ever return.
 *
 * Transparent, animation-free and finished the instant it has an answer, so the
 * person sees the system dialogue over the app they were already looking at.
 *
 * The caller is answered from `onDestroy` rather than from the result callback,
 * deliberately: every way out of this screen has to answer it. A system that
 * decides to show nothing at all — which is what Android 13 does once a person
 * has refused twice — never calls back at all, and a caller suspended for ever
 * is the one failure this shim must not have.
 */
internal class PermissionRequestActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // A recreated instance is one whose request is already in flight; the system
    // re-delivers its own result, and asking again would stack two dialogues.
    if (savedInstanceState != null) return

    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray,
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)

    // The answer is not read here. What was granted is a question for
    // `areNotificationsEnabled()`, which also sees a switch turned off in
    // Settings — and reading it once, in one place, is what keeps the two from
    // ever disagreeing.
    finish()
  }

  override fun onDestroy() {
    super.onDestroy()
    pending.getAndSet(null)?.complete(Unit)
  }

  internal companion object {
    private const val REQUEST_CODE = 0x0CA7

    private val pending = AtomicReference<CompletableDeferred<Unit>?>()

    /** Shows the dialogue and returns when it is gone, whatever was answered. */
    suspend fun show(activity: Activity) {
      val answer = CompletableDeferred<Unit>()

      // One dialogue is what the person sees, so a second caller waits on the
      // first one's answer instead of raising another.
      if (!pending.compareAndSet(null, answer)) {
        pending.get()?.await()

        return
      }

      activity.startActivity(Intent(activity, PermissionRequestActivity::class.java))
      answer.await()
    }
  }
}
