package io.github.openrgbremote

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.openrgbremote.core.designsystem.theme.OpenRgbTheme
import io.github.openrgbremote.core.domain.model.ConnectionProblem
import io.github.openrgbremote.core.domain.model.ConnectionStatus
import io.github.openrgbremote.core.domain.model.ConnectionTestResult
import io.github.openrgbremote.core.domain.model.DiscoveredServer
import io.github.openrgbremote.core.domain.model.RemoteDevice
import io.github.openrgbremote.core.domain.model.Server
import io.github.openrgbremote.core.domain.model.ServerOverview
import io.github.openrgbremote.core.domain.usecase.ScanResultItem
import io.github.openrgbremote.core.domain.usecase.ScanState
import io.github.openrgbremote.feature.color.ColorScreen
import io.github.openrgbremote.feature.color.ColorActions
import io.github.openrgbremote.feature.color.ColorUiState
import io.github.openrgbremote.feature.color.TargetDevice
import io.github.openrgbremote.feature.color.TargetZone
import io.github.openrgbremote.feature.devicedetail.DetailActions
import io.github.openrgbremote.feature.devicedetail.DeviceDetailScreen
import io.github.openrgbremote.core.domain.model.DeviceKey
import io.github.openrgbremote.core.domain.model.HsvColor
import io.github.openrgbremote.sdk.model.ColorMode
import io.github.openrgbremote.sdk.model.DeviceMode
import io.github.openrgbremote.sdk.model.ModeFlags
import io.github.openrgbremote.feature.devicedetail.DeviceDetailUiState
import io.github.openrgbremote.feature.devices.DevicesActions
import io.github.openrgbremote.feature.devices.DevicesScreen
import io.github.openrgbremote.feature.devices.DevicesUiState
import io.github.openrgbremote.feature.onboarding.OnboardingActions
import io.github.openrgbremote.feature.onboarding.OnboardingScreen
import io.github.openrgbremote.feature.servers.ServerEditScreen
import io.github.openrgbremote.feature.servers.ServerEditUiState
import io.github.openrgbremote.feature.servers.ServerFormActions
import io.github.openrgbremote.feature.servers.TestState
import io.github.openrgbremote.sdk.model.ColorArray
import io.github.openrgbremote.sdk.model.OpenRgbDevice
import io.github.openrgbremote.sdk.testing.RealDevices
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
                Surface(Modifier.fillMaxSize()) { content() }
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
}
