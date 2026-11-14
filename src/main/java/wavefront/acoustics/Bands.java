package wavefront.acoustics;

/**
 * The eight octave bands every acoustic value in the mod is expressed in.
 * Pure Java: nothing in this package may import Minecraft classes, so it can be
 * compiled and tested without the game.
 */
public final class Bands {
    private Bands() {}

    public static final int COUNT = 8;
    public static final double SPEED_OF_SOUND = 343.0; // m/s in air at 20 C
    public static final double[] HZ = {63, 125, 250, 500, 1000, 2000, 4000, 8000};
    public static final double[] WAVELENGTH = new double[COUNT];

    static {
        for (int i = 0; i < COUNT; i++) WAVELENGTH[i] = SPEED_OF_SOUND / HZ[i];
    }

    /** Converts a loss in decibels to the fraction of sound energy that gets through. */
    public static double dbToEnergy(double lossDb) {
        return Math.pow(10.0, -lossDb / 10.0);
    }

    /** Converts an energy fraction (0..1) to a loss in decibels. Capped so silence stays finite. */
    public static double energyToDb(double energy) {
        if (energy <= 1e-12) return 120.0;
        return -10.0 * Math.log10(energy);
    }

    public static double[] zeros() {
        return new double[COUNT];
    }
}
