package io.embodit.windchime.platform

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.windchime.ui.Containers

/**
 * Opens / reopens the control window. Shared Space (profile D44 correction): it always opens beside the chime volume
 * (Placement.right in Main.kt), never "in front of you"; the user moves it with the system handle. The app never
 * reads the head pose. Exit closes both window containers before the tasks are removed (D26 / D44).
 */
class WindowCtl {
    var panelState by mutableStateOf("never")
        private set
    val panelOpen: Boolean get() = panelState != "never" && panelState != "ON_DESTROY"
    /** Set when the panel tile is tapped while the window is open: the window flashes its edge (design input.targets). */
    var flashAt by mutableLongStateOf(0L)
        private set

    private var navigator: SpatialNavigator? = null
    private var panelNavigator: SpatialNavigator? = null
    private val main = Handler(Looper.getMainLooper())
    private var openedOnce = false

    /** The volume hands over its navigator (the window's own composition can't reopen itself). */
    fun attach(nav: SpatialNavigator) { navigator = nav }

    fun detach() { navigator = null }

    fun attachPanel(nav: SpatialNavigator) { panelNavigator = nav }

    fun onPanelLifecycle(event: String) {
        panelState = event
        Log.i(TAG, "WINDOW lifecycle $event")
    }

    /** Opens the control window beside the volume (Placement.right); no-op while it is open. */
    fun open(why: String) {
        if (panelOpen) { Log.i(TAG, "WINDOW open($why) skipped: already open"); return }
        Log.i(TAG, "WINDOW open why=$why")
        runCatching { (navigator ?: panelNavigator)?.openWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }
            .onFailure { Log.e(TAG, "WINDOW open failed", it) }
        openedOnce = true
    }

    /** The first open after the volume is up (a short delay lets the system place the volume first). */
    fun openAtStart() { if (!openedOnce) main.postDelayed({ open("start") }, 300) }

    /** T11: the panel tile in the volume. Closed -> reopen beside the volume; open -> flash its edge. */
    fun reopenOrFlash() {
        if (panelOpen) { flashAt = SystemClock.uptimeMillis(); Log.i(TAG, "WINDOW flash (already open)") } else open("panel-tile")
    }

    fun flash() { flashAt = SystemClock.uptimeMillis() }

    /** Debug: the system [x] on the control window (S07). */
    fun closeControlOnly() { runCatching { (navigator ?: panelNavigator)?.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) } }

    /** Exit (D26 / D44): close the control window and the volume itself. */
    fun closeAll() {
        val nav = navigator ?: panelNavigator ?: return
        runCatching { nav.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }.onFailure { Log.w(TAG, "EXIT close control: $it") }
        runCatching { navigator?.closeWindowContainer() }.onFailure { Log.w(TAG, "EXIT close volume: $it") }
        Log.i(TAG, "EXIT closed all window containers")
    }
}
