package io.embodit.windchime.ui.control

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import io.embodit.windchime.domain.VolumeSnap
import io.embodit.windchime.platform.TAG
import io.embodit.windchime.ui.theme.ChimeColors

/** Text that logs "OVERFLOW <panel>/<control>" whenever it is cut (profile D13; the ui-audit scan reads the log). */
@Composable
internal fun Txt(
    text: String,
    tag: String,
    style: TextStyle = PicoTheme.typography.bodyLarge,
    color: Color = PicoTheme.colorScheme.labelPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, softWrap = maxLines > 1, textAlign = align,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") },
    )
}

/** Main action (design: 72 dp, B31 >= 64 dp). */
@Composable
internal fun PrimaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Int? = null) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 72.dp, minWidth = 160.dp),
        shape = RoundedCornerShape(36.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.labelPrimaryLight)
            Box(Modifier.width(8.dp))
        }
        // design-style: inherited-content-color Button
        Txt(text, "$tag/primary", style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimaryLight)
    }
}

@Composable
internal fun SecondaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, icon: Int? = null) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 120.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) ChimeColors.Error else PicoTheme.colorScheme.fillSecondary,
            contentColor = if (danger) ChimeColors.OnError else PicoTheme.colorScheme.labelPrimary,
        ),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = if (danger) ChimeColors.OnError else PicoTheme.colorScheme.labelPrimary)
            Box(Modifier.width(8.dp))
        }
        // design-style: inherited-content-color Button
        Txt(text, "$tag/button", style = PicoTheme.typography.labelLarge, color = if (danger) ChimeColors.OnError else PicoTheme.colorScheme.labelPrimary)
    }
}

/** A switch-like button (D19): its on / off state is the fill and the icon; 72 dp like the design's button row. */
@Composable
internal fun ToggleButton(text: String, tag: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: Int? = null) {
    val fg = if (on) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick, modifier = modifier,
        size = ButtonDefaults.Regular.copy(height = 72.dp, minWidth = 120.dp),
        shape = RoundedCornerShape(36.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = fg)
            Box(Modifier.width(8.dp))
        } else {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) ChimeColors.PrimaryContainer else Color.Transparent).border(1.5.dp, fg, CircleShape))
            Box(Modifier.width(8.dp))
        }
        // design-style: inherited-content-color Button
        Txt(text, "$tag/toggle", style = PicoTheme.typography.titleMedium, color = fg)
    }
}

/** A labelled settings row holding a same-page option switch (SegmentControl never changes pages). */
@Composable
internal fun OptionRow(label: String, tag: String, labelWidth: Dp = 132.dp, height: Dp = 64.dp, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(labelWidth))
        content()
    }
}

/** A small label above a segmented choice (the play page: 风铃 / 风), then the segments in their own row. */
@Composable
internal fun <T> LabeledSegments(label: String, tag: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, enabled: Boolean = true, note: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(label, "$tag/label", style = PicoTheme.typography.labelMedium, color = ChimeColors.InkSecondary)
            if (note != null) {
                Box(Modifier.width(12.dp))
                Txt(note, "$tag/note", style = PicoTheme.typography.labelMedium, color = ChimeColors.InkSecondary)
            }
        }
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            Segments(options, selected, tag, onSelect, Modifier.weight(1f), height = 64.dp, enabled = enabled)
        }
    }
}

@Composable
internal fun <T> Segments(options: List<Pair<T, String>>, selected: T, tag: String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, height: Dp = 56.dp, enabled: Boolean = true) {
    // B19: the control itself is >= 48 dp tall (the default track is ~37 dp).
    SegmentControl(modifier = modifier.height(height)) {
        options.forEach { (value, label) ->
            SegmentItem(
                selected = value == selected,
                onClick = { if (enabled) onSelect(value) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                title = {
                    // design-style: inherited-content-color SegmentItem
                    Txt(label, "$tag/segment", style = PicoTheme.typography.labelLarge, color = if (enabled) PicoTheme.colorScheme.labelPrimary else ChimeColors.InkSecondary)
                },
            )
        }
    }
}

/** A whole >= 48 dp row that toggles (label + Switch); the bare SpatialUI Switch is only ~32 x 20 dp (D44). */
@Composable
internal fun SwitchRow(label: String, tag: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(16.dp))
            .toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Volume (B20): bound to a local Float so every tap and every small drag moves it and is heard at once; on release the
 * value snaps to low 35 % / mid 65 % / high 100 % when it is near one ([VolumeSnap]). Shows the percentage.
 */
@Composable
internal fun VolumeSlider(value: Float, onLive: (Float) -> Unit, onDone: (Float) -> Unit, modifier: Modifier = Modifier) {
    var local by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { local = value }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = local,
            onValueChange = { v -> local = v; onLive(v) },
            onValueChangeFinished = { val s = VolumeSnap.snap(local); local = s; onDone(s) },
            modifier = Modifier.weight(1f).height(48.dp),
            valueRange = 0f..1f,
        )
        Box(Modifier.width(12.dp))
        Txt("${VolumeSnap.percent(local)}%", "settings/volume-pct", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
    }
}
