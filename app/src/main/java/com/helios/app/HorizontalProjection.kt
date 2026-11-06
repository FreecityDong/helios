package com.helios.app

/** Camera-relative X at an obstacle's depth plane, not a ground-plane route. */
data class HorizontalProjection(val width: Int, val height: Int, val fx: Float, val cx: Float,
    val fy: Float = Float.NaN, val cy: Float = Float.NaN,
    val forwardWidthMeters: Float = DEFAULT_FORWARD_WIDTH_METERS) {
    fun matches(w: Int, h: Int) = width == w && height == h && width > 0 && height > 0 &&
        fx.isFinite() && fx > 0f && cx.isFinite() && cx in 0f..(width-1).toFloat()

    init { require(forwardWidthMeters.isFinite() && forwardWidthMeters in 0.2f..1.5f) }
    val halfForwardMeters get() = forwardWidthMeters/2f
    fun inForward(pixel:Float,depthMeters:Float) = kotlin.math.abs(lateralMeters(pixel,depthMeters))<=halfForwardMeters

    fun lateralMeters(pixel: Float, depthMeters: Float) = (pixel-cx)*depthMeters/fx
    val verticalAvailable get() = fy.isFinite() && fy>0f && cy.isFinite() && cy in 0f..(height-1).toFloat()
    fun verticalMeters(pixel: Float, depthMeters: Float) = (pixel-cy)*depthMeters/fy

    fun pixel(lateralMeters: Float, depthMeters: Float) = cx+fx*lateralMeters/depthMeters

    fun overlapMeters(box: TargetBox, leftMeters: Float, rightMeters: Float): Float? {
        val z = box.distance.meters?.takeIf { it.isFinite() && it in 0.25f..4f } ?: return null
        return (minOf(lateralMeters(box.right,z),rightMeters)-
            maxOf(lateralMeters(box.left,z),leftMeters)).coerceAtLeast(0f)
    }

    companion object {
        // Default only. Runtime decisions use this projection's configured width.
        const val DEFAULT_FORWARD_WIDTH_METERS = 0.60f
        const val HALF_FORWARD_METERS = DEFAULT_FORWARD_WIDTH_METERS/2f
        const val MAX_OBSERVED_DEPTH_METERS = 10f
        const val SIDE_OUTER_METERS = 0.95f
    }
}
