package wavefront.acoustics;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/**
 * A wave simulation of bass. The space round the listener is cut into small cubes of air, and
 * a short burst of pressure is let loose and followed step by step as it spreads, squeezes
 * through openings, bends round edges and bounces off walls. Nothing about bending or openings
 * is put in by formula: it comes out of the stepping, the way it does in real air.
 *
 * The burst starts at the listener, not at a sound source. Sound gets from A to B exactly as
 * well as from B to A, so one run from the listener gives the answer for every source position
 * at once, including sources that have not started playing yet.
 *
 * Only bass is worth doing this way. The cubes have to be several times smaller than the
 * wavelength, and the work grows with the fourth power of the pitch.
 */
public final class WaveGrid {
    /** Runs a job in several parts, possibly on several threads, and returns when all are done. */
    public interface Runner {
        void run(int parts, IntConsumer body);
    }

    public static final Runner SERIAL = (parts, body) -> {
        for (int i = 0; i < parts; i++) body.accept(i);
    };

    /** The bands that can be simulated, lowest first: 63 Hz, then 125 Hz. */
    public static final int MAX_BANDS = 2;

    /** How far the wave moves per step, in cells. Above 1/sqrt(3) the stepping blows up. */
    static final float COURANT = 0.5735f;
    private static final float L2 = COURANT * COURANT;
    private static final float FREE = 2f - 6f * L2;
    /** Pressures smaller than this are set to zero. Far smaller numbers are very slow to compute with. */
    private static final float TINY = 1e-18f;
    /** After a sound first arrives, this much more is counted as part of it. Later arrivals are reverb. */
    private static final double EARLY_SECONDS = 0.050;
    /** The run lasts long enough for a path this much longer than a straight line to the far corner. */
    private static final double DETOUR = 1.25;
    /** A cell counts as reached once the pressure passes this fraction of the open-air energy there (-50 dB). */
    private static final double QUIET = 1e-5;
    /** Width of the burst in periods of its centre pitch. Gives it roughly an octave of spread. */
    private static final double BURST_PERIODS = 0.45;

    /** Cells per block along each side. */
    public final int n;
    /** Size in blocks, and the world position of the lowest corner. */
    public final int bx, by, bz, ox, oy, oz;
    // The arrays have one spare layer of cells all round, so the stepping never has to check for the edge.
    final int px, py, pz, pxy, cells;
    /** 0: air. 1: solid. 2: outside the grid. */
    private final byte[] kind;
    private final byte[] plate;
    /** How readily the surface of each cell's block lets sound in (0 = perfect mirror, 1 = swallows it all). */
    private final float[] admittance;
    /** Bit d set: pressure can pass to the neighbour in direction d (-x, +x, -y, +y, -z, +z). */
    public byte[] mask;
    private float[] loss;

