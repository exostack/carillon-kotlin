package dev.carillon.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.carillon.sdk.Carillon
import dev.carillon.sdk.TagValue
import dev.carillon.sdk.tagOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The test bench.
 *
 * A tool, not a product: every control here exists to exercise one call of the
 * SDK against a real server and show what came back. Views are built in code and
 * left plain — anything that looked designed would be a claim about how the SDK
 * should be presented, which is the customer's decision and not ours.
 */
class MainActivity : Activity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  private lateinit var preferences: android.content.SharedPreferences
  private lateinit var endpointField: EditText
  private lateinit var keyField: EditText
  private lateinit var externalIdField: EditText
  private lateinit var tagNameField: EditText
  private lateinit var tagValueField: EditText
  private lateinit var infoView: TextView
  private lateinit var logView: TextView

  private val lines = ArrayList<String>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    preferences = getSharedPreferences(BENCH, Context.MODE_PRIVATE)

    setContentView(buildLayout())
    applyConfiguration()

    // The launching intent, in case this activity was started by a tap. Forwarded
    // before anything else so that the open is queued even on a cold start.
    Carillon.didOpen(intent)
  }

  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    Carillon.didOpen(intent)
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  // Configuration ------------------------------------------------------------

  private fun applyConfiguration() {
    val endpoint = endpointField.text.toString().ifBlank { DEFAULT_ENDPOINT }
    val key = keyField.text.toString()

    preferences.edit().putString(ENDPOINT, endpoint).putString(KEY, key).apply()

    // Behind the same debuggability check as the logging it mirrors, so a
    // release bench would show nothing — which is the point of the check.
    Carillon.onDebugLine = { line -> runOnUiThread { append(line) } }
    Carillon.onOpened = { opened ->
      runOnUiThread {
        append("opened ${opened.deliveryId}")
        refreshInfo()
      }
    }

    Carillon.configure(this, key = key, endpoint = endpoint, debug = true)
    append("configured for $endpoint with ${if (key.isEmpty()) "no key" else key}")
    append("registering silently — nothing is asked; watch device_id appear below")

    // The token comes back from Firebase on a coroutine of the SDK's own, and
    // the registration a moment after it. Re-read on a delay so the panel shows
    // the device the server named rather than the emptiness before it.
    refreshInfo()
    infoView.postDelayed({ refreshInfo() }, 2_000)
  }

  private fun requestPermission() {
    scope.launch {
      // The dialogue is the SDK's to raise now, which is why this hands it an
      // activity. The device is already registered either way.
      val permission = Carillon.requestPermission(this@MainActivity)
      append("requestPermission() → $permission")
      refreshInfo()
    }
  }

  // The bench ----------------------------------------------------------------

  private fun buildLayout(): View {
    val column =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(32, 48, 32, 48)
      }

    column.addView(heading("Connection"))
    endpointField =
      field("Endpoint", preferences.getString(ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT)
    column.addView(endpointField)
    keyField = field("Mobile key", preferences.getString(KEY, "") ?: "", monospace = true)
    column.addView(keyField)
    column.addView(button("Apply") { applyConfiguration() })

    column.addView(heading("Registration"))
    column.addView(
      note("Configure registers this device on its own. This asks whether Android may show anything.")
    )
    column.addView(button("requestPermission()") { requestPermission() })

    column.addView(heading("Identity"))
    externalIdField = field("external_id", "")
    column.addView(externalIdField)
    column.addView(
      button("identify()") {
        val externalId = externalIdField.text.toString()
        if (externalId.isNotEmpty()) {
          Carillon.identify(externalId)
          append("identify($externalId)")
          refreshInfo()
        }
      }
    )
    column.addView(
      button("clearIdentity()") {
        Carillon.clearIdentity()
        append("clearIdentity()")
        refreshInfo()
      }
    )

    column.addView(heading("Tags"))
    tagNameField = field("name", "")
    column.addView(tagNameField)
    tagValueField = field("value", "")
    column.addView(tagValueField)
    // One pair at a time, sent as the whole map. The SDK replaces rather than
    // merges, which the bench shows honestly rather than papering over by
    // accumulating pairs of its own.
    column.addView(
      button("setTags()") {
        val name = tagNameField.text.toString()
        if (name.isNotEmpty()) {
          val tags: Map<String, TagValue> = mapOf(name to tagOf(tagValueField.text.toString()))
          Carillon.setTags(tags)
          append("setTags([$name: ${tagValueField.text}])")
          refreshInfo()
        }
      }
    )
    column.addView(
      button("setTags(empty)") {
        Carillon.setTags(emptyMap())
        append("setTags(empty)")
        refreshInfo()
      }
    )

    column.addView(heading("Opt in"))
    column.addView(
      button("optIn()") {
        Carillon.optIn()
        append("optIn()")
        refreshInfo()
      }
    )
    column.addView(
      button("optOut()") {
        Carillon.optOut()
        append("optOut()")
        refreshInfo()
      }
    )

    column.addView(heading("debugInfo()"))
    column.addView(button("refresh") { refreshInfo() })
    infoView = monospaceView()
    column.addView(infoView)

    column.addView(heading("Log"))
    logView = monospaceView()
    column.addView(logView)

    return ScrollView(this).apply { addView(column, MATCH_PARENT, WRAP_CONTENT) }
  }

  private fun refreshInfo() {
    infoView.text = Carillon.debugInfo().toString()
  }

  /**
   * Newest first: on a bench the interesting line is always the last thing that
   * happened, and scrolling to find it is the one thing a tool must not ask for.
   */
  private fun append(line: String) {
    lines.add(line)
    if (lines.size > 500) lines.removeAt(0)
    logView.text = lines.asReversed().joinToString("\n")
  }

  // Plain views --------------------------------------------------------------

  private fun heading(text: String): TextView =
    TextView(this).apply {
      this.text = text.uppercase()
      setTypeface(typeface, Typeface.BOLD)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      setPadding(0, 40, 0, 8)
      gravity = Gravity.START
    }

  private fun note(text: String): TextView =
    TextView(this).apply {
      this.text = text
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      setPadding(0, 0, 0, 8)
    }

  private fun field(hint: String, value: String, monospace: Boolean = false): EditText =
    EditText(this).apply {
      this.hint = hint
      setText(value)
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
      if (monospace) typeface = Typeface.MONOSPACE
    }

  private fun button(label: String, onClick: () -> Unit): Button =
    Button(this).apply {
      text = label
      setOnClickListener { onClick() }
    }

  private fun monospaceView(): TextView =
    TextView(this).apply {
      typeface = Typeface.MONOSPACE
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
      setTextIsSelectable(true)
    }

  private companion object {
    const val BENCH = "bench"
    const val ENDPOINT = "endpoint"
    const val KEY = "key"
    const val DEFAULT_ENDPOINT = "https://api-staging.carillon.dev"
  }
}
