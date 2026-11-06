package dev.harness.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Stores secrets (the API key) in EncryptedSharedPreferences. */
class SecurePrefs(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        try {
            EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // A keystore reset (e.g. after restoring a backup) makes the old file unreadable.
            context.deleteSharedPreferences(FILE_NAME)
            EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    var apiKey: String
        get() = prefs.getString(KEY_API, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_API, value).apply()
        }

    companion object {
        private const val FILE_NAME = "farai_secure_prefs"
        private const val KEY_API = "provider.api_key"
    }
}
