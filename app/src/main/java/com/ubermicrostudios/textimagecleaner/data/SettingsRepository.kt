package com.ubermicrostudios.textimagecleaner.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Persisted user preferences (DataStore). */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext

    val backupFolderName: Flow<String> = appContext.dataStore.data.map { prefs ->
        sanitizeFolderName(prefs[KEY_BACKUP_FOLDER] ?: DEFAULT_BACKUP_FOLDER)
    }

    suspend fun setBackupFolderName(name: String) {
        val clean = sanitizeFolderName(name)
        appContext.dataStore.edit { it[KEY_BACKUP_FOLDER] = clean }
    }

    suspend fun getBackupFolderNameOnce(): String = backupFolderName.first()

    companion object {
        const val DEFAULT_BACKUP_FOLDER = "TextImageCleaner_Backup"
        private val KEY_BACKUP_FOLDER = stringPreferencesKey("backup_folder_name")

        fun sanitizeFolderName(raw: String): String {
            val cleaned = raw.trim()
                .replace('/', '_')
                .replace('\\', '_')
                .replace(Regex("\\s+"), " ")
            return cleaned.ifBlank { DEFAULT_BACKUP_FOLDER }
        }
    }
}
