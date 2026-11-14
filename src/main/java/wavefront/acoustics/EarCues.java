package wavefront.acoustics;

/**
 * How well a listener can tell which side a sound is on.
 *
 * The brain works out left from right mostly from the tiny gap between a sound reaching one
 * ear and the other. That gap is the width of the head divided by the speed of sound, times
 * the sine of the angle the sound is off to the side. In water sound is about four times
 * faster, so every gap shrinks to about a quarter: a sound directly to one side produces the
 * gap that a sound only 14 degrees off centre would in air. The head also stops shadowing the
 * far ear, so the loudness difference goes too.
 *
 * This is reproduced by moving each sound towards the middle: its sideways part is scaled by
 * the share of the gap that survives, and its distance is kept.
 */
public final class EarCues {
    private EarCues() {}

    /**
     * The share of the left-right cue that survives where sound travels at {@code speedOfSound}:
     * 1 in air, about 0.24 in water. {@code strength} blends from no effect (0) to the real
     * amount (1).
     */
    public static double sidewaysKept(double speedOfSound, double strength) {
        double real = Math.min(1.0, Bands.SPEED_OF_SOUND / Math.max(1.0, speedOfSound));
        double s = Math.max(0.0, Math.min(1.0, strength));
        return 1.0 + (real - 1.0) * s;
    }

    /**
     * Where a sound at (x, y, z) should be placed so that it is heard only {@code keep} as far
     * to the side as it really is. {@code right} and {@code forward} are unit vectors for the
     * listener's right-hand side and facing direction.
     */
    public static double[] compress(double[] ear, double[] right, double[] forward, double x, double y, double z, double keep) {
        double dx = x - ear[0], dy = y - ear[1], dz = z - ear[2];
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1e-6) return new double[]{x, y, z};
        dx /= distance;
        dy /= distance;
        dz /= distance;
        double sideways = dx * right[0] + dy * right[1] + dz * right[2];
        // What is left once the sideways part is taken out: front or back, up or down.
        double mx = dx - sideways * right[0], my = dy - sideways * right[1], mz = dz - sideways * right[2];
        double middle = Math.sqrt(mx * mx + my * my + mz * mz);
        if (middle < 1e-6) {
            // Exactly to one side: there is no front or back to keep, so lean it towards the front.
            mx = forward[0];
            my = forward[1];
            mz = forward[2];
            middle = 1.0;
        }
        double kept = sideways * keep;
        double rest = Math.sqrt(Math.max(0.0, 1.0 - kept * kept)) / middle;
        return new double[]{
                ear[0] + distance * (kept * right[0] + rest * mx),
                ear[1] + distance * (kept * right[1] + rest * my),
                ear[2] + distance * (kept * right[2] + rest * mz)};
    }
}
