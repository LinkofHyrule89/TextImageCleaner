package com.ubermicrostudios.textimagecleaner

import com.ubermicrostudios.textimagecleaner.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryTest {

    @Test
    fun sanitizeRejectsSlashesAndBlank() {
        assertEquals(
            SettingsRepository.DEFAULT_BACKUP_FOLDER,
            SettingsRepository.sanitizeFolderName("   ")
        )
        assertEquals(
            "My_Album",
            SettingsRepository.sanitizeFolderName("My/Album")
        )
        assertEquals(
            "My_Album",
            SettingsRepository.sanitizeFolderName("My\\Album")
        )
        assertEquals(
            "Family Pics",
            SettingsRepository.sanitizeFolderName("  Family Pics  ")
        )
    }
}
