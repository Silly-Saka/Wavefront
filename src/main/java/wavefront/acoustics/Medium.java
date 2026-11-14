package wavefront.acoustics;

/**
 * Something sound travels through: air, water or lava. Two numbers describe it for this mod:
 * how fast sound moves in it, and how dense it is. Their product, the acoustic impedance, sets
 * how much sound gets across when one medium meets another.
 */
public final class Medium {
    public final String name;
    /** Speed of sound in blocks (metres) per second. */
    public final double speed;
    /** Density in kg per cubic metre. */
    public final double density;

    public static final Medium AIR = new Medium("air", Bands.SPEED_OF_SOUND, 1.204);

    public Medium(String name, double speed, double density) {
        this.name = name;
        this.speed = speed;
        this.density = density;
    }

    public double impedance() {
        return speed * density;
    }

    /**
     * Loss in dB for sound crossing straight through the boundary between two media. Only the
     * share 4*Z1*Z2 / (Z1 + Z2)^2 of the energy gets across; the rest bounces back. Between air
     * and water that share is about a thousandth, 29.4 dB, the same in either direction.
     */
    public static double crossingLossDb(Medium a, Medium b) {
        double za = a.impedance(), zb = b.impedance();
        return 10.0 * Math.log10((za + zb) * (za + zb) / (4.0 * za * zb));
    }
}