    public WaveGrid(int ox, int oy, int oz, int bx, int by, int bz, int n) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.bx = bx;
        this.by = by;
        this.bz = bz;
        this.n = n;
        this.px = bx * n + 2;
        this.py = by * n + 2;
        this.pz = bz * n + 2;
        this.pxy = px * py;
        this.cells = pxy * pz;
        this.kind = new byte[cells];
        this.plate = new byte[cells];
        this.admittance = new float[cells];
        Arrays.fill(kind, (byte) 2);
        for (int z = 1; z < pz - 1; z++)
            for (int y = 1; y < py - 1; y++)
                Arrays.fill(kind, (z * py + y) * px + 1, (z * py + y) * px + px - 1, (byte) 0);
    }

    /**
     * Turns the share of sound a surface soaks up (0..1) into the figure the stepping needs.
     * Stone soaks up about 2 percent of bass, so it comes out close to a perfect mirror.
     */
    public static float admittanceFor(double absorption) {
        double reflected = Math.sqrt(1.0 - Math.max(0.0, Math.min(0.999, absorption)));
        return (float) ((1.0 - reflected) / (1.0 + reflected));
    }

    /** Marks a whole block as solid. Positions are world block coordinates; outside the grid is ignored. */
    public void setFull(int wx, int wy, int wz, float surface) {
        int x = wx - ox, y = wy - oy, z = wz - oz;
        if (x < 0 || y < 0 || z < 0 || x >= bx || y >= by || z >= bz) return;
        for (int c = 0; c < n; c++)
            for (int b = 0; b < n; b++)
                for (int a = 0; a < n; a++) {
                    int i = ((z * n + c + 1) * py + y * n + b + 1) * px + x * n + a + 1;
                    kind[i] = 1;
                    admittance[i] = surface;
                }
    }

    /** Puts a partly solid block (door, slab, pane) into the grid. */
    public void setShape(int wx, int wy, int wz, WaveShape shape, float surface) {
        if (shape.n != n) throw new IllegalArgumentException("shape made for a different cell size");
        if (shape.open) return;
        int x = wx - ox, y = wy - oy, z = wz - oz;
        if (x < 0 || y < 0 || z < 0 || x >= bx || y >= by || z >= bz) return;
        for (int c = 0; c < n; c++)
            for (int b = 0; b < n; b++)
                for (int a = 0; a < n; a++) {
                    int i = ((z * n + c + 1) * py + y * n + b + 1) * px + x * n + a + 1;
                    int s = (c * n + b) * n + a;
                    kind[i] = (byte) (shape.solid[s] ? 1 : 0);
                    plate[i] = shape.plates[s];
                    admittance[i] = surface;
                }
    }

    /** Works out, for every cell of air, which neighbours it can pass pressure to. Call once the blocks are in. */
    public void build() {
        mask = new byte[cells];
        loss = new float[cells];
        int[] offset = {-1, 1, -px, px, -pxy, pxy};
        for (int z = 1; z < pz - 1; z++) {
            for (int y = 1; y < py - 1; y++) {
                for (int x = 1; x < px - 1; x++) {
                    int i = (z * py + y) * px + x;
                    if (kind[i] != 0) continue;
                    int open = 0;
                    float soaked = 0;
                    for (int d = 0; d < 6; d++) {
                        int j = i + offset[d];
                        boolean plateHere = (plate[i] & (1 << d)) != 0;
                        boolean plateThere = kind[j] == 0 && (plate[j] & (1 << (d ^ 1))) != 0;
                        if (kind[j] == 0 && !plateHere && !plateThere) open |= 1 << d;
                        else if (kind[j] == 2) soaked += 1f; // the edge of the grid lets sound leave
                        else if (kind[j] == 1) soaked += admittance[j];
                        else soaked += plateHere ? admittance[i] : admittance[j];
                    }
                    mask[i] = (byte) open;
                    loss[i] = COURANT / 2f * soaked;
                }
            }
        }
    }

    /** True if this world position is in a cell of air that connects to at least one neighbour. */
    public boolean isOpen(double x, double y, double z) {
        int i = cellAt(x, y, z);
        return i >= 0 && mask[i] != 0;
    }

    private int cellAt(double x, double y, double z) {
        int cx = (int) Math.floor((x - ox) * n) + 1, cy = (int) Math.floor((y - oy) * n) + 1, cz = (int) Math.floor((z - oz) * n) + 1;
        if (cx < 1 || cy < 1 || cz < 1 || cx > px - 2 || cy > py - 2 || cz > pz - 2) return -1;
        return (cz * py + cy) * px + cx;
    }

    /** The open cell at this position, or the nearest one within a block of it, or -1. */
    private int openCellNear(double x, double y, double z) {
        int here = cellAt(x, y, z);
        if (here < 0) return -1;
        if (mask[here] != 0) return here;
        double fx = (x - ox) * n + 1, fy = (y - oy) * n + 1, fz = (z - oz) * n + 1;
        int cx = here % px, cy = (here / px) % py, cz = here / pxy;
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int k = Math.max(1, cz - n); k <= Math.min(pz - 2, cz + n); k++)
            for (int j = Math.max(1, cy - n); j <= Math.min(py - 2, cy + n); j++)
                for (int i = Math.max(1, cx - n); i <= Math.min(px - 2, cx + n); i++) {
                    int c = (k * py + j) * px + i;
                    if (mask[c] == 0) continue;
                    double d = sq(i + 0.5 - fx) + sq(j + 0.5 - fy) + sq(k + 0.5 - fz);
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = c;
                    }
                }
        return best;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double pow4(double v) {
        return v * v * v * v;
    }

    /**
     * Runs the simulation for one band from the listener's position.
     *
     * @param band  0 for 63 Hz, 1 for 125 Hz. 125 Hz needs at least 2 cells per block to come out right.
     * @param parts how many pieces each step is split into for the runner
     * @return the result for that band, or null if the listener is not in open air inside the grid
     */
    public BassField.Layer solve(int band, double lx, double ly, double lz, int parts, Runner runner) {
        int source = openCellNear(lx, ly, lz);
        if (source < 0) return null;
        Work work = new Work(cells);
        return new BassField.Layer(this, simulate(band, source, work, Math.max(1, parts), runner));
    }

    /** Runs every band on this one grid: 63 Hz alone for bands = 1, plus 125 Hz for bands = 2. */
    public BassField solveAll(double lx, double ly, double lz, int bands, int parts, Runner runner) {
        int source = openCellNear(lx, ly, lz);
        if (source < 0) return null;
        bands = Math.max(1, Math.min(MAX_BANDS, bands));
        Work work = new Work(cells);
        BassField.Layer[] layers = new BassField.Layer[bands];
        for (int b = 0; b < bands; b++) layers[b] = new BassField.Layer(this, simulate(b, source, work, Math.max(1, parts), runner));
        return new BassField(lx, ly, lz, layers);
    }

    /** Scratch space for one run, reused from band to band. */
    private static final class Work {
        float[] now, other, energy;
        final int[] arrived;

        Work(int cells) {
            now = new float[cells];
            other = new float[cells];
            energy = new float[cells];
            arrived = new int[cells];
        }

        void clear() {
            Arrays.fill(now, 0f);
            Arrays.fill(other, 0f);
            Arrays.fill(energy, 0f);
            Arrays.fill(arrived, -1);
        }
    }

    /** The burst of pressure for one band: a few cycles of its centre pitch that swell and fade. */
    private static final class Burst {
        final double hz, dt, width, middle;
        final int steps;

        Burst(int band, int n) {
            hz = Bands.HZ[band];
            dt = COURANT * (1.0 / n) / Bands.SPEED_OF_SOUND;
            width = BURST_PERIODS / hz;
            middle = 3.5 * width;
            steps = (int) Math.ceil(2 * middle / dt);
        }

        float at(int step) {
            double t = step * dt - middle;
            return (float) (Math.exp(-t * t / (2 * width * width)) * Math.sin(2 * Math.PI * hz * t));
        }
    }

    private float[] simulate(int band, int source, Work work, int parts, Runner runner) {
        Burst burst = new Burst(band, n);
        int window = burst.steps + (int) Math.ceil(EARLY_SECONDS / burst.dt);
        int sx = source % px, sy = (source / px) % py, sz = source / pxy;
        double far = Math.sqrt(sq(Math.max(sx - 1, px - 2 - sx)) + sq(Math.max(sy - 1, py - 2 - sy)) + sq(Math.max(sz - 1, pz - 2 - sz)));
        int steps = (int) Math.ceil(far * DETOUR / COURANT) + window;
        Calibration open = calibration(n, band);
        float reached = (float) (open.peakTimesDistance * open.peakTimesDistance * QUIET);

        work.clear();
        run(source, steps, window, reached, burst, work, parts, runner, null);

        float[] ratio = new float[cells];
        Arrays.fill(ratio, Float.NaN);
        float[] energy = work.energy;
        int[] arrived = work.arrived;
        for (int z = 1; z < pz - 1; z++) {
            for (int y = 1; y < py - 1; y++) {
                for (int x = 1; x < px - 1; x++) {
                    int i = (z * py + y) * px + x;
                    if (mask[i] == 0) continue;
                    int r2 = (x - sx) * (x - sx) + (y - sy) * (y - sy) + (z - sz) * (z - sz);
                    if (r2 < 9) {
                        ratio[i] = 1f; // right next to the listener nothing has had room to change
                    } else if (arrived[i] >= 0 && arrived[i] + burst.steps <= steps) {
                        double quartic = (pow4(x - sx) + pow4(y - sy) + pow4(z - sz)) / ((double) r2 * r2);
                        ratio[i] = (float) (energy[i] * (double) r2 / open.energyTimesDistance2(quartic));
                    }
                    // otherwise: the burst never got here in the time allowed, so nothing is known
                }
            }
        }
        return ratio;
    }

    /** Steps the wave. The burst is fed in at the source cell; energy is added up per cell from when it first arrives. */
    private void run(int source, int steps, int window, float reached, Burst burst, Work work, int parts, Runner runner,
                     java.util.function.Consumer<float[]> afterStep) {
        int sx = source % px, sy = (source / px) % py, sz = source / pxy;
        for (int step = 0; step < steps; step++) {
            // Each step passes pressure one cell along one axis, so nothing can be further than
            // "step" cells away counted along the three axes. Only that much needs stepping.
            int reach = step + 1;
            int x0 = Math.max(1, sx - reach), x1 = Math.min(px - 2, sx + reach);
            int y0 = Math.max(1, sy - reach), y1 = Math.min(py - 2, sy + reach);
            int z0 = Math.max(1, sz - reach), z1 = Math.min(pz - 2, sz + reach);
            float[] now = work.now, other = work.other;
            int layers = z1 - z0 + 1;
            long volume = (long) layers * (y1 - y0 + 1) * (x1 - x0 + 1);
            int split = volume < 60_000 ? 1 : Math.min(parts, layers);
            int at = step;
            if (split <= 1) {
                slab(now, other, work, z0, z1, y0, y1, x0, x1, sx, sy, sz, reach, at, window, reached);
            } else {
                runner.run(split, k -> slab(now, other, work, z0 + layers * k / split, z0 + layers * (k + 1) / split - 1,
                        y0, y1, x0, x1, sx, sy, sz, reach, at, window, reached));
            }
            if (step < burst.steps) other[source] += burst.at(step);
            if (afterStep != null) afterStep.accept(other);
            work.now = other;
            work.other = now;
        }
    }

    /**
     * One step for a range of layers. For each cell of air the new pressure comes from its own
     * pressure now, its pressure one step ago, and the pressures of the neighbours it is open to.
     * Closed sides bounce the wave back, minus what the surface soaks up.
     * (Scheme with boundary losses after Webb and Bilbao, 2011.)
     */
    private void slab(float[] now, float[] other, Work work, int z0, int z1, int y0, int y1, int xLow, int xHigh,
                      int sx, int sy, int sz, int reach, int step, int window, float reached) {
        final byte[] mask = this.mask;
        final float[] loss = this.loss, energy = work.energy;
        final int[] arrived = work.arrived;
        final int px = this.px, pxy = this.pxy;
        for (int z = z0; z <= z1; z++) {
            int dz2 = (z - sz) * (z - sz);
            for (int y = y0; y <= y1; y++) {
                int dyz2 = dz2 + (y - sy) * (y - sy);
                int row = (z * py + y) * px;
                int left = reach - Math.abs(z - sz) - Math.abs(y - sy);
                if (left < 0) continue;
                int x0 = Math.max(xLow, sx - left), x1 = Math.min(xHigh, sx + left);
                for (int x = x0; x <= x1; x++) {
                    int i = row + x;
                    int m = mask[i];
                    float v;
                    if (m == 63) {
                        v = FREE * now[i] + L2 * (now[i - 1] + now[i + 1] + now[i - px] + now[i + px] + now[i - pxy] + now[i + pxy]) - other[i];
                    } else if (m == 0) {
                        continue;
                    } else {
                        float sum = 0;
                        int open = 0;
                        if ((m & 1) != 0) { sum += now[i - 1]; open++; }
                        if ((m & 2) != 0) { sum += now[i + 1]; open++; }
                        if ((m & 4) != 0) { sum += now[i - px]; open++; }
                        if ((m & 8) != 0) { sum += now[i + px]; open++; }
                        if ((m & 16) != 0) { sum += now[i - pxy]; open++; }
                        if ((m & 32) != 0) { sum += now[i + pxy]; open++; }
                        float l = loss[i];
                        v = ((2f - open * L2) * now[i] + L2 * sum + (l - 1f) * other[i]) / (1f + l);
                    }
                    int a = arrived[i];
                    if (a < 0) {
                        float size = Math.abs(v);
                        if (size < TINY) {
                            other[i] = 0f;
                            continue;
                        }
                        other[i] = v;
                        int dx = x - sx;
                        if (size * size * (dyz2 + dx * dx) <= reached) continue;
                        arrived[i] = a = step;
                    } else {
                        other[i] = v;
                    }
                    if (step - a <= window) energy[i] += v * v;
                }
            }
        }
    }

    // ------------------------------------------------------------------ what open air gives

    /** What the same burst does in open air, measured once per cell size and band. Everything else is compared to it. */
    static final class Calibration {
        /**
         * Energy collected at a cell, times its distance in cells squared. In open air this is the
         * same at every distance, but a grid of cubes carries a wave slightly differently along
         * its rows than across its diagonals, by up to 2 dB when the wavelength is only five
         * cells. So it is stored as a figure that depends on direction: the logarithm is
         * {@code base + slope * q}, where q is 1 along a row, 1/2 across a face and 1/3 from
         * corner to corner (the sum of the fourth powers of the direction's three parts).
         */
        final double base, slope;
        /** Highest pressure at a cell, times its distance in cells. */
        final double peakTimesDistance;

        Calibration(double base, double slope, double peakTimesDistance) {
            this.base = base;
            this.slope = slope;
            this.peakTimesDistance = peakTimesDistance;
        }

        double energyTimesDistance2(double quartic) {
            return Math.exp(base + slope * quartic);
        }
    }

    private static final Map<Integer, Calibration> CALIBRATIONS = new ConcurrentHashMap<>();
    private static final int NEAR = 8, FAR = 14;

    static Calibration calibration(int n, int band) {
        return CALIBRATIONS.computeIfAbsent(n * 16 + band, key -> calibrate(n, band));
    }

    private static Calibration calibrate(int n, int band) {
        Burst burst = new Burst(band, n);
        int steps = (int) Math.ceil(FAR / COURANT) + burst.steps + 10;
        // Big enough that nothing bounced off the edge of the grid gets back to the measuring cells in time.
        int half = (int) Math.ceil((COURANT * steps + FAR) / 2.0) + 3;
        int blocks = (2 * half + n) / n;
        WaveGrid grid = new WaveGrid(0, 0, 0, blocks, blocks, blocks, n);
        grid.build();
        int c = half + 1;
        int source = (c * grid.py + c) * grid.px + c;

        int count = 0;
        int[] index = new int[4 * FAR * FAR * FAR * 2];
        int[] distance2 = new int[index.length];
        for (int z = -FAR; z <= FAR; z++)
            for (int y = -FAR; y <= FAR; y++)
                for (int x = -FAR; x <= FAR; x++) {
                    int r2 = x * x + y * y + z * z;
                    if (r2 < NEAR * NEAR || r2 > FAR * FAR) continue;
                    index[count] = ((c + z) * grid.py + c + y) * grid.px + c + x;
                    distance2[count++] = r2;
                }
        double[] energy = new double[count];
        float[] peak = new float[count];
        int measured = count;
        Work work = new Work(grid.cells);
        work.clear();
        grid.run(source, steps, 0, Float.MAX_VALUE, burst, work, 1, SERIAL, field -> {
            for (int k = 0; k < measured; k++) {
                float v = field[index[k]];
                energy[k] += v * v;
                if (Math.abs(v) > peak[k]) peak[k] = Math.abs(v);
            }
        });
        // Fit a straight line through the logarithm of energy times distance squared, against q.
        double peakSum = 0, sumQ = 0, sumL = 0, sumQQ = 0, sumQL = 0;
        for (int k = 0; k < count; k++) {
            peakSum += peak[k] * Math.sqrt(distance2[k]);
            int at = index[k];
            int x = at % grid.px - c, y = (at / grid.px) % grid.py - c, z = at / grid.pxy - c;
            double q = (pow4(x) + pow4(y) + pow4(z)) / ((double) distance2[k] * distance2[k]);
            double l = Math.log(energy[k] * distance2[k]);
            sumQ += q;
            sumL += l;
            sumQQ += q * q;
            sumQL += q * l;
        }
        double slope = (count * sumQL - sumQ * sumL) / (count * sumQQ - sumQ * sumQ);
        double base = (sumL - slope * sumQ) / count;
        return new Calibration(base, slope, peakSum / count);
    }
}
