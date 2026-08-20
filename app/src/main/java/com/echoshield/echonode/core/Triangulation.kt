package com.echoshield.echonode.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Source localization from the phones that independently confirmed a shot.
 *
 * Two methods, in order of preference:
 *
 * 1. **TDoA multilateration.** Sound travels ~0.343 m/ms, so the differences in
 *    arrival time across phones at known positions constrain where the shot came
 *    from. For confirming nodes i, the quantity `arrival_i - distance_i / c` equals
 *    the (unknown but shared) clock origin for the *true* source position and varies
 *    for any other point. We therefore grid-search the position that minimises the
 *    variance of that quantity — a coarse sweep then a fine refinement — which needs
 *    no initial guess and cannot diverge the way an iterative least-squares solve can.
 *    This requires arrival times on a common clock; [Observation.detectedAtMs] must
 *    already be converted to the local time base, and [Observation.timingUncertaintyMs]
 *    must describe how well that conversion is known.
 *
 * 2. **Confidence-weighted centroid.** When timing is missing, too coarse, or the
 *    solve does not fit, fall back to the weighted centre of the confirming cluster.
 *    This is the honest answer for un-synchronised phones: it says "somewhere in
 *    here", bounded by [Fix.spreadMeters].
 *
 * See `docs/GUNSHOT_ACOUSTICS_INDOORS.md` for the acoustics this rests on.
 */
object Triangulation {

    const val SPEED_OF_SOUND_MPS = 343.0

    /** Nominal storey height used to turn an altitude spread into a floor offset. */
    const val FLOOR_HEIGHT_METERS = 3.5

    const val METHOD_TDOA = "tdoa_multilateration"
    const val METHOD_CENTROID = "weighted_centroid"
    const val METHOD_SINGLE = "single_report"

    /** Minimum confirming nodes with usable timing before a TDoA solve is attempted. */
    private const val MIN_TDOA_NODES = 3

    /**
     * Arrival times spread wider than this cannot be one indoor acoustic event
     * (sound crosses a large building in well under a second), so they indicate
     * clocks that are not actually comparable.
     */
    private const val MAX_ARRIVAL_SPREAD_MS = 1500.0

    /**
     * Worst per-node clock uncertainty we will still try to triangulate with. At
     * 0.343 m/ms, 60 ms of clock error is ~20 m of ranging error — already coarse,
     * and beyond it the fix is not worth more than the centroid.
     */
    private const val MAX_TIMING_UNCERTAINTY_MS = 60.0

    /** Nodes closer together than this give the solve no geometric leverage. */
    private const val MIN_BASELINE_METERS = 4.0

    private const val COARSE_RANGE_METERS = 150.0
    private const val COARSE_STEP_METERS = 5.0
    private const val FINE_STEP_METERS = 0.5

