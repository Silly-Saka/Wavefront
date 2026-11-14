package wavefront.acoustics;

/**
 * The result of one wave simulation: for every cell of air round the listener, how much bass
 * a source there would deliver, compared with the same source at the same distance in open air.
 * 1 means "as in open air", 0.1 means 10 dB quieter, 4 means 6 dB louder (for example a source
 * and a listener both near a hard floor, where the bounce off the floor adds to the direct sound).
 */
public final class BassField {
    /** A source is compared with the open cells up to this far from it, in blocks. Block sounds start inside a solid block. */
    private static final double REACH = 1.05;

    /** The result for one band. Bands can be worked out on grids of different fineness. */
    public static final class Layer {
        private final int n, ox, oy, oz, px, py, pz;
        /** NaN where there is no air or the burst never arrived. */
        private final float[] ratio;
        /** The average over all cells at each distance from the listener, one entry per block. */
        private final float[] usual;

        Layer(WaveGrid grid, float[] ratio, float[] usual) {
            this.usual = usual;
            this.n = grid.n;
            this.ox = grid.ox;
            this.oy = grid.oy;
            this.oz = grid.oz;
            this.px = grid.px;
            this.py = grid.py;
            this.pz = grid.pz;
            this.ratio = ratio;
        }

        /** The average value at this distance from the listener, or NaN when nothing is known that far out. */
        double usual(double distance) {
            int bin = (int) (distance + 0.5);
            return bin < 0 || bin >= usual.length ? Double.NaN : usual[bin];
        }

        /** The value for a source at this world position, or NaN when nothing is known there. */
        double sample(double x, double y, double z) {
            double fx = (x - ox) * n + 1, fy = (y - oy) * n + 1, fz = (z - oz) * n + 1;
            int cx = (int) Math.floor(fx), cy = (int) Math.floor(fy), cz = (int) Math.floor(fz);
            if (cx < 1 || cy < 1 || cz < 1 || cx > px - 2 || cy > py - 2 || cz > pz - 2) return Double.NaN;
            int reach = (int) Math.ceil(REACH * n);
            double sum = 0, weights = 0;
            for (int k = Math.max(1, cz - reach); k <= Math.min(pz - 2, cz + reach); k++) {
                for (int j = Math.max(1, cy - reach); j <= Math.min(py - 2, cy + reach); j++) {
                    for (int i = Math.max(1, cx - reach); i <= Math.min(px - 2, cx + reach); i++) {
                        float value = ratio[(k * py + j) * px + i];
                        if (value != value) continue; // NaN
                        double dx = (i + 0.5 - fx) / n, dy = (j + 0.5 - fy) / n, dz = (k + 0.5 - fz) / n;
                        double d2 = dx * dx + dy * dy + dz * dz;
                        if (d2 > REACH * REACH) continue;
                        double w = 1.0 / (0.3 + d2); // nearer cells count for more
                        sum += w * value;
                        weights += w;
                    }
                }
            }
            return weights <= 0 ? Double.NaN : sum / weights;
        }
    }

    private final Layer[] layers;
    /** Where the listener was when this was worked out. */
    public final double listenerX, listenerY, listenerZ;

    /** @param layers one per band, lowest first; an entry may be null if that band could not be done */
    public BassField(double lx, double ly, double lz, Layer... layers) {
        this.layers = layers;
        this.listenerX = lx;
        this.listenerY = ly;
        this.listenerZ = lz;
    }

    /** How many bands were simulated: 1 (63 Hz) or 2 (63 and 125 Hz). */
    public int bands() {
        return layers.length;
    }

    /** How far this position is from where the listener stood for the simulation, in blocks. */
    public double listenerMoved(double x, double y, double z) {
        double dx = x - listenerX, dy = y - listenerY, dz = z - listenerZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Bass energy for a source at this world position, per simulated band, compared with open
     * air at the same distance. An entry is NaN when nothing is known for that band there:
     * outside the grid, buried in solid blocks, or not reached by the burst in the time allowed.
     * Returns null when that goes for every band.
     */
    public double[] sample(double x, double y, double z) {
        double[] out = new double[layers.length];
        boolean any = false;
        for (int b = 0; b < layers.length; b++) {
            out[b] = layers[b] == null ? Double.NaN : layers[b].sample(x, y, z);
            any |= out[b] == out[b];
        }
        return any ? out : null;
    }

    /**
     * What this place does to bass at this position's distance from the listener, averaged over
     * every direction: the same figures as {@link #sample}, but for "a spot like any other that
     * far away". Entries are NaN where nothing is known.
     */
    public double[] usual(double x, double y, double z) {
        double distance = listenerMoved(x, y, z);
        double[] out = new double[layers.length];
        for (int b = 0; b < layers.length; b++) out[b] = layers[b] == null ? Double.NaN : layers[b].usual(distance);
        return out;
    }
}
