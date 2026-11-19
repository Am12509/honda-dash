package app.hondadash

import android.content.SharedPreferences

/**
 * Trip computer. Fuel flow comes from the ECU's fuel per injection (CF40) x engine rpm, set to zero while the
 * decel fuel cut (B618) is active. One factor turns that into litres per hour. The factor starts as an estimate and
 * becomes exact after one full-to-full fill-up (see [fillUp]). Distance is the OBD speed integrated over time.
 *
 * Runs on the engine thread after every poll cycle, so it keeps counting with the screen closed.
 */
class Trip(private val prefs: SharedPreferences) {
    /** Litres per hour = factor x CF40 x rpm / 1000. Estimate until the first fill-up calibration. */
    @Volatile var factor = prefs.getFloat("fuel_k", DEFAULT_FACTOR.toFloat()).toDouble()
        private set
    val calibrated: Boolean get() = prefs.getBoolean("fuel_cal", false)

    @Volatile var nowLh = Double.NaN          // fuel flow now, L/h
        private set
    @Volatile var nowSpeed = Double.NaN       // km/h
        private set
    @Volatile var tripKm = prefs.getFloat("trip_km", 0f).toDouble()
        private set
    @Volatile var tripL = prefs.getFloat("trip_l", 0f).toDouble()
        private set
    @Volatile var driveKm = 0.0
        private set
    @Volatile var driveL = 0.0
        private set
    /** Since the last "Full tank": raw fuel (index x hours, before the factor) and km. */
    @Volatile var tankRaw = prefs.getFloat("tank_raw", 0f).toDouble()
        private set
    @Volatile var tankKm = prefs.getFloat("tank_km", 0f).toDouble()
        private set
    val tankL: Double get() = tankRaw * factor

    private var last = 0L
    private var lastSave = 0L
    private var lastSeen = 0L
    // smoothed instant values (about 3 s), so "now" does not jump with every reading
    private var smLh = Double.NaN
    private var smKmh = Double.NaN

    /**
     * Adds the time since the previous call. [rpm], [speed], [fuel] (CF40) and [cut] are the latest values,
     * null when not read recently. Returns false when nothing was added.
     */
    @Synchronized
    fun update(now: Long, rpm: Double?, speed: Double?, fuel: Double?, cut: Double?): Boolean {
        if (lastSeen != 0L && now - lastSeen > NEW_DRIVE_MS) { driveKm = 0.0; driveL = 0.0 }
        lastSeen = now
        val prev = last
        last = now
        if (rpm == null || speed == null || fuel == null) { nowLh = Double.NaN; return false }
        val idx = if (cut != null && cut >= 0.5) 0.0 else fuel * rpm / 1000.0
        val lh = idx * factor
        val a = if (smLh.isNaN()) 1.0 else 0.35
        smLh = if (smLh.isNaN()) lh else smLh + (lh - smLh) * a
        smKmh = if (smKmh.isNaN()) speed else smKmh + (speed - smKmh) * a
        nowLh = smLh
        nowSpeed = smKmh
        if (prev == 0L) return false
        val dt = now - prev
        if (dt <= 0 || dt > MAX_STEP_MS) return false      // a gap (link lost): do not guess
        val h = dt / 3_600_000.0
        val km = speed * h
        val raw = idx * h
        tripKm += km; tripL += raw * factor
        driveKm += km; driveL += raw * factor
        tankKm += km; tankRaw += raw
        if (now - lastSave > 10_000) save(now)
        return true
    }

    @Synchronized
    fun save(now: Long = System.currentTimeMillis()) {
        lastSave = now
        prefs.edit().putFloat("trip_km", tripKm.toFloat()).putFloat("trip_l", tripL.toFloat())
            .putFloat("tank_raw", tankRaw.toFloat()).putFloat("tank_km", tankKm.toFloat()).apply()
    }

    @Synchronized
    fun resetTrip() { tripKm = 0.0; tripL = 0.0; save() }

    /** The tank was just filled to the top: start counting the next tank. */
    @Synchronized
    fun fullTank() { tankRaw = 0.0; tankKm = 0.0; save() }

    /**
     * Second fill-up to the top: [litres] went in. Sets the factor so the counted fuel equals the litres.
     * Returns the tank average in km/L, or null when the tank distance is too short to calibrate.
     */
    @Synchronized
    fun fillUp(litres: Double): Double? {
        if (litres <= 1.0 || tankKm < MIN_CAL_KM || tankRaw <= 0) return null
        val k = litres / tankRaw
        // keep the old trip litres consistent with the new factor
        val scale = k / factor
        tripL *= scale; driveL *= scale
        factor = k
        prefs.edit().putFloat("fuel_k", k.toFloat()).putBoolean("fuel_cal", true).apply()
        val avg = tankKm / litres
        fullTank()
        return avg
    }

    companion object {
        /** Estimate: about 0.9 L/h at a warm idle (CF40 about 900 at 820 rpm in the 19 Nov Fuel log). */
        const val DEFAULT_FACTOR = 0.00122
        const val MAX_STEP_MS = 15_000L
        const val NEW_DRIVE_MS = 5 * 60_000L
        const val MIN_CAL_KM = 50.0

        /** km per litre for a distance and litres, or NaN. */
        fun kml(km: Double, l: Double): Double = if (l > 0.05 && km > 0.2) km / l else Double.NaN
    }
}
