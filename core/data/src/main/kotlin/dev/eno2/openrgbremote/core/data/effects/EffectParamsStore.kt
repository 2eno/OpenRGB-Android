package dev.eno2.openrgbremote.core.data.effects

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.eno2.openrgbremote.core.effects.EffectId
import dev.eno2.openrgbremote.core.effects.EffectParams
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject

/** Remembers each effect's last parameters across app restarts. */
interface EffectParamsStore {
    suspend fun load(): Map<EffectId, EffectParams>
    suspend fun save(effect: EffectId, params: EffectParams)

    /** Keeps nothing; for tests. */
    object None : EffectParamsStore {
        override suspend fun load(): Map<EffectId, EffectParams> = emptyMap()
        override suspend fun save(effect: EffectId, params: EffectParams) = Unit
    }

    companion object {
        /** `speed;reverse;brightness;RRGGBB,RRGGBB` – compact and readable in a backup. */
        fun encode(params: EffectParams): String =
            "${params.speed};${params.reverse};${params.brightness};${params.colors.joinToString(",") { "%06X".format(it and 0xFFFFFF) }}"

        /** Null for anything malformed, so a corrupt entry falls back to the defaults. */
        fun decode(text: String): EffectParams? {
            val parts = text.split(';')
            if (parts.size != 4) return null
            val speed = parts[0].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
            val reverse = parts[1].toBooleanStrictOrNull() ?: return null
            val brightness = parts[2].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
            val colors = if (parts[3].isEmpty()) emptyList() else parts[3].split(',').map { it.toIntOrNull(16) ?: return null }
            return EffectParams(
                speed = speed.coerceIn(EffectParams.MIN_SPEED, EffectParams.MAX_SPEED),
                reverse = reverse,
                colors = colors.take(EffectParams.MAX_COLORS),
                brightness = brightness.coerceIn(0f, 1f),
            )
        }
    }
}

class DataStoreEffectParamsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : EffectParamsStore {

    // Remembered settings are a convenience: an unreadable or full storage must not crash the app,
    // the effects then simply start with their defaults.
    override suspend fun load(): Map<EffectId, EffectParams> = try {
        val prefs = dataStore.data.first()
        EffectId.entries.mapNotNull { effect -> prefs[key(effect)]?.let(EffectParamsStore::decode)?.let { effect to it } }.toMap()
    } catch (_: IOException) {
        emptyMap()
    }

    override suspend fun save(effect: EffectId, params: EffectParams) {
        try {
            dataStore.edit { it[key(effect)] = EffectParamsStore.encode(params) }
        } catch (_: IOException) {
            // Kept in memory for this session.
        }
    }

    private fun key(effect: EffectId) = stringPreferencesKey("effect_params_${effect.name}")
}
