package wavefront.acoustics;

/**
 * How much air itself soaks up sound over distance. High notes fade much faster than low
 * notes, which is why distant thunder is only a rumble.
 *
 * Calculated with the standard formula from ISO 9613-1 (pure-tone absorption by oxygen and
 * nitrogen relaxation). The self-test checks it against the table in ISO 9613-2.
 */
public final class AirAbsorption {
    private AirAbsorption() {}

    /** Loss in dB per metre for each band, at 20 C and 50 % relative humidity. */
    public static final double[] DB_PER_METRE = new double[Bands.COUNT];

    static {
        for (int b = 0; b < Bands.COUNT; b++) DB_PER_METRE[b] = dbPerMetre(Bands.HZ[b], 20.0, 50.0);
    }

    /**
     * ISO 9613-1 absorption coefficient at sea-level pressure.
     *
     * @param hz       frequency
     * @param celsius  air temperature
     * @param humidity relative humidity in percent
     */
    public static double dbPerMetre(double hz, double celsius, double humidity) {
        double t = celsius + 273.15, t0 = 293.15, t01 = 273.16;
        double saturation = Math.pow(10.0, -6.8346 * Math.pow(t01 / t, 1.261) + 4.6151);
        double h = humidity * saturation; // molar concentration of water vapour, percent
        double oxygen = 24.0 + 4.04e4 * h * (0.02 + h) / (0.391 + h);
        double nitrogen = Math.pow(t / t0, -0.5) * (9.0 + 280.0 * h * Math.exp(-4.170 * (Math.pow(t / t0, -1.0 / 3.0) - 1.0)));
        double f2 = hz * hz;
        return 8.686 * f2 * (1.84e-11 * Math.sqrt(t / t0)
                + Math.pow(t / t0, -2.5) * (0.01275 * Math.exp(-2239.1 / t) / (oxygen + f2 / oxygen)
                + 0.1068 * Math.exp(-3352.0 / t) / (nitrogen + f2 / nitrogen)));
    }

    /**
     * Applies air absorption over {@code metres} to a set of band energies, in place.
     * {@code strength} scales the loss: 1 is real air, 0 turns it off, higher exaggerates it.
     */
    public static void apply(double[] energy, double metres, double strength) {
        if (strength <= 0 || metres <= 0) return;
        for (int b = 0; b < Bands.COUNT; b++) {
            energy[b] *= Bands.dbToEnergy(DB_PER_METRE[b] * metres * strength);
        }
    }
}
