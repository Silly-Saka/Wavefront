package wavefront.acoustics;

/**
 * How much sound is lost when it has to bend round an edge to reach the listener.
 *
 * Uses the standard barrier formula (Maekawa's chart as fitted by Kurze and Anderson, the
 * same form as ISO 9613-2 without its built-in ground effect):
 * loss = 10*log10(3 + 20 * N), where N = 2 * detour / wavelength is the Fresnel number.
 * The detour is how much longer the bent path is than the straight line. Short wavelengths
 * (high notes) get a large N and lose more, which is why sound round a corner is duller.
 */
public final class Diffraction {
    private Diffraction() {}

    /**
     * Ceilings on the loss. ISO 9613-2 uses 20 and 25 dB, but those reflect wind and turbulence
     * over hundreds of metres outdoors. At Minecraft distances the formula holds further, so the
     * ceilings here are higher; they only stop deep shadows from becoming total silence.
     */
    public static final double MAX_LOSS_ONE_EDGE_DB = 30.0;
    public static final double MAX_LOSS_MANY_EDGES_DB = 35.0;

    /**
     * Loss in dB for one band.
     *
     * @param band        index into {@link Bands#HZ}
     * @param detour      bent path length minus straight-line distance, in metres (blocks)
     * @param bends       number of edges the path bends round (1 or more)
     * @param bendSpan    distance between the first and last bend in metres (0 for one bend)
     */
    public static double lossDb(int band, double detour, int bends, double bendSpan) {
        if (bends <= 0) return 0;
        double wavelength = Bands.WAVELENGTH[band];
        double fresnel = 2.0 * Math.max(0, detour) / wavelength;
        double multi = 1.0;
        if (bends > 1 && bendSpan > 1e-6) {
            // ISO 9613-2 eq. 15: a thick barrier (two edges) shadows more than a thin one.
            double r = 5.0 * wavelength / bendSpan;
            multi = (1.0 + r * r) / (1.0 / 3.0 + r * r);
        }
        double loss = 10.0 * Math.log10(3.0 + 20.0 * fresnel * multi);
        return Math.min(loss, bends > 1 ? MAX_LOSS_MANY_EDGES_DB : MAX_LOSS_ONE_EDGE_DB);
    }

    /** Loss in dB for all eight bands. */
    public static double[] lossDb(double detour, int bends, double bendSpan) {
        double[] out = new double[Bands.COUNT];
        for (int b = 0; b < Bands.COUNT; b++) out[b] = lossDb(b, detour, bends, bendSpan);
        return out;
    }
}
