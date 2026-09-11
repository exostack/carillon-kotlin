package dev.carillon.sdk

import android.content.Context
import android.content.SharedPreferences
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reverses the secret so a test can see the sealed form is not the plain one,
 * and refuses anything it did not seal — which is what a Keystore key that stayed
 * on another device does to restored ciphertext.
 */
private class ReversingCipher : SecretCipher {
  override fun seal(secret: String): String = "sealed:" + secret.reversed()

  override fun open(sealed: String): String? =
    if (sealed.startsWith("sealed:")) sealed.removePrefix("sealed:").reversed() else null
}

private object RefusingCipher : SecretCipher {
  override fun seal(secret: String): String? = null

  override fun open(sealed: String): String? = null
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SecretStorageTest {
  private val preferences: SharedPreferences
    get() = RuntimeEnvironment.getApplication().getSharedPreferences("test", Context.MODE_PRIVATE)

  private fun store(cipher: SecretCipher = ReversingCipher()) = SharedPreferencesStore(preferences, cipher)

  @Test
  fun aFreshInstallGeneratesASecretAndTheNextLaunchReusesIt() {
    makeEngine(store = store())
    val secret = store().installationSecret

    assertEquals(43, secret?.length)
    makeEngine(store = store())
    assertEquals(secret, store().installationSecret)
  }

  @Test
  fun onlyTheSealedFormReachesThePreferences() {
    store().installationSecret = "plain-secret"

    assertEquals("sealed:terces-nialp", preferences.getString("installation_secret_sealed", null))
    assertNull(preferences.getString("installation_secret", null))
    assertFalse(preferences.all.values.any { it == "plain-secret" })
  }

  @Test
  fun ciphertextThatCannotBeOpenedYieldsANewSecretAndForgetsTheRestoredIdentity() {
    preferences.edit()
      .putString("installation_secret_sealed", "sealed-by-another-device")
      .putString("device_id", "01937b1e-0000-7000-8000-000000000001")
      .putString("fingerprint", "stale")
      .putString("state", DeviceState(token = "another-device-token", externalId = "user-42").stored())
      .apply()

    val restored = store()
    assertNull(restored.installationSecret)

    val engine = makeEngine(store = restored)
    val secret = restored.installationSecret

    assertEquals(43, secret?.length)
    assertNotEquals("sealed-by-another-device", preferences.getString("installation_secret_sealed", null))
    assertNull(restored.deviceId)
    assertNull(restored.registeredFingerprint)
    assertNull(engine.currentState.token)
    assertEquals("user-42", engine.currentState.externalId)
    assertNull(engine.debugInfo().deviceId)
  }

  @Test
  fun aPlaintextSecretFromAnEarlierVersionIsSealedOnceAndKept() {
    preferences.edit()
      .putString("installation_secret", "legacy-secret")
      .putString("device_id", "01937b1e-0000-7000-8000-000000000001")
      .apply()

    val migrated = store()

    assertEquals("legacy-secret", migrated.installationSecret)
    assertNull(preferences.getString("installation_secret", null))
    assertEquals("sealed:terces-ycagel", preferences.getString("installation_secret_sealed", null))
    assertEquals("01937b1e-0000-7000-8000-000000000001", migrated.deviceId)

    val engine = makeEngine(store = store())
    assertEquals("legacy-secret", store().installationSecret)
    assertEquals("01937b1e-0000-7000-8000-000000000001", engine.debugInfo().deviceId)
  }

  @Test
  fun aDeviceThatCannotSealKeepsTheSecretInMemoryOnly() {
    val unsealable = store(RefusingCipher)
    makeEngine(store = unsealable)

    assertEquals(43, unsealable.installationSecret?.length)
    assertTrue(preferences.all.isEmpty() || preferences.all.keys.none { it.startsWith("installation_secret") })
  }

  @Test
  fun theKeystoreCipherFailsClosedWhereThereIsNoKeystore() {
    val cipher = KeystoreSecretCipher()

    assertNull(cipher.seal("secret"))
    assertNull(cipher.open("anything"))
  }
}
