package dev.eno2.openrgbremote.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dev.eno2.openrgbremote.core.domain.model.AppSettings
import dev.eno2.openrgbremote.core.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            onboardingCompleted = prefs[ONBOARDING_COMPLETED] ?: false,
            securityNoticeAccepted = prefs[SECURITY_NOTICE_ACCEPTED] ?: false,
        )
    }.distinctUntilChanged()

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[ONBOARDING_COMPLETED] = completed }
    }

    override suspend fun setSecurityNoticeAccepted(accepted: Boolean) {
        dataStore.edit { it[SECURITY_NOTICE_ACCEPTED] = accepted }
    }

    private companion object {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val SECURITY_NOTICE_ACCEPTED = booleanPreferencesKey("security_notice_accepted")
    }
}
