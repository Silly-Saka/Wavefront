package wavefront.acoustics;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * How the size of an opening limits the sound that gets through it.
 *
 * The edge formula in {@link Diffraction} treats every edge as the side of an endless gap.
 * That is right for a wide opening, but a doorway or a one-block hole can only pass the sound
 * that actually lands on it. A small opening catches a small share of the source's sound and
 * then spreads it out again over the far side like a new, weaker source:
 *
 *   share through = opening area * L^2 / (2 * pi * r1^2 * r2^2)
 *
 * where r1 is the distance from the source to the opening, r2 from the opening to the
 * listener, and L = r1 + r2. The share is relative to an unobstructed sound over the same
 * distance. An opening that is large compared with the wavelength also beams the sound
 * forward, so this limit is tight for bass and loose for high notes. A route can never carry
 * more than the limit, so a narrow gap lets less bass through however little the path bends.
 *
 * A long narrow passage is different: its walls guide the sound along instead of letting it
 * spread, so openings deeper than {@link #MAX_DEPTH} blocks are left alone.
 */
public final class Aperture {
    private Aperture() {}

    /** How far from the route the search for the opening's rim goes, in blocks. */
    static final int REACH = 4;
    /** An opening this many blocks deep or more counts as a passage, not a hole in a wall. */
    static final int MAX_DEPTH = 3;

    /** The tightest opening on a route. {@code area} is in square blocks. */
    public static final class Opening {
        public final double area;
        /** Path length from the source to the opening, and from the opening to the listener. */
        public final double before, after;

        Opening(double area, double before, double after) {
            this.area = area;
            this.before = before;
            this.after = after;
        }

        /** The share passed when the opening spreads sound evenly over the far side (0..1). */
        public double evenShare() {
            double r1 = Math.max(0.5, before), r2 = Math.max(0.5, after);
            double total = r1 + r2;
            return Math.min(1.0, area * total * total / (2.0 * Math.PI * r1 * r1 * r2 * r2));
        }

        /**
         * The largest share of sound energy this opening can send in any one direction, for one
         * band (0..1). An opening much larger than the wavelength beams the sound forward, by a
         * factor of 2*pi*area / wavelength^2, so high notes are barely limited while bass is.
         */
        public double energyLimit(int band) {
            double wavelength = Bands.WAVELENGTH[band];
            double beaming = Math.max(1.0, 2.0 * Math.PI * area / (wavelength * wavelength));
            return Math.min(1.0, evenShare() * beaming);
        }
    }

    /**
     * Finds the smallest opening the route squeezes through, or null if it never passes through
     * one (for example when it only goes over the top of a wall under open sky).
     */
    public static Opening tightest(PathFinder.World world, PathFinder.Route route) {
        Opening best = null;
        double travelled = 0;
        double[][] p = route.points;
        for (int i = 1; i < p.length - 1; i++) {
            travelled += PathFinder.dist(p[i - 1], p[i]);
            // Look just before and just after the bend: one of the two lies inside the opening.
            double area = Math.min(areaNear(world, toward(p[i], p[i - 1], 0.45)), areaNear(world, toward(p[i], p[i + 1], 0.45)));
            if (Double.isInfinite(area)) continue;
            if (best == null || area < best.area) best = new Opening(area, travelled, route.length - travelled);
        }
        return best;
    }

    /**
     * Smallest bounded cross-section at this point. A route hugs the rim of an opening, so the
     * point can sit exactly on the boundary between the opening and the wall beside it; cells a
     * short step to each side are tried as well.
     */
    static double areaNear(PathFinder.World world, double[] point) {
        double best = Double.POSITIVE_INFINITY;
        Set<Long> tried = new HashSet<>();
        for (int k = 0; k < 7; k++) {
            double ox = k == 1 ? NUDGE : k == 2 ? -NUDGE : 0, oy = k == 3 ? NUDGE : k == 4 ? -NUDGE : 0, oz = k == 5 ? NUDGE : k == 6 ? -NUDGE : 0;
            int x = PathFinder.floor(point[0] + ox), y = PathFinder.floor(point[1] + oy), z = PathFinder.floor(point[2] + oz);
            if (tried.add(PathFinder.key(x, y, z))) best = Math.min(best, areaOfCell(world, x, y, z));
        }
        return best;
    }

    private static final double NUDGE = 0.15;

    /** Smallest bounded cross-section through this cell, trying all three directions. Infinite if none. */
    static double areaOfCell(PathFinder.World world, int x, int y, int z) {
        if (!world.isOpen(x, y, z)) return Double.POSITIVE_INFINITY;
        double best = Double.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            int area = crossSection(world, x, y, z, axis);
            if (area < 0) continue;
            // Count how deep the opening is along this direction; a deep one is a passage.
            int depth = 1;
            for (int dir = -1; dir <= 1; dir += 2) {
                for (int step = 1; step < MAX_DEPTH; step++) {
                    int nx = x + (axis == 0 ? dir * step : 0), ny = y + (axis == 1 ? dir * step : 0), nz = z + (axis == 2 ? dir * step : 0);
                    if (!world.isOpen(nx, ny, nz)) break;
                    int next = crossSection(world, nx, ny, nz, axis);
                    if (next < 0 || next > area * 2) break;
                    depth++;
                }
            }
            if (depth >= MAX_DEPTH) continue;
            best = Math.min(best, area);
        }
        return best;
    }

    /**
     * Counts the open cells connected to (x, y, z) within the flat slice at right angles to
     * {@code axis}. Returns -1 if the open area runs past {@link #REACH}, meaning this slice is
     * not a bounded opening.
     */
    static int crossSection(PathFinder.World world, int x, int y, int z, int axis) {
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{0, 0});
        seen.add(((long) 64 << 8) | 64);
        int count = 0;
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            if (Math.abs(c[0]) > REACH || Math.abs(c[1]) > REACH) return -1;
            count++;
            for (int k = 0; k < 4; k++) {
                int u = c[0] + (k == 0 ? 1 : k == 1 ? -1 : 0), v = c[1] + (k == 2 ? 1 : k == 3 ? -1 : 0);
                long key = ((long) (u + 64) << 8) | (v + 64);
                if (seen.contains(key)) continue;
                int cx = x + (axis == 0 ? 0 : u), cy = y + (axis == 1 ? 0 : axis == 0 ? u : v), cz = z + (axis == 2 ? 0 : v);
                if (!world.isOpen(cx, cy, cz)) continue;
                seen.add(key);
                queue.add(new int[]{u, v});
            }
        }
        return count;
    }

    private static double[] toward(double[] from, double[] to, double distance) {
        double d = PathFinder.dist(from, to);
        if (d < 1e-9) return from.clone();
        double t = Math.min(1.0, distance / d);
        return new double[]{from[0] + (to[0] - from[0]) * t, from[1] + (to[1] - from[1]) * t, from[2] + (to[2] - from[2]) * t};
    }
}
