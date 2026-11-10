package dev.eno2.openrgbremote.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.eno2.openrgbremote.core.domain.model.QuickAction
import dev.eno2.openrgbremote.core.domain.repository.QuickActionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class DataStoreQuickActionRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : QuickActionRepository {

    override fun observeSlots(): Flow<List<QuickAction?>> = dataStore.data
        .map { prefs -> List(QuickAction.SLOTS) { QuickAction.decode(prefs[key(it)]) } }
        .distinctUntilChanged()

    override suspend fun setSlot(index: Int, action: QuickAction?) {
        require(index in 0 until QuickAction.SLOTS) { "No slot $index" }
        dataStore.edit { prefs -> if (action == null) prefs.remove(key(index)) else prefs[key(index)] = action.encode() }
    }

    private fun key(index: Int) = stringPreferencesKey("quick_action_$index")
}
