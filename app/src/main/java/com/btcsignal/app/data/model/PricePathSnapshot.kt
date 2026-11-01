package com.btcsignal.app.data.model

import androidx.compose.ui.geometry.Offset
import org.json.JSONArray

/**
 * Compact JSON encoding of a "Price Move Since Candle Open" path (elapsed-ms-since-
 * candle-open -> price), used to persist a per-signal chart snapshot captured at candle
 * close (see LiveMonitoringService.handleCandleClosed) so the History screen can redraw
 * the exact same chart later (PriceMoveSnapshotChart) without storing an actual bitmap.
 * Deliberately the only place this format is written/read, mirroring the app's rule of
 * one canonical implementation per concern (see Signal.kt).
 */
fun encodePricePath(points: List<Offset>): String {
    val arr = JSONArray()
    for (p in points) {
        val pair = JSONArray()
        pair.put(p.x.toDouble())
        pair.put(p.y.toDouble())
        arr.put(pair)
    }
    return arr.toString()
}

fun decodePricePath(json: String?): List<Offset> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val pair = arr.getJSONArray(i)
            Offset(pair.getDouble(0).toFloat(), pair.getDouble(1).toFloat())
        }
    } catch (e: Exception) {
        emptyList()
    }
}
