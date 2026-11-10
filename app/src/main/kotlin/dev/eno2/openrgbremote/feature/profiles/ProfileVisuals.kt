package dev.eno2.openrgbremote.feature.profiles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.designsystem.component.rgbColor
import dev.eno2.openrgbremote.core.domain.model.ColorLayout
import dev.eno2.openrgbremote.core.domain.model.Profile
import dev.eno2.openrgbremote.core.domain.model.ProfileRenderer

/** Localized name: presets are translated, custom profiles keep the name the user gave them. */
@Composable
fun profileDisplayName(profile: Profile): String = when (profile.presetKey) {
    "ocean" -> stringResource(R.string.profile_preset_ocean)
    "sunset" -> stringResource(R.string.profile_preset_sunset)
    "cyberpunk" -> stringResource(R.string.profile_preset_cyberpunk)
    "forest" -> stringResource(R.string.profile_preset_forest)
    "gaming_red" -> stringResource(R.string.profile_preset_gaming_red)
    "warm_white" -> stringResource(R.string.profile_preset_warm_white)
    "night" -> stringResource(R.string.profile_preset_night)
    "off" -> stringResource(R.string.profile_preset_off)
    else -> profile.name
}

/**
 * Strip rendered with the same [ProfileRenderer] that colors the devices, so what the card
 * shows is what a linear zone will look like.
 */
@Composable
fun ProfilePreview(profile: Profile, shape: Shape, modifier: Modifier = Modifier) {
    val samples = if (profile.layout == ColorLayout.ALTERNATING) 24 else 60
    val colors = ProfileRenderer.preview(profile, samples)
    val description = stringResource(R.string.profile_preview_description, profileDisplayName(profile))
    Canvas(
        modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val width = size.width / samples
        for (i in 0 until samples) {
            // +1px overlap avoids hairline gaps between neighbouring rectangles.
            drawRect(rgbColor(colors[i]), Offset(i * width, 0f), Size(width + 1f, size.height))
        }
    }
}
