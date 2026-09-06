package com.oai.geminilivetranslate.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiKeyStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("gemini_translate_secrets", Context.MODE_PRIVATE)

    data class State(val keys: List<String>, val selected: String?, val proxyKey: String? = null)

    @Synchronized
    fun load(): State {
        val payload = prefs.getString(PAYLOAD, null) ?: return State(emptyList(), null, null)
        return runCatching {
            val root = JSONObject(decrypt(payload))
            val array = root.optJSONArray("keys") ?: JSONArray()
            val keys = buildList<String> {
                for (i in 0 until array.length()) {
                    val key = array.optString(i).trim()
                    if (key.isNotBlank() && key !in this) add(key)
                }
            }
            val selected = root.optString("selected").takeIf { it in keys } ?: keys.firstOrNull()
            val proxyKey = root.optString("proxyKey").trim().takeIf(String::isNotBlank)
            State(keys, selected, proxyKey)
        }.getOrElse { State(emptyList(), null, null) }
    }

    @Synchronized
    fun setGeminiKeys(values: List<String>): State {
        val normalized = values
            .map(String::trim)
            .filter(String::isNotBlank)
            .onEach(::requireValidGeminiKey)
            .distinct()
        val current = load()
        val selected = current.selected?.takeIf { it in normalized } ?: normalized.firstOrNull()
        return save(current.copy(keys = normalized, selected = selected))
    }


    @Synchronized
    fun currentGeminiKey(): String? {
        val real = realGeminiKey(load())
        return if (
            AiStudioLiveBackendPolicy.configuredToPreferAiStudio(appContext) &&
            selectedOperationUsesGeminiLive()
        ) {
            AiStudioLiveBackendPolicy.liveCredential(real)
        } else {
            real
        }
    }


    @Synchronized
    fun currentLiveCredential(): String? {
        val real = realGeminiKey(load())
        return if (AiStudioLiveBackendPolicy.configuredToPreferAiStudio(appContext)) {
            AiStudioLiveBackendPolicy.liveCredential(real)
        } else {
            real
        }
    }

    @Synchronized
    fun orderedGeminiKeys(): List<String> {
        val current = load()
        if (current.keys.isEmpty()) return emptyList()
        val selected = current.selected?.takeIf { it in current.keys } ?: current.keys.first()
        val start = current.keys.indexOf(selected).coerceAtLeast(0)
        return current.keys.indices.map { offset ->
            current.keys[(start + offset) % current.keys.size]
        }
    }

    @Synchronized
    fun selectNextGeminiKey(after: String? = null): String? {
        val current = load()
        if (current.keys.isEmpty()) return null
        val active = after?.takeIf { it in current.keys }
            ?: current.selected?.takeIf { it in current.keys }
            ?: current.keys.first()
        if (current.keys.size == 1) return active
        val index = current.keys.indexOf(active)
        val next = current.keys[(index + 1) % current.keys.size]
        save(current.copy(selected = next))
        return next
    }

    @Synchronized
    fun selectGeminiKey(key: String): String? {
        val current = load()
        val normalized = key.trim()
        if (normalized !in current.keys) return null
        save(current.copy(selected = normalized))
        return normalized
    }

    @Synchronized
    fun setProxyKey(key: String): State {
        val normalized = key.trim()
        require(normalized.isBlank() || normalized.none(Char::isWhitespace)) { "API Key Proxy không hợp lệ" }
        return save(load().copy(proxyKey = normalized.takeIf(String::isNotBlank)))
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().commit()
        runCatching {
            val keyStore = keyStore()
            if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
        }
        invalidateLogRedactionCache()
    }

    private fun selectedOperationUsesGeminiLive(): Boolean {
        val appPreferences = AppPreferences(appContext)
        return when (appPreferences.loadProcessingMode()) {
            AppPreferences.PROCESSING_MODE_VIDEO_DESCRIPTION -> false
            AppPreferences.PROCESSING_MODE_TRANSCRIBE -> {
                val selectedSource = appContext
                    .getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_LAST_SOURCE_MODE, SourceMode.FILE.name)
                    .orEmpty()
                selectedSource != SourceMode.FILE.name
            }
            else -> true
        }
    }

    private fun realGeminiKey(current: State): String? =
        current.selected?.takeIf { it in current.keys } ?: current.keys.firstOrNull()

    private fun requireValidGeminiKey(key: String) {
        require(key.length >= 20 && key.none(Char::isWhitespace)) { "API Key không hợp lệ" }
    }

    private fun save(state: State): State {
        val root = JSONObject().put("keys", JSONArray(state.keys)).put("selected", state.selected ?: "").put("proxyKey", state.proxyKey ?: "")
        prefs.edit().putString(PAYLOAD, encrypt(root.toString())).commit()
        invalidateLogRedactionCache()
        return state
    }

    private fun invalidateLogRedactionCache() {
        runCatching { AppLogRepository.get(appContext).invalidateSecrets() }
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8))
        return JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .toString()
    }

    private fun decrypt(payload: String): String {
        val root = JSONObject(payload)
        val iv = Base64.decode(root.getString("iv"), Base64.NO_WRAP)
        val data = Base64.decode(root.getString("data"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data), StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        private const val ALIAS = "gemini_translate_api_keys_v1"
        private const val PAYLOAD = "payload"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_LAST_SOURCE_MODE = "lastSourceMode"
    }
}
