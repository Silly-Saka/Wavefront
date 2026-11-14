package wavefront.acoustics;

/**
 * Adds up how much sound is lost along a straight line that passes through blocks.
 * Feed it one call per block the line crosses, then read the total per band.
 */
public final class Occlusion {
    /** Sound energy that survives so far, per band (1 = nothing lost). */
    private final double[] transmitted = new double[Bands.COUNT];
    private boolean touchedSolid = false;

    public Occlusion() {
        java.util.Arrays.fill(transmitted, 1.0);
    }

    /**
     * A block whose real shape the line crosses for {@code thickness} metres.
     * Used for ordinary blocks: full cubes, slabs, stairs, doors, carpets.
     */
    public void throughSolid(AcousticMaterial material, double thickness) {
        if (thickness <= 1e-6) return;
        touchedSolid = true;
        for (int b = 0; b < Bands.COUNT; b++) {
            transmitted[b] *= Bands.dbToEnergy(material.gameLoss(b, thickness));
        }
    }

    /**
     * A block that is full of holes (bars, fences, grates). {@code fill} is the fraction of the
     * cell that is solid. The open part passes sound untouched, so such a block can never block
     * more than {@code -10*log10(1 - fill)} dB however dense its material is.
     */
    public void throughPartlyOpen(AcousticMaterial material, double fill) {
        if (fill <= 0) return;
        touchedSolid = true;
        double open = 1.0 - Math.min(1.0, fill);
        for (int b = 0; b < Bands.COUNT; b++) {
            transmitted[b] *= open + fill * Bands.dbToEnergy(material.gameLoss1m(b));
        }
    }

    /** True once the line has crossed anything that blocks sound at all. */
    public boolean blocked() {
        return touchedSolid;
    }

    /** Fraction of energy that gets through, per band. */
    public double[] energy() {
        return transmitted.clone();
    }

    /** Loss in dB, per band. */
    public double[] lossDb() {
        double[] out = new double[Bands.COUNT];
        for (int b = 0; b < Bands.COUNT; b++) out[b] = Bands.energyToDb(transmitted[b]);
        return out;
    }

    /**
     * Length of the part of the segment from A to B that lies inside any of the given boxes.
     * Each box is {minX, minY, minZ, maxX, maxY, maxZ}. Overlapping boxes are not double counted.
     * This is how the real shape of stairs, slabs and doors turns into a thickness.
     */
    public static double lengthInsideBoxes(double ax, double ay, double az,
                                           double bx, double by, double bz, double[][] boxes) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9 || boxes.length == 0) return 0;

        // Collect the [enter, exit] interval (as fractions of the segment) for every box hit.
        double[] starts = new double[boxes.length];
        double[] ends = new double[boxes.length];
        int n = 0;
        for (double[] box : boxes) {
            double t0 = 0, t1 = 1;
            boolean hit = true;
            double[] origin = {ax, ay, az};
            double[] dir = {dx, dy, dz};
            for (int axis = 0; axis < 3 && hit; axis++) {
                double lo = box[axis], hi = box[axis + 3];
                if (Math.abs(dir[axis]) < 1e-12) {
                    if (origin[axis] < lo || origin[axis] > hi) hit = false;
                } else {
                    double tA = (lo - origin[axis]) / dir[axis];
                    double tB = (hi - origin[axis]) / dir[axis];
                    if (tA > tB) { double tmp = tA; tA = tB; tB = tmp; }
                    t0 = Math.max(t0, tA);
                    t1 = Math.min(t1, tB);
                    if (t0 > t1) hit = false;
                }
            }
            if (hit && t1 > t0) {
                starts[n] = t0;
                ends[n] = t1;
                n++;
            }
        }
        if (n == 0) return 0;

        // Merge overlapping intervals (few boxes per block, so a simple sort is fine).
        for (int i = 1; i < n; i++) {
            double s = starts[i], e = ends[i];
            int j = i - 1;
            while (j >= 0 && starts[j] > s) {
                starts[j + 1] = starts[j];
                ends[j + 1] = ends[j];
                j--;
            }
            starts[j + 1] = s;
            ends[j + 1] = e;
        }
        double total = 0, curStart = starts[0], curEnd = ends[0];
        for (int i = 1; i < n; i++) {
            if (starts[i] <= curEnd) {
                curEnd = Math.max(curEnd, ends[i]);
            } else {
                total += curEnd - curStart;
                curStart = starts[i];
                curEnd = ends[i];
            }
        }
        total += curEnd - curStart;
        return total * len;
    }
}
