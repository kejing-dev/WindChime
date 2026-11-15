package io.embodit.windchime

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.windchime.ui.Containers
import io.embodit.windchime.ui.control.ControlWindow
import io.embodit.windchime.ui.scene.ChimeScreen
import io.embodit.windchime.ui.theme.ChimeTheme

/**
 * Two surfaces in Shared Space (design windowModel volume_plus_control_window): the chime volume (default container,
 * 0.60 x 0.70 x 0.60 m from the manifest) and the control window (Planar 560 x 640 dp) to its right. Every open uses
 * Placement.right(volume) (profile D44: Shared Space never promises "in front of you").
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            ChimeTheme { ChimeScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 560.dp, height = 640.dp),
            placement = { containers.firstOrNull { it.id == Containers.VOLUME }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            ChimeTheme {
                Box(Modifier.windowConstraints(minWidth = 520.dp, minHeight = 620.dp, maxWidth = 800.dp, maxHeight = 720.dp)) { ControlWindow() }
            }
        }
    }
