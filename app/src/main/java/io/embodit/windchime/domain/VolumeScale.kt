package io.embodit.windchime.domain

import kotlin.math.abs
import kotlin.math.min

/**
 * The chime volume may be resized uniformly by the user (manifest resizerestriction=1; system min 640 / default 1500 /
 * max 5400 dp, about 0.43x .. 3.6x; QA #1 q1-2, design input.dragSpace / physics.grab). The app measures the volume
 * it really got (the SpatialView's corner in the content's local space, as luban-lock / seed-pits do) and:
 *  - scales the content root by [root] so the whole chime fills the volume as designed;
 *  - turns every hand / ray / eye-hand travel into content (root-local) metres with [toContent]: physical travel x
 *    [viewRatio] (content m per physical m of the view itself, zen-orb's check, 1 unless the system scales the space)
 *    / [root];
 *  - turns a hit point in the view's local space into root-local metres with [pointToContent].
 * The physics, the layout (Geo) and the flow all keep working in design metres at 1x.
 */
data class VolumeScale(val root: Float = 1f, val viewRatio: Float = 1f) {
    /** Content metres per physical metre of hand travel. */
    val contentPerPhysical: Float get() = viewRatio / root

    fun toContent(physical: V3): V3 = physical * contentPerPhysical

    fun pointToContent(local: V3): V3 = local * (1f / root)

    /** The design eye positions (physical metres from the volume centre) in root-local metres. */
    fun eyeToContent(eye: V3): V3 = eye * (1f / root)

    companion object {
        const val MIN = 0.3f
        const val MAX = 4f

        /** [halfX] / [halfY] = the measured half width / height of the volume in its local space (metres). */
        fun measured(halfX: Float, halfY: Float, entityPerPx: Float = 0f, physicalPerPx: Float = 0f): VolumeScale {
            val k = min(abs(halfX) / (Geo.VOL_W / 2), abs(halfY) / (Geo.VOL_H / 2))
            val root = if (k.isFinite() && k > 0f) k.coerceIn(MIN, MAX) else 1f
            val ratio = if (entityPerPx > 0f && physicalPerPx > 0f) (entityPerPx / physicalPerPx).coerceIn(MIN, MAX) else 1f
            return VolumeScale(root, ratio)
        }

        /** The live scale (set by the scene each time the volume's size changes; read by real input and the debug broadcast). */
        @Volatile var current = VolumeScale()
    }
}
