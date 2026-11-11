package dev.eno2.openrgbremote

import dev.eno2.openrgbremote.core.domain.model.ProfileAnimation
import dev.eno2.openrgbremote.core.domain.model.QuickAction
import dev.eno2.openrgbremote.core.domain.model.Schedule
import dev.eno2.openrgbremote.feature.quick.QuickActionsScreen
import dev.eno2.openrgbremote.feature.quick.QuickActionsUiState
import dev.eno2.openrgbremote.feature.quick.SchedulesScreen
import dev.eno2.openrgbremote.feature.quick.SchedulesUiState
import dev.eno2.openrgbremote.feature.quick.ScheduleEditScreen
import dev.eno2.openrgbremote.feature.quick.ScheduleEditUiState
import dev.eno2.openrgbremote.feature.quick.ScheduleEditActions
import java.time.DayOfWeek
import dev.eno2.openrgbremote.feature.settings.SettingsScreen
import java.time.LocalTime
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import dev.eno2.openrgbremote.core.designsystem.theme.OpenRgbTheme
import dev.eno2.openrgbremote.core.domain.model.ConnectionProblem
import dev.eno2.openrgbremote.core.domain.model.ConnectionStatus
import dev.eno2.openrgbremote.core.domain.model.ConnectionTestResult
import dev.eno2.openrgbremote.core.domain.model.DiscoveredServer
import dev.eno2.openrgbremote.core.domain.model.RemoteDevice
import dev.eno2.openrgbremote.core.domain.model.Server
import dev.eno2.openrgbremote.core.domain.model.ServerOverview
import dev.eno2.openrgbremote.core.domain.usecase.ScanResultItem
import dev.eno2.openrgbremote.core.domain.usecase.ScanState
import dev.eno2.openrgbremote.feature.color.ColorScreen
import dev.eno2.openrgbremote.feature.color.ColorActions
import dev.eno2.openrgbremote.feature.color.ColorUiState
import dev.eno2.openrgbremote.feature.color.TargetDevice
import dev.eno2.openrgbremote.feature.color.TargetZone
import dev.eno2.openrgbremote.feature.devicedetail.DetailActions
import dev.eno2.openrgbremote.feature.devicedetail.DeviceDetailScreen
import dev.eno2.openrgbremote.core.domain.model.DeviceKey
import dev.eno2.openrgbremote.core.domain.model.HsvColor
import dev.eno2.openrgbremote.sdk.model.ColorMode
import dev.eno2.openrgbremote.sdk.model.DeviceMode
import dev.eno2.openrgbremote.sdk.model.ModeFlags
import dev.eno2.openrgbremote.feature.devicedetail.DeviceDetailUiState
import dev.eno2.openrgbremote.feature.devices.DevicesActions
import dev.eno2.openrgbremote.feature.devices.DevicesScreen
import dev.eno2.openrgbremote.feature.devices.DevicesUiState
import dev.eno2.openrgbremote.feature.onboarding.OnboardingActions
import dev.eno2.openrgbremote.feature.effects.EffectCard
import dev.eno2.openrgbremote.feature.effects.EffectSettingsActions
import dev.eno2.openrgbremote.feature.effects.EffectSettingsScreen
import dev.eno2.openrgbremote.feature.effects.EffectSettingsUiState
import dev.eno2.openrgbremote.feature.effects.EffectsScreen
import dev.eno2.openrgbremote.feature.effects.EffectsUiState
import dev.eno2.openrgbremote.feature.effects.LocalAnimateEffectPreviews
import dev.eno2.openrgbremote.feature.effects.MicrophoneState
import dev.eno2.openrgbremote.core.domain.model.EffectConfig
import dev.eno2.openrgbremote.core.domain.model.EffectState
import dev.eno2.openrgbremote.core.effects.EffectId
import dev.eno2.openrgbremote.core.effects.EffectParams
import dev.eno2.openrgbremote.core.effects.LoopStats
import dev.eno2.openrgbremote.feature.profiles.ProfileEditActions
import dev.eno2.openrgbremote.feature.profiles.ProfileEditScreen
import dev.eno2.openrgbremote.feature.profiles.ProfileEditUiState
import dev.eno2.openrgbremote.feature.profiles.ProfilesActions
import dev.eno2.openrgbremote.feature.profiles.ProfilesScreen
import dev.eno2.openrgbremote.feature.profiles.ProfilesUiState
import dev.eno2.openrgbremote.core.domain.model.BuiltInProfiles
import dev.eno2.openrgbremote.core.domain.model.ColorLayout
import dev.eno2.openrgbremote.core.domain.model.Profile
import dev.eno2.openrgbremote.feature.onboarding.OnboardingScreen
import dev.eno2.openrgbremote.feature.servers.ServerEditScreen
import dev.eno2.openrgbremote.feature.servers.ServerEditUiState
import dev.eno2.openrgbremote.feature.servers.ServerFormActions
import dev.eno2.openrgbremote.feature.servers.TestState
import dev.eno2.openrgbremote.sdk.model.ColorArray
import dev.eno2.openrgbremote.sdk.model.OpenRgbDevice
import dev.eno2.openrgbremote.sdk.testing.RealDevices
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the main screens to PNG (app/build/screenshots) with Robolectric's native graphics.
 * Each test also proves that the screen composes without crashing for realistic data: the
 * devices are decoded from packets of a real OpenRGB 1.0 server. Debug devices report all LEDs
 * as black, so demo colors are applied for readability.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun capture(name: String, qualifiers: String = "", dark: Boolean = false, content: @Composable () -> Unit) {
        if (qualifiers.isNotEmpty()) RuntimeEnvironment.setQualifiers(qualifiers)
        compose.setContent {
            OpenRgbTheme(darkTheme = dark, dynamicColor = false) {
                CompositionLocalProvider(LocalAnimateEffectPreviews provides false) {
                    Surface(Modifier.fillMaxSize()) { content() }
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // --------------------------------------------------------------------------------- data

    private val palette = intArrayOf(0xFF2D55, 0xFF9500, 0xFFD60A, 0x34C759, 0x00C7BE, 0x0A84FF, 0x5E5CE6, 0xBF5AF2)

    private fun rainbow(count: Int, offset: Int = 0) = IntArray(count) { i -> palette[(i * palette.size / count.coerceAtLeast(1) + offset) % palette.size] }

    private fun demoDevices(): List<OpenRgbDevice> = RealDevices.all().mapIndexed { index, device ->
        val colors = when (index) {
            0 -> rainbow(device.ledCount)
            2 -> IntArray(device.ledCount) { 0x00C7BE }
            else -> rainbow(device.ledCount, index)
        }
        device.copy(colors = ColorArray.of(colors))
    }

    private val gamingPc = Server(1, "Gaming-PC", "192.168.1.20")
    private val livingRoom = Server(2, "Wohnzimmer-PC", "192.168.1.35")

    private fun overviews() = listOf(
        ServerOverview(gamingPc, ConnectionStatus.Connected(6, "OpenRGB 1.0", "gaming-pc"), demoDevices().map { RemoteDevice(1, it) }),
        ServerOverview(livingRoom, ConnectionStatus.Reconnecting(3, 8_000, ConnectionProblem.CONNECTION_REFUSED, "Connection refused"), emptyList()),
    )

    // -------------------------------------------------------------------------------- tests

    @Test
    fun devices_phone_de() = capture("devices_phone_de", "+de") {
        DevicesScreen(DevicesUiState(loading = false, servers = overviews()), DevicesActions())
    }

    @Test
    fun devices_effect_running_de() = capture("devices_effect_running_de", "+de") {
        val running = EffectState.Running(EffectConfig(EffectId.AURORA), LoopStats(targetFps = 30, fps = 29, frameMillis = 0.4f, targets = 4), deviceCount = 4)
        DevicesScreen(DevicesUiState(loading = false, servers = overviews().take(1), runningEffect = running), DevicesActions())
    }

    @Test
    fun devices_phone_en_dark() = capture("devices_phone_en_dark", "+en-night", dark = true) {
        DevicesScreen(DevicesUiState(loading = false, servers = overviews().reversed()), DevicesActions())
    }

    @Test
    fun devices_tablet_de() = capture("devices_tablet_de", "de-w1280dp-h800dp-land-xhdpi") {
        NavigationSuiteScaffold(navigationSuiteItems = {
            item(selected = true, onClick = {}, icon = { Icon(Icons.Outlined.Lightbulb, null) }, label = { Text("Geräte") })
            item(selected = false, onClick = {}, icon = { Icon(Icons.Outlined.Settings, null) }, label = { Text("Einstellungen") })
        }) {
            DevicesScreen(DevicesUiState(loading = false, servers = overviews()), DevicesActions())
        }
    }

    @Test
    fun devices_empty_de() = capture("devices_empty_de", "+de") {
        DevicesScreen(DevicesUiState(loading = false), DevicesActions())
    }

    @Test
    fun device_detail_keyboard_de() = capture("device_detail_keyboard_de", "de-w411dp-h1500dp-xxhdpi") {
        DeviceDetailScreen(
            DeviceDetailUiState(loading = false, device = demoDevices()[0], serverName = "Gaming-PC", status = overviews()[0].status),
            DetailActions(),
        )
    }

    @Test
    fun device_detail_unavailable_en() = capture("device_detail_unavailable_en", "+en") {
        DeviceDetailScreen(DeviceDetailUiState(loading = false, keyName = "Debug Keyboard", status = overviews()[1].status), DetailActions())
    }

    @Test
    fun onboarding_welcome_de() = capture("onboarding_1_welcome_de", "+de") {
        OnboardingScreen(step = 0, securityAccepted = true, scanState = ScanState(), actions = OnboardingActions())
    }

    @Test
    fun onboarding_server_de() = capture("onboarding_2_server_de", "+de") {
        OnboardingScreen(step = 1, securityAccepted = true, scanState = ScanState(), actions = OnboardingActions())
    }

    @Test
    fun onboarding_connect_de() = capture("onboarding_3_connect_de", "+de") {
        val scan = ScanState(
            running = true,
            network = "192.168.1.0/24",
            scanned = 180,
            total = 253,
            found = listOf(
                ScanResultItem(DiscoveredServer("192.168.1.20", 6742, 6, "OpenRGB 1.0", "gaming-pc"), alreadySaved = false),
                ScanResultItem(DiscoveredServer("192.168.1.35", 6742, 4, null, null), alreadySaved = true),
                ScanResultItem(DiscoveredServer("192.168.1.77", 6742, null, null, null), alreadySaved = false),
            ),
        )
        OnboardingScreen(step = 2, securityAccepted = true, scanState = scan, actions = OnboardingActions())
    }

    @Test
    fun server_edit_failure_de() = capture("server_edit_failure_de", "+de") {
        ServerEditScreen(
            ServerEditUiState(name = "", host = "192.168.1.35", test = TestState.Failure(ConnectionProblem.CONNECTION_REFUSED, "refused")),
            ServerFormActions(),
            onBack = {},
        )
    }

    @Test
    fun server_edit_success_en_dark() = capture("server_edit_success_en_dark", "+en-night", dark = true) {
        ServerEditScreen(
            ServerEditUiState(name = "gaming-pc", host = "192.168.1.20", test = TestState.Success(ConnectionTestResult.Success(6, "OpenRGB 1.0", "gaming-pc", 6))),
            ServerFormActions(),
            onBack = {},
        )
    }

    private fun colorState(): ColorUiState {
        val devices = demoDevices()
        return ColorUiState(
            loading = false,
            color = HsvColor.fromRgb(0xFF6A00),
            devices = devices.take(4).mapIndexed { i, d -> TargetDevice(DeviceKey.of(1, d), d.label, d.type, selected = i != 1, livePreview = i != 3) },
            favorites = listOf(0xFF6A00, 0x00C7BE, 0x5E5CE6, 0xFF2D55),
            canUndo = true,
        )
    }

    @Test
    fun color_all_de() = capture("color_all_de", "de-w411dp-h1250dp-xxhdpi") { ColorScreen(colorState(), ColorActions()) }

    @Test
    fun color_zone_en_dark() = capture("color_zone_en_dark", "en-w411dp-h1250dp-night-xxhdpi", dark = true) {
        val keyboard = demoDevices()[0]
        ColorScreen(
            ColorUiState(
                loading = false,
                color = HsvColor(265f, 0.7f, 0.9f),
                devices = listOf(TargetDevice(DeviceKey.of(1, keyboard), keyboard.label, keyboard.type, true, true)),
                zones = keyboard.zones.mapIndexed { i, z -> TargetZone(i, z.label, i == 2 || i == 3) },
                singleDeviceLabel = keyboard.label,
                livePreview = false,
            ),
            ColorActions(),
        )
    }

    @Test
    fun color_tablet_de() = capture("color_tablet_de", "de-w1280dp-h800dp-land-xhdpi") { ColorScreen(colorState(), ColorActions()) }

    @Test
    fun device_detail_modes_de() = capture("device_detail_modes_de", "de-w411dp-h1100dp-xxhdpi") {
        val wave = DeviceMode(
            name = "Rainbow Wave", value = 3,
            flags = ModeFlags.HAS_SPEED or ModeFlags.HAS_BRIGHTNESS or ModeFlags.HAS_DIRECTION_LR or ModeFlags.HAS_MODE_SPECIFIC_COLOR or ModeFlags.HAS_RANDOM_COLOR or ModeFlags.MANUAL_SAVE,
            speedMin = 10, speedMax = 1, speed = 4, brightnessMin = 0, brightnessMax = 100, brightness = 80,
            colorsMin = 1, colorsMax = 4, colorMode = ColorMode.MODE_SPECIFIC,
            colors = ColorArray.of(intArrayOf(0xFF2D55, 0x0A84FF, 0x34C759)),
        )
        val base = demoDevices()[2]
        val device = base.copy(modes = base.modes + listOf(DeviceMode("Static", flags = ModeFlags.HAS_MODE_SPECIFIC_COLOR), wave, DeviceMode("Breathing")), activeModeIndex = 2)
        DeviceDetailScreen(DeviceDetailUiState(loading = false, device = device, serverName = "Gaming-PC", status = overviews()[0].status), DetailActions())
    }

    private val customProfiles = listOf(
        Profile(1, "Schreibtisch Abend", listOf(0xFF6A00, 0xFF2D55, 0x5E5CE6), ColorLayout.GRADIENT),
        Profile(2, "Team-Farben", listOf(0x0A84FF, 0xFFFFFF, 0xFF3B30), ColorLayout.BLOCKS),
        Profile(3, "Bonbon", listOf(0xFF2D55, 0x34C759, 0xFFD60A, 0x0A84FF), ColorLayout.ALTERNATING, brightness = 0.8f),
        Profile(4, "Lagerfeuer", EffectId.FIRE.defaultColors, animation = ProfileAnimation(EffectId.FIRE, 1.2f)),
        Profile(5, "Polarlicht", listOf(0x00FF7F, 0x00C8C8, 0x7A3CFF), animation = ProfileAnimation(EffectId.AURORA, 0.8f, reverse = true)),
    )

    @Test
    fun profile_edit_dynamic_de() = capture("profile_edit_dynamic_de", "de-w411dp-h1100dp-xxhdpi") {
        ProfileEditScreen(ProfileEditUiState(loading = false, profile = customProfiles[3]), ProfileEditActions())
    }

    @Test
    fun profiles_de() = capture("profiles_de", "de-w411dp-h1250dp-xxhdpi") {
        ProfilesScreen(ProfilesUiState(loading = false, builtIn = BuiltInProfiles.all, custom = customProfiles), ProfilesActions())
    }

    @Test
    fun profiles_tablet_en_dark() = capture("profiles_tablet_en_dark", "en-w1280dp-h800dp-land-night-xhdpi", dark = true) {
        ProfilesScreen(ProfilesUiState(loading = false, builtIn = BuiltInProfiles.all, custom = customProfiles), ProfilesActions())
    }

    @Test
    fun profile_edit_de() = capture("profile_edit_de", "+de") {
        ProfileEditScreen(ProfileEditUiState(loading = false, profile = customProfiles[0]), ProfileEditActions())
    }

    private fun effectsState(running: EffectState.Running?) = EffectsUiState(
        running = running,
        cards = EffectId.entries.map { EffectCard(it, EffectParams(), running?.config?.effect == it) },
    )

    @Test
    fun effects_de() = capture("effects_de", "de-w411dp-h1250dp-xxhdpi") {
        val running = EffectState.Running(EffectConfig(EffectId.RAINBOW_WAVE), LoopStats(targetFps = 30, fps = 30, frameMillis = 0.4f, targets = 4), deviceCount = 4)
        EffectsScreen(effectsState(running), onOpen = {}, onStop = {}, onOpenDevices = {})
    }

    @Test
    fun effects_tablet_en_dark() = capture("effects_tablet_en_dark", "en-w1280dp-h800dp-land-night-xhdpi", dark = true) {
        EffectsScreen(effectsState(null), onOpen = {}, onStop = {}, onOpenDevices = {})
    }

    @Test
    fun effect_settings_de() = capture("effect_settings_de", "de-w411dp-h1100dp-xxhdpi") {
        val devices = demoDevices().take(4).mapIndexed { i, d -> TargetDevice(DeviceKey.of(1, d), d.label, d.type, i != 1, true) }
        EffectSettingsScreen(
            EffectSettingsUiState(EffectConfig(EffectId.AURORA, EffectParams(speed = 0.8f)), running = true, devices = devices, loading = false),
            MicrophoneState.NOT_NEEDED,
            EffectSettingsActions(),
        )
    }

    @Test
    fun effect_music_en() = capture("effect_music_en", "en-w411dp-h1000dp-xxhdpi") {
        val devices = demoDevices().take(2).map { d -> TargetDevice(DeviceKey.of(1, d), d.label, d.type, true, true) }
        EffectSettingsScreen(
            EffectSettingsUiState(EffectConfig(EffectId.MUSIC), devices = devices, loading = false),
            MicrophoneState.NEEDS_PERMISSION,
            EffectSettingsActions(),
        )
    }

    private val allProfiles get() = customProfiles + BuiltInProfiles.all

    private val slots = listOf(
        QuickAction.ApplyProfile(BuiltInProfiles.all.first { it.presetKey == "ocean" }.id),
        QuickAction.StartEffect(EffectId.RAINBOW_WAVE),
        QuickAction.Off,
        null,
    )

    @Test
    fun quick_actions_de() = capture("quick_actions_de", "de-w411dp-h1000dp-xxhdpi") {
        QuickActionsScreen(QuickActionsUiState(slots, allProfiles), onBack = {}, onSetSlot = { _, _ -> })
    }

    /** Accessibility check: the largest system font scale must not clip or overlap. */
    @Test
    fun quick_actions_en_large_font() {
        RuntimeEnvironment.setFontScale(2f)
        capture("quick_actions_en_large_font", "en-w411dp-h1400dp-xxhdpi") {
            QuickActionsScreen(QuickActionsUiState(slots, allProfiles), onBack = {}, onSetSlot = { _, _ -> })
        }
    }

    private val schedules = listOf(
        Schedule(1, LocalTime.of(7, 0), DayOfWeek.entries.take(5).toSet(), QuickAction.ApplyProfile(BuiltInProfiles.all.first { it.presetKey == "warm_white" }.id)),
        Schedule(2, LocalTime.of(23, 30), DayOfWeek.entries.toSet(), QuickAction.Off),
        Schedule(3, LocalTime.of(10, 0), setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), QuickAction.ApplyProfile(1), enabled = false),
    )

    @Test
    fun schedules_de() = capture("schedules_de", "+de") {
        SchedulesScreen(SchedulesUiState(false, schedules, allProfiles), onBack = {}, onEdit = {}, onEnabled = { _, _ -> })
    }

    @Test
    fun schedule_edit_de() = capture("schedule_edit_de", "+de") {
        ScheduleEditScreen(ScheduleEditUiState(schedules[0], allProfiles), ScheduleEditActions())
    }

    @Test
    fun schedule_edit_en_dark() = capture("schedule_edit_en_dark", "en-w411dp-h891dp-night-xxhdpi", dark = true) {
        ScheduleEditScreen(ScheduleEditUiState(schedules[1].copy(days = emptySet()), allProfiles, daysError = true), ScheduleEditActions())
    }

    @Test
    fun settings_de() = capture("settings_de", "de-w411dp-h1000dp-xxhdpi") {
        SettingsScreen(serverCount = 2, onOpenServers = {}, onShowOnboarding = {})
    }
}
