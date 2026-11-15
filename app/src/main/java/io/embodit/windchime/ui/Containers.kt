package io.embodit.windchime.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.embodit.windchime.domain.Page

/** Ids of the two window containers. The chime volume is the default container (manifest). */
object Containers {
    const val VOLUME = "WindChimeVolume"
    const val CONTROL = "WindChimeControl"
    const val CONTROL_TAG = "control"
}

/** Window-page state shared process-wide so the debug broadcast and the flow can switch pages. */
object Ui {
    var page by mutableStateOf(Page.PLAY)
    var exitConfirm by mutableStateOf(false)
}
