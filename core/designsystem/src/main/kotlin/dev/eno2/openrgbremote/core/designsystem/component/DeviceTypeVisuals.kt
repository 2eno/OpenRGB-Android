package dev.eno2.openrgbremote.core.designsystem.component

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Headset
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.LaptopChromebook
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LinearScale
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import dev.eno2.openrgbremote.core.designsystem.R
import dev.eno2.openrgbremote.sdk.model.DeviceType

/** Icon shown for a device type. */
val DeviceType.icon: ImageVector
    get() = when (this) {
        DeviceType.MOTHERBOARD -> Icons.Outlined.DeveloperBoard
        DeviceType.DRAM -> Icons.Outlined.Memory
        DeviceType.GPU -> Icons.Outlined.ViewInAr
        DeviceType.COOLER -> Icons.Outlined.AcUnit
        DeviceType.LEDSTRIP -> Icons.Outlined.LinearScale
        DeviceType.KEYBOARD -> Icons.Outlined.Keyboard
        DeviceType.MOUSE -> Icons.Outlined.Mouse
        DeviceType.MOUSEMAT -> Icons.Outlined.Tab
        DeviceType.HEADSET -> Icons.Outlined.Headset
        DeviceType.HEADSET_STAND -> Icons.Outlined.Headphones
        DeviceType.GAMEPAD -> Icons.Outlined.SportsEsports
        DeviceType.LIGHT -> Icons.Outlined.Lightbulb
        DeviceType.SPEAKER -> Icons.Outlined.Speaker
        DeviceType.VIRTUAL -> Icons.Outlined.Widgets
        DeviceType.STORAGE -> Icons.Outlined.Storage
        DeviceType.CASE -> Icons.Outlined.Computer
        DeviceType.MICROPHONE -> Icons.Outlined.Mic
        DeviceType.ACCESSORY -> Icons.Outlined.Cable
        DeviceType.KEYPAD -> Icons.Outlined.Dialpad
        DeviceType.LAPTOP -> Icons.Outlined.LaptopChromebook
        DeviceType.MONITOR -> Icons.Outlined.Monitor
        DeviceType.UNKNOWN -> Icons.Outlined.QuestionMark
    }

/** Localized name of a device type. */
@get:StringRes
val DeviceType.labelRes: Int
    get() = when (this) {
        DeviceType.MOTHERBOARD -> R.string.device_type_motherboard
        DeviceType.DRAM -> R.string.device_type_dram
        DeviceType.GPU -> R.string.device_type_gpu
        DeviceType.COOLER -> R.string.device_type_cooler
        DeviceType.LEDSTRIP -> R.string.device_type_ledstrip
        DeviceType.KEYBOARD -> R.string.device_type_keyboard
        DeviceType.MOUSE -> R.string.device_type_mouse
        DeviceType.MOUSEMAT -> R.string.device_type_mousemat
        DeviceType.HEADSET -> R.string.device_type_headset
        DeviceType.HEADSET_STAND -> R.string.device_type_headset_stand
        DeviceType.GAMEPAD -> R.string.device_type_gamepad
        DeviceType.LIGHT -> R.string.device_type_light
        DeviceType.SPEAKER -> R.string.device_type_speaker
        DeviceType.VIRTUAL -> R.string.device_type_virtual
        DeviceType.STORAGE -> R.string.device_type_storage
        DeviceType.CASE -> R.string.device_type_case
        DeviceType.MICROPHONE -> R.string.device_type_microphone
        DeviceType.ACCESSORY -> R.string.device_type_accessory
        DeviceType.KEYPAD -> R.string.device_type_keypad
        DeviceType.LAPTOP -> R.string.device_type_laptop
        DeviceType.MONITOR -> R.string.device_type_monitor
        DeviceType.UNKNOWN -> R.string.device_type_unknown
    }
