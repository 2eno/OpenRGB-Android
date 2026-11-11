package dev.eno2.openrgbremote.quick

import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The widget layout with real Glance rendering: four buttons, names, setup hint for empty slots. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de")
class QuickActionsWidgetTest {

    @Test
    fun `shows the four favorites and a setup hint for empty slots`() = runGlanceAppWidgetUnitTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val slots = listOf(
            WidgetSlot(0, QuickActionLabel("Ozean", listOf(0x001A66, 0x00FFC8)), running = false),
            WidgetSlot(1, QuickActionLabel("Feuer", listOf(0xFF2000)), running = true),
            WidgetSlot(2, QuickActionLabel("Alles aus", listOf(0)), running = false),
            WidgetSlot(3, null, running = false),
        )
        provideComposable { GlanceTheme { QuickActionsWidgetContent(context, slots, "Einrichten") } }

        onNode(hasText("Ozean")).assertExists()
        onNode(hasText("Feuer")).assertExists()
        onNode(hasText("Alles aus")).assertExists()
        onNode(hasText("Einrichten")).assertExists()
        onNode(hasContentDescription("Feuer")).assertExists()
    }
}