    /** One confirming node's contribution to the fix. */
    data class Observation(
        val nodeId: String,
        val latitude: Double,
        val longitude: Double,
        val altitude: Double = Double.NaN,
        val confidence: Float = 0.5f,
        /**
         * When this node's microphone heard the impulse, already converted to the
         * *local* clock. [NO_TIMING] when unknown.
         */
        val detectedAtMs: Long = NO_TIMING,
        /** How well [detectedAtMs] is known, in ms; NaN when the clock is unsynced. */
        val timingUncertaintyMs: Double = Double.NaN
    ) {
        val hasPosition: Boolean
            get() = latitude.isFinite() && longitude.isFinite() && !(latitude == 0.0 && longitude == 0.0)

        val hasTiming: Boolean
            get() = detectedAtMs != NO_TIMING &&
                timingUncertaintyMs.isFinite() &&
                timingUncertaintyMs <= MAX_TIMING_UNCERTAINTY_MS
    }

    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        /** Storeys above the lowest confirming phone. */
        val floorOffset: Int,
        val method: String,
        val contributingNodes: Int,
        /** Radius covering the confirming cluster — a crude confidence bound. */
        val spreadMeters: Double,
        /** RMS timing misfit of the TDoA solve; NaN for centroid fixes. */
        val timingResidualMs: Double
    )

    const val NO_TIMING = Long.MIN_VALUE

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2) * sin(dLon / 2)
        return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Best available source fix from [observations], or null if no observation
     * carried a usable position.
     */
    fun solve(observations: List<Observation>): Fix? {
        val located = observations.filter { it.hasPosition }
        if (located.isEmpty()) return null

        val tdoa = solveTdoa(located)
        val base = tdoa ?: weightedCentroid(located)

        val altitudes = located.map { it.altitude }.filter { it.isFinite() }
        val floorOffset = if (altitudes.size >= 2) {
            ((altitudes.max() - altitudes.min()) / FLOOR_HEIGHT_METERS).roundToInt()
        } else {
            0
        }
        val spreadMeters = located.maxOf {
            haversineMeters(base.latitude, base.longitude, it.latitude, it.longitude)
        }

        return Fix(
            latitude = base.latitude,
            longitude = base.longitude,
            altitude = if (altitudes.isEmpty()) Double.NaN else altitudes.average(),
            floorOffset = floorOffset,
            method = if (tdoa != null) METHOD_TDOA else {
                if (located.size >= 2) METHOD_CENTROID else METHOD_SINGLE
            },
            contributingNodes = located.size,
            spreadMeters = spreadMeters,
            timingResidualMs = base.residualMs
        )
    }

    private data class Point(val latitude: Double, val longitude: Double, val residualMs: Double)

    private fun weightedCentroid(located: List<Observation>): Point {
        var weightSum = 0.0
        var latAcc = 0.0
        var lonAcc = 0.0
        located.forEach { observation ->
            val weight = observation.confidence.toDouble().coerceIn(0.05, 1.0)
            weightSum += weight
            latAcc += observation.latitude * weight
            lonAcc += observation.longitude * weight
        }
        return Point(latAcc / weightSum, lonAcc / weightSum, Double.NaN)
    }

    /**
     * Grid-search multilateration. Returns null whenever the inputs cannot support an
     * honest fix — too few timed nodes, clocks too loose, no geometric baseline, an
     * arrival spread that is not physically one event, or a solve that does not fit
     * its own timing uncertainty.
     */
    private fun solveTdoa(located: List<Observation>): Point? {
        val timed = located.filter { it.hasTiming }
        if (timed.size < MIN_TDOA_NODES) return null

        val arrivals = timed.map { it.detectedAtMs }
        if ((arrivals.max() - arrivals.min()).toDouble() > MAX_ARRIVAL_SPREAD_MS) return null

        val lat0 = timed.sumOf { it.latitude } / timed.size
        val lon0 = timed.sumOf { it.longitude } / timed.size
        val metresPerDegLat = 111_320.0
        val metresPerDegLon = 111_320.0 * cos(lat0 * PI / 180.0)
        if (abs(metresPerDegLon) < 1.0) return null // degenerate near the poles

        // Local tangent-plane coordinates in metres, times in seconds.
        val points = timed.map { observation ->
            Triple(
                (observation.longitude - lon0) * metresPerDegLon,
                (observation.latitude - lat0) * metresPerDegLat,
                observation.detectedAtMs / 1000.0
            )
        }

        val baseline = points.maxOf { a -> points.maxOf { b -> hypot(a.first - b.first, a.second - b.second) } }
        if (baseline < MIN_BASELINE_METERS) return null

        var best = search(points, 0.0, 0.0, COARSE_RANGE_METERS, COARSE_STEP_METERS)
        best = search(points, best.first, best.second, COARSE_STEP_METERS, FINE_STEP_METERS)

        val residualMs = sqrt(best.third) * 1000.0
        // The fit has to be consistent with how well we actually know the clocks;
        // a residual far beyond that means the arrival times are not one shot.
        val tolerance = (timed.maxOf { it.timingUncertaintyMs } * 2.0).coerceAtLeast(10.0)
        if (residualMs > tolerance) return null

        return Point(
            latitude = lat0 + best.second / metresPerDegLat,
            longitude = lon0 + best.first / metresPerDegLon,
            residualMs = residualMs
        )
    }

    /**
     * Sweep a square window and return the (x, y, variance) minimising the variance of
     * `arrival - travelTime` across nodes — zero variance means every node's arrival is
     * explained by one shot at that point plus a single shared clock offset.
     */
    private fun search(
        points: List<Triple<Double, Double, Double>>,
        centerX: Double,
        centerY: Double,
        range: Double,
        step: Double
    ): Triple<Double, Double, Double> {
        var bestX = centerX
        var bestY = centerY
        var bestVariance = Double.MAX_VALUE

        var x = centerX - range
        while (x <= centerX + range) {
            var y = centerY - range
            while (y <= centerY + range) {
                var mean = 0.0
                points.forEach { (px, py, t) ->
                    mean += t - hypot(px - x, py - y) / SPEED_OF_SOUND_MPS
                }
                mean /= points.size

                var variance = 0.0
                points.forEach { (px, py, t) ->
                    val offset = t - hypot(px - x, py - y) / SPEED_OF_SOUND_MPS
                    variance += (offset - mean) * (offset - mean)
                }
                variance /= points.size

                if (variance < bestVariance) {
                    bestVariance = variance
                    bestX = x
                    bestY = y
                }
                y += step
            }
            x += step
        }
        return Triple(bestX, bestY, bestVariance)
    }
}
