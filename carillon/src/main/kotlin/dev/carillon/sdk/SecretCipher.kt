package dev.carillon.sdk

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the installation secret before it reaches storage.
 *
 * A seam rather than a direct Keystore call because a JVM test has no Keystore:
 * the store's behaviour around a secret that cannot be opened is what needs
 * proving, and it is provable with a cipher that refuses on demand.
 */
internal interface SecretCipher {
  /** Null when the device cannot seal anything, in which case nothing is stored. */
  fun seal(secret: String): String?

  /** Null when the ciphertext was not produced by this device's key. */
  fun open(sealed: String): String?
}

/**
 * AES/GCM under a key that lives in the Android Keystore and never leaves it.
 *
 * Auto Backup copies the app's preferences onto a new device; it cannot copy a
 * Keystore key. Ciphertext restored elsewhere therefore opens to nothing, the
 * engine generates a fresh secret, and the restored device gets an identity of
 * its own instead of inheriting one it cannot prove.
 */
internal class KeystoreSecretCipher : SecretCipher {
  override fun seal(secret: String): String? =
    runCatching {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, key(create = true) ?: return null)
      val sealed = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))

      Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
    }.getOrNull()

  override fun open(sealed: String): String? =
    runCatching {
      val bytes = Base64.decode(sealed, Base64.NO_WRAP)
      if (bytes.size <= IV_BYTES) return null
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(
        Cipher.DECRYPT_MODE,
        key(create = false) ?: return null,
        GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES),
      )

      String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

  private fun key(create: Boolean): SecretKey? {
    val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
    (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
    if (!create) return null

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
    generator.init(
      KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )

    return generator.generateKey()
  }

  private companion object {
    const val PROVIDER = "AndroidKeyStore"
    const val ALIAS = "dev.carillon.sdk.installation_secret"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_BYTES = 12
    const val TAG_BITS = 128
  }
}
