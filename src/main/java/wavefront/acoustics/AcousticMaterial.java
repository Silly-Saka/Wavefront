package wavefront.acoustics;

/**
 * One acoustic material from the block database (stone, wool, glass, ...).
 * All arrays have one value per band in {@link Bands#HZ}.
 */
public final class AcousticMaterial {
    public final String name;
    /** Fraction of sound energy a surface soaks up when sound bounces off it (0..1). */
    public final double[] absorption;
    /** Fraction of the reflected sound that scatters instead of bouncing like a mirror (0..1). */
    public final double[] scattering;
    /** Real-world loss in dB through a 1 m thick block. */
    public final double[] realLoss1m;
    /** Real-world loss in dB through a 5 cm panel. */
    public final double[] realLossThin;
    public final double density;
    public final double soundSpeed;
    public final double lossFactor;

    // Gameplay losses, recomputed whenever the wall-strength setting changes.
    volatile double[] gameLoss1m = Bands.zeros();
    volatile double[] gameLossThin = Bands.zeros();

    AcousticMaterial(String name, double[] absorption, double[] scattering, double[] realLoss1m,
                     double[] realLossThin, double density, double soundSpeed, double lossFactor) {
        this.name = name;
        this.absorption = absorption;
        this.scattering = scattering;
        this.realLoss1m = realLoss1m;
        this.realLossThin = realLossThin;
        this.density = density;
        this.soundSpeed = soundSpeed;
        this.lossFactor = lossFactor;
    }

    /** Gameplay loss in dB through one full block (1 m) of this material. */
    public double gameLoss1m(int band) {
        return gameLoss1m[band];
    }

    /** Gameplay loss in dB through a thin 5 cm panel of this material. */
    public double gameLossThin(int band) {
        return gameLossThin[band];
    }

    /**
     * Gameplay loss in dB for sound crossing {@code thickness} metres of this material.
     * Thin parts (doors, carpets) use the panel figure, a full block uses the 1 m figure,
     * in between is blended, and several blocks in a row add up.
     */
    public double gameLoss(int band, double thickness) {
        if (thickness <= 0) return 0;
        double full = gameLoss1m[band];
        double thin = Math.min(gameLossThin[band], full); // keep "thicker never blocks less"
        if (thickness >= 1.0) return full * thickness;
        if (thickness <= THIN) return thin * (thickness / THIN);
        double t = Math.log(thickness / THIN) / LOG_RANGE; // 0 at 5 cm, 1 at 1 m
        return thin + (full - thin) * t;
    }

    static final double THIN = 0.05;
    private static final double LOG_RANGE = Math.log(1.0 / THIN);
}
