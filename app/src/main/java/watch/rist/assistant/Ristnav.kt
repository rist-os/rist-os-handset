package watch.rist.assistant

object Ristnav {
    init { System.loadLibrary("ristnav") }

    /** out = {px, py} in frame pixels. */
    external fun nProject(
        bearing: Double, centerLat: Double, centerLon: Double, mPerPx: Double,
        minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        w: Int, h: Int, lat: Double, lon: Double, out: DoubleArray
    )

    external fun nHaversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double

    // handle is a native rn_ctx pointer.
    external fun nCreate(): Long
    external fun nDestroy(handle: Long)
    external fun nSetRoute(handle: Long, shape: String, precision: Int,
        turnLats: DoubleArray, turnLons: DoubleArray, turnTypes: IntArray, turnDists: IntArray): Int
    external fun nUpdate(handle: Long, lat: Double, lon: Double, out: DoubleArray): Int
    external fun nSelectFrame(handle: Long, frames: DoubleArray, nFrames: Int, lat: Double, lon: Double): Int
    external fun nGetRoutePoints(handle: Long, out: DoubleArray): Int
}
