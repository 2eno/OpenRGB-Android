package dev.eno2.openrgbremote.core.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.eno2.openrgbremote.core.data.db.AppDatabase
import dev.eno2.openrgbremote.core.domain.model.ColorLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Upgrades real databases of every earlier schema (exported to core/data/schemas) to the current
 * one and checks that the stored data survives. Room validates the resulting schema against the
 * one the code expects, so a missing or wrong auto-migration fails here and not on a user's phone.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val name = "migration-test.db"

    @Test
    fun `every schema upgrades to the current one`() {
        for (version in 1 until CURRENT) {
            val file = "v$version-$name"
            helper.createDatabase(file, version).close()
            helper.runMigrationsAndValidate(file, CURRENT, true).close()
        }
    }

    @Test
    fun `servers and profiles survive and old profiles become static`() = runTest {
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO servers (id, name, host, port, auto_connect, created_at) VALUES (1, 'Gaming-PC', '192.168.1.20', 6742, 1, 10)")
            execSQL("INSERT INTO profiles (id, name, colors, layout, brightness, created_at) VALUES (5, 'Desk', 'FF0000,0000FF', 'BLOCKS', 0.5, 20)")
            close()
        }
        helper.runMigrationsAndValidate(name, CURRENT, true).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            assertEquals("192.168.1.20", db.serverDao().observeAll().first().single().host)
            val profile = db.profileDao().get(5)!!.toDomain()
            assertEquals(listOf(0xFF0000, 0x0000FF), profile.colors)
            assertEquals(ColorLayout.BLOCKS, profile.layout)
            assertNull(profile.animation)
            assertEquals(emptyList(), db.scheduleDao().observeAll().first())
        } finally {
            db.close()
        }
    }

    private companion object {
        const val CURRENT = 6
    }
}
