package com.rickyboy.appsweep

/** Measurements of the artwork (generated from the images; units are pixels of each image). */
object RamArt {
    // RAM ram (left dial): 600 x 576
    const val RAM_W = 600
    const val RAM_H = 576
    const val RAM_CX = 189.26f        // centre of the horn's curl
    const val RAM_CY = 229.53f
    /** The scale uses the thick part of the horn; the thin tip stays natural horn colour. */
    const val SCALE_START = 0.05f
    const val SCALE_END = 0.84f
    // Along the horn: position (0..1), angle (degrees), inner and outer radius
    val HORN_T = floatArrayOf(0.020f, 0.030f, 0.040f, 0.050f, 0.060f, 0.070f, 0.080f, 0.090f, 0.100f, 0.110f, 0.120f, 0.130f, 0.140f, 0.150f, 0.160f, 0.170f, 0.180f, 0.190f, 0.200f, 0.210f, 0.220f, 0.230f, 0.240f, 0.250f, 0.260f, 0.270f, 0.280f, 0.290f, 0.300f, 0.310f, 0.320f, 0.330f, 0.340f, 0.350f, 0.360f, 0.370f, 0.380f, 0.390f, 0.400f, 0.410f, 0.420f, 0.430f, 0.440f, 0.450f, 0.460f, 0.470f, 0.480f, 0.490f, 0.500f, 0.510f, 0.520f, 0.530f, 0.540f, 0.550f, 0.560f, 0.570f, 0.580f, 0.590f, 0.600f, 0.610f, 0.620f, 0.630f, 0.640f, 0.650f, 0.660f, 0.670f, 0.680f, 0.690f, 0.700f, 0.710f, 0.720f, 0.730f, 0.740f, 0.750f, 0.760f, 0.770f, 0.780f, 0.790f, 0.800f, 0.810f, 0.820f, 0.830f, 0.840f, 0.850f, 0.860f, 0.870f, 0.880f, 0.890f, 0.900f, 0.920f, 0.930f, 0.940f, 0.950f, 0.960f, 0.970f, 0.980f, 0.990f, 1.000f)
    val HORN_ANGLE = floatArrayOf(-44.387f, -47.581f, -50.774f, -53.968f, -57.161f, -60.355f, -63.548f, -66.742f, -69.935f, -73.129f, -76.322f, -79.516f, -82.709f, -85.903f, -89.096f, -92.290f, -95.483f, -98.677f, -101.870f, -105.064f, -108.257f, -111.451f, -114.644f, -117.838f, -121.031f, -124.225f, -127.418f, -130.612f, -133.805f, -136.999f, -140.192f, -143.386f, -146.579f, -149.773f, -152.966f, -156.160f, -159.354f, -162.547f, -165.741f, -168.934f, -172.128f, -175.321f, -178.515f, -181.708f, -184.902f, -188.095f, -191.289f, -194.482f, -197.676f, -200.869f, -204.063f, -207.256f, -210.450f, -213.643f, -216.837f, -220.030f, -223.224f, -226.417f, -229.611f, -232.804f, -235.998f, -239.191f, -242.385f, -245.578f, -248.772f, -251.965f, -255.159f, -258.352f, -261.546f, -264.739f, -267.933f, -271.126f, -274.320f, -277.513f, -280.707f, -283.901f, -287.094f, -290.288f, -293.481f, -296.675f, -299.868f, -303.062f, -306.255f, -309.449f, -312.642f, -315.836f, -319.029f, -322.223f, -325.416f, -331.803f, -334.997f, -338.190f, -341.384f, -344.577f, -347.771f, -350.964f, -354.158f, -357.351f)
    val HORN_R_IN = floatArrayOf(140.134f, 102.282f, 88.591f, 84.564f, 82.148f, 82.148f, 83.758f, 81.342f, 81.342f, 80.537f, 81.342f, 80.537f, 79.732f, 78.926f, 78.926f, 78.926f, 78.926f, 79.732f, 79.732f, 78.121f, 78.926f, 78.926f, 78.926f, 78.121f, 78.121f, 78.121f, 78.121f, 78.121f, 78.121f, 78.121f, 78.121f, 77.315f, 77.315f, 77.315f, 77.315f, 77.315f, 77.315f, 76.510f, 76.510f, 77.315f, 77.315f, 77.315f, 78.121f, 78.926f, 78.926f, 79.732f, 80.537f, 81.342f, 82.148f, 82.953f, 84.564f, 86.174f, 86.980f, 87.785f, 89.396f, 92.617f, 92.617f, 94.228f, 95.839f, 98.255f, 99.866f, 102.282f, 103.893f, 107.114f, 107.919f, 109.530f, 112.752f, 113.557f, 115.168f, 116.779f, 116.779f, 117.584f, 117.584f, 118.389f, 120.000f, 120.000f, 120.000f, 119.195f, 118.389f, 119.195f, 115.973f, 115.168f, 111.946f, 111.141f, 107.919f, 111.141f, 119.195f, 127.248f, 136.107f, 91.007f, 88.591f, 86.174f, 82.953f, 81.342f, 78.926f, 76.510f, 74.094f, 72.483f)
    val HORN_R_OUT = floatArrayOf(203.758f, 207.785f, 209.396f, 205.369f, 205.369f, 206.980f, 208.591f, 204.564f, 208.591f, 206.980f, 207.785f, 203.758f, 202.148f, 204.564f, 203.758f, 205.369f, 201.342f, 202.148f, 201.342f, 201.342f, 202.148f, 198.121f, 198.121f, 198.926f, 194.899f, 194.899f, 194.094f, 191.678f, 188.456f, 186.846f, 186.040f, 184.430f, 182.819f, 181.208f, 181.208f, 180.403f, 179.597f, 177.987f, 175.570f, 175.570f, 174.765f, 173.960f, 173.154f, 173.960f, 173.960f, 173.960f, 173.960f, 174.765f, 173.154f, 174.765f, 176.376f, 177.987f, 177.181f, 180.403f, 177.987f, 182.013f, 182.819f, 184.430f, 186.846f, 188.456f, 189.262f, 190.067f, 191.678f, 192.483f, 193.289f, 194.899f, 194.899f, 195.705f, 196.510f, 197.315f, 197.315f, 198.121f, 197.315f, 196.510f, 194.899f, 193.289f, 192.483f, 188.456f, 188.456f, 185.235f, 181.208f, 177.181f, 173.154f, 169.933f, 164.295f, 161.074f, 154.631f, 149.799f, 144.161f, 97.450f, 109.530f, 118.389f, 116.779f, 111.141f, 105.503f, 100.671f, 95.034f, 90.201f)

    /** Both top dials share this canvas so the two heads come out the same size. */
    const val DIAL_W = 600
    const val DIAL_H = 720
    // Where the RAM ram sits inside its dial (bottom, facing the other ram)
    const val RAM_OX = 86f
    const val RAM_OY = 244f
    const val RAM_SCALE = 0.864f

    // Cleaning ram (right dial, already laid out on the 600 x 720 canvas)
    const val CLEAN_W = 600
    const val CLEAN_H = 720
    const val CLEAN_BURST_X = 203f   // where the apps burst out of its head
    const val CLEAN_BURST_Y = 266f

    // Kicking ram (cleaning screen): 900 x 540
    const val KICK_W = 900
    const val KICK_H = 540
    const val KICK_HIP_X = 547.50f
    const val KICK_HIP_Y = 258.75f
    const val KICK_HOOF_X = 723.75f
    const val KICK_HOOF_Y = 285.00f

    /** Index of the measurement nearest to horn position [t]. */
    fun hornIndex(t: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in HORN_T.indices) {
            val d = kotlin.math.abs(HORN_T[i] - t)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}
