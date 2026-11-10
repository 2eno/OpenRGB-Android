package dev.eno2.openrgbremote.core.effects

import dev.eno2.openrgbremote.sdk.model.OpenRgbDevice
import dev.eno2.openrgbremote.sdk.model.Zone

/**
 * Normalized position (0..1) of every LED of a device, in LED order.
 *
 * Every zone spans the full range on its own, like profiles do: a wave runs along each strip and
 * across each keyboard instead of being squeezed into one part of a combined "device length".
 * Linear zones lie on y = 0.5; matrix zones take x from the column and y from the row (0 = top);
 * LEDs that a matrix map does not contain fall back to the zone center.
 */
class LedLayout(val x: FloatArray, val y: FloatArray, val isMatrix: BooleanArray) {
    init {
        require(x.size == y.size && x.size == isMatrix.size)
    }

    val size: Int get() = x.size

    companion object {
        fun linear(count: Int): LedLayout = LedLayout(
            FloatArray(count) { if (count <= 1) 0.5f else it.toFloat() / (count - 1) },
            FloatArray(count) { 0.5f },
            BooleanArray(count),
        )

        fun of(device: OpenRgbDevice): LedLayout {
            val n = device.ledCount
            val x = FloatArray(n) { 0.5f }
            val y = FloatArray(n) { 0.5f }
            val matrix = BooleanArray(n)
            for (zone in device.zones) place(zone, x, y, matrix)
            return LedLayout(x, y, matrix)
        }

        private fun place(zone: Zone, x: FloatArray, y: FloatArray, matrix: BooleanArray) {
            val map = zone.matrixMap
            if (zone.type.isMatrix && map != null) {
                for (row in 0 until map.height) for (column in 0 until map.width) {
                    val led = map.ledAt(row, column) ?: continue
                    val index = zone.startIndex + led
                    if (index !in x.indices || led >= zone.ledsCount) continue
                    x[index] = if (map.width <= 1) 0.5f else column.toFloat() / (map.width - 1)
                    y[index] = if (map.height <= 1) 0.5f else row.toFloat() / (map.height - 1)
                    matrix[index] = true
                }
            } else {
                for (i in 0 until zone.ledsCount) {
                    val index = zone.startIndex + i
                    if (index !in x.indices) continue
                    x[index] = if (zone.ledsCount <= 1) 0.5f else i.toFloat() / (zone.ledsCount - 1)
                }
            }
        }
    }
}
