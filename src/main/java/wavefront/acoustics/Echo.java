package wavefront.acoustics;

/**
 * A distinct echo: one clear repeat of a sound off a large flat surface, such as a cliff or
 * the far wall of a big cavern.
 *
 * A reflection is only heard as a separate echo when it arrives at least about 50 ms after
 * the sound itself; sooner than that the ear fuses the two and it is part of the reverb. In
 * distance, that means the reflected path must be at least about 17 blocks longer.
 *
 * Block worlds make the geometry simple: large flat surfaces face along x, y or z, so the
 * reflection is a mirror image of the source in a plane at a fixed coordinate.
 */
public final class Echo {
    private Echo() {}

    /** 50 ms at 343 blocks per second. */
    public static final double MIN_EXTRA_PATH = 0.05 * Bands.SPEED_OF_SOUND;

    public static final class Reflection {
        /** Where on the surface the sound bounces. */
        public final double[] point;
        /** Source to surface to listener, in blocks. */
        public final double pathLength;
        /** How much longer that is than the straight line. */
        public final double extraPath;

        Reflection(double[] point, double pathLength, double extraPath) {
            this.point = point;
            this.pathLength = pathLength;
            this.extraPath = extraPath;
        }

        /** True if this reflection arrives late enough to be heard as its own echo. */
        public boolean distinct() {
            return extraPath >= MIN_EXTRA_PATH;
        }
    }

    /**
     * The mirror reflection off the plane where coordinate {@code axis} (0 = x, 1 = y, 2 = z)
     * equals {@code at}. Returns null if the source and listener are not on the same side of
     * the plane, because then the surface cannot bounce one to the other.
     */
    public static Reflection reflect(double[] source, double[] listener, int axis, double at) {
        double ds = source[axis] - at, dl = listener[axis] - at;
        if (ds * dl <= 0) return null;
        double[] image = source.clone();
        image[axis] = at - ds;
        double path = PathFinder.dist(image, listener);
        // The bounce point is where the line from the mirror image to the listener crosses the plane.
        double t = (at - image[axis]) / (listener[axis] - image[axis]);
        double[] point = new double[3];
        for (int i = 0; i < 3; i++) point[i] = image[i] + (listener[i] - image[i]) * t;
        point[axis] = at;
        return new Reflection(point, path, path - PathFinder.dist(source, listener));
    }

    /**
     * Energy per band that survives the bounce, relative to an unobstructed sound over the same
     * path length: the surface keeps what it absorbs, and the air takes its share over the
     * whole path. {@code airStrength} is the air absorption setting (1 = real air).
     */
    public static double[] bounceEnergy(Reflection reflection, double[] surfaceAbsorption, double airStrength) {
        double[] energy = new double[Bands.COUNT];
        for (int b = 0; b < Bands.COUNT; b++) energy[b] = Math.max(0, 1.0 - surfaceAbsorption[b]);
        AirAbsorption.apply(energy, reflection.pathLength, airStrength);
        return energy;
    }

    /**
     * How loud the echo is next to the sound itself (linear amplitude): sound spreading over
     * the longer path makes it weaker in proportion to the two path lengths.
     */
    public static double spreading(double directLength, double echoLength) {
        return Math.max(1.0, directLength) / Math.max(1.0, echoLength);
    }
}
