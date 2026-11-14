package dev.bilimusic.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.bilimusic.desktop.BiliAccount
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal class AndroidLoginStore(context: Context) {
    private val preferences = context.getSharedPreferences("login", Context.MODE_PRIVATE)
    private val alias = "bilimusic-login"

    fun save(account: BiliAccount) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = JSONObject().put("cookie", account.cookie).put("csrf", account.csrf)
        val encrypted = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        preferences.edit().putString("session", encode(cipher.iv) + "." + encode(encrypted)).apply()
    }

    fun read(): Pair<String, String>? {
        val value = preferences.getString("session", null) ?: return null
        val (iv, encrypted) = value.split('.', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(iv)))
        val payload = JSONObject(cipher.doFinal(decode(encrypted)).toString(Charsets.UTF_8))
        return payload.getString("cookie") to payload.getString("csrf")
    }

    fun clear() { preferences.edit().remove("session").apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    private fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
}
