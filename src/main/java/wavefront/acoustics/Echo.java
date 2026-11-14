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
        /** Cosine of the angle between the sound and a line straight into the surface (1 = head on). */
        public final double cosIncidence;

        Reflection(double[] point, double pathLength, double extraPath, double cosIncidence) {
            this.point = point;
            this.pathLength = pathLength;
            this.extraPath = extraPath;
            this.cosIncidence = cosIncidence;
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
        double cos = path < 1e-9 ? 1.0 : Math.min(1.0, (Math.abs(ds) + Math.abs(dl)) / path);
        return new Reflection(point, path, path - PathFinder.dist(source, listener), cos);
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
     * The share of sound a surface does not send back, per band. A material's absorption figure
     * covers what it soaks up; anything that passes straight through is lost to the echo as well,
     * which is what stops leaves, with their low absorption, from echoing like a wall.
     *
     * @param lossThroughDb real-world loss in dB for sound passing through one block of the material
     */
    public static double[] notReflected(double[] absorption, double[] lossThroughDb) {
        double[] out = new double[Bands.COUNT];
        for (int b = 0; b < Bands.COUNT; b++) {
            out[b] = Math.min(1.0, Math.max(absorption[b], Bands.dbToEnergy(lossThroughDb[b])));
        }
        return out;
    }

    // ------------------------------------------------------------------ uneven surfaces

    /** Marks a column of the patch where no surface was found. */
    public static final int NO_SURFACE = Integer.MIN_VALUE;
    /** An uneven patch must have a surface over at least this share of its area to echo at all. */
    public static final double MIN_ROUGH_COVERAGE = 0.4;
    /**
     * A rough surface scatters part of the sound. That part still comes back, but spread over a
     * few hundredths of a second instead of as one sharp repeat. The echo here is a single clean
     * copy, so only this share of the scattered energy is counted towards it. The value is a
     * judgement, not a measurement.
     */
    public static final double SCATTERED_SHARE = 0.5;

    /** The piece of surface around a bounce point, described by how deep each column's face lies. */
    public static final class Patch {
        /** Columns that have a surface. */
        public final int count;
        /** Share of the patch that has a surface (0..1). */
        public final double coverage;
        /** Average depth of the surface, in blocks, relative to the first guess of where it is. */
        public final double meanDepth;
        /** How uneven it is: the standard deviation of the depth, in blocks. 0 is perfectly flat. */
        public final double roughness;
        /** Depth of the part that sticks out furthest towards the sound. */
        public final int nearest;

        Patch(int count, double coverage, double meanDepth, double roughness, int nearest) {
            this.count = count;
            this.coverage = coverage;
            this.meanDepth = meanDepth;
            this.roughness = roughness;
            this.nearest = nearest;
        }
    }

    /**
     * Sums up a patch of surface. {@code depths} holds one entry per column: how many blocks
     * further away (positive) or nearer (negative) that column's face is than the first guess,
     * or {@link #NO_SURFACE}.
     */
    public static Patch patch(int[] depths) {
        int count = 0, nearest = Integer.MAX_VALUE;
        double sum = 0;
        for (int d : depths) {
            if (d == NO_SURFACE) continue;
            count++;
            sum += d;
            nearest = Math.min(nearest, d);
        }
        if (count == 0) return new Patch(0, 0, 0, 0, 0);
        double mean = sum / count, squares = 0;
        for (int d : depths) {
            if (d != NO_SURFACE) squares += (d - mean) * (d - mean);
        }
        return new Patch(count, (double) count / depths.length, mean, Math.sqrt(squares / count), nearest);
    }

    /**
     * The share of an echo's energy an uneven patch returns, for one band (0..1).
     *
     * The part that still reflects like a mirror follows the standard roughness rule
     * (Rayleigh/Ament): exp(-(4 * pi * roughness * cos(angle) / wavelength)^2). Bumps much
     * smaller than the wavelength leave it untouched, so bass survives and high notes scatter.
     * The scattered part is counted at {@link #SCATTERED_SHARE}, and the whole is scaled by how
     * much of the patch is there at all.
     */
    public static double surfaceReturn(int band, Patch patch, double cosIncidence) {
        double g = 4.0 * Math.PI * patch.roughness * cosIncidence / Bands.WAVELENGTH[band];
        double mirror = Math.exp(-g * g);
        return patch.coverage * (mirror + (1.0 - mirror) * SCATTERED_SHARE);
    }

    /**
     * How loud the echo is next to the sound itself (linear amplitude): sound spreading over
     * the longer path makes it weaker in proportion to the two path lengths.
     */
    public static double spreading(double directLength, double echoLength) {
        return Math.max(1.0, directLength) / Math.max(1.0, echoLength);
    }
}
