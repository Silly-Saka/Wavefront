package wavefront.acoustics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Finds the routes sound can take through air from a source to the listener when the
 * straight line is blocked: through a doorway, over a wall, round a corner.
 *
 * Step 1 searches block by block (A*) for a route through open cells.
 * Step 2 pulls that route tight like a string, so it only bends where it touches an edge.
 * The bends are the diffraction edges; the tightened length gives the detour.
 */
public final class PathFinder {

    /** What the path finder needs to know about the world. Implemented on Minecraft's level in-game. */
    public interface World {
        /** True if sound can pass through this block cell at all (air, bars, an open doorway...). */
        boolean isOpen(int x, int y, int z);

        /** True if this cell is completely empty, so moving through it never needs an exact check. */
        boolean isEmpty(int x, int y, int z);

        /** Exact check against real block shapes: is the straight segment from A to B unobstructed? */
        boolean segmentClear(double ax, double ay, double az, double bx, double by, double bz);
    }

    /** One route from source to listener. */
    public static final class Route {
        /** Points from source (first) to listener (last); everything in between is a bend at an edge. */
        public final double[][] points;
        public final double length;
        public final int bends;
        /** Distance between the first and last bend (0 with a single bend). */
        public final double bendSpan;

        Route(double[][] points) {
            this.points = points;
            double len = 0;
            for (int i = 1; i < points.length; i++) len += dist(points[i - 1], points[i]);
            this.length = len;
            this.bends = points.length - 2;
            this.bendSpan = bends > 1 ? dist(points[1], points[points.length - 2]) : 0;
        }

        /** The last bend before the listener: the spot the sound appears to come from. */
        public double[] lastBend() {
            return points[points.length - 2];
        }
    }

    private final World world;
    private final int maxNodes;

    /** After a route is found, cells this close to its bends are closed off to force a different one. */
    private static final int EXCLUDE = 3;
    /** Two routes must arrive at least this far apart (radians, about 40 degrees) to both count. */
    private static final double MIN_ROUTE_ANGLE = 0.7;

    public PathFinder(World world, int maxNodes) {
        this.world = world;
        this.maxNodes = maxNodes;
    }

    /**
     * Finds up to {@code maxRoutes} distinct routes, shortest first. Returns an empty list when the
     * source is sealed off (or the search budget runs out).
     *
     * @param margin how many blocks outside the box around source and listener the search may wander
     */
    public List<Route> findRoutes(double[] source, double[] listener, int maxRoutes, int margin) {
        List<Route> routes = new ArrayList<>();
        Set<Long> blocked = new HashSet<>();
        long startCell = cellOf(source), goalCell = cellOf(listener);

        for (int attempt = 0; attempt < maxRoutes; attempt++) {
            List<long[]> cells = search(source, listener, blocked, margin);
            if (cells == null) break;
            Route route = tighten(source, listener, cells);
            if (route == null) break;

            // A route only counts as separate if it arrives from a clearly different direction.
            // Otherwise two paths over the same long edge would be counted as two sources of sound.
            boolean duplicate = false;
            for (Route other : routes) {
                if (route.bends > 0 && other.bends > 0
                        && angleBetween(listener, route.lastBend(), other.lastBend()) < MIN_ROUTE_ANGLE) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) routes.add(route);
            if (route.bends == 0) break; // straight line is clear: nothing else to find

            // Close off the cells around this route's bends so the next search finds another way round.
            for (int i = 1; i < route.points.length - 1; i++) {
                double[] p = route.points[i];
                int cx = floor(p[0]), cy = floor(p[1]), cz = floor(p[2]);
                for (int dx = -EXCLUDE; dx <= EXCLUDE; dx++)
                    for (int dy = -EXCLUDE; dy <= EXCLUDE; dy++)
                        for (int dz = -EXCLUDE; dz <= EXCLUDE; dz++) {
                            long key = key(cx + dx, cy + dy, cz + dz);
                            if (key != startCell && key != goalCell) blocked.add(key);
                        }
            }
        }
        routes.sort((a, b) -> Double.compare(a.length, b.length));
        return routes;
    }

    // ---------------------------------------------------------------- A* search

    private static final int[][] STEPS = buildSteps();

    private static int[][] buildSteps() {
        // 6 face moves and 12 edge-diagonal moves. Corner diagonals are left out: they add
        // little and would let a path squeeze through the point where blocks touch.
        List<int[]> steps = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    int n = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (n == 1 || n == 2) steps.add(new int[]{dx, dy, dz});
                }
        return steps.toArray(new int[0][]);
    }

    private static final class Node implements Comparable<Node> {
        final int x, y, z;
        final double g, f;
        final Node parent;

        Node(int x, int y, int z, double g, double f, Node parent) {
            this.x = x; this.y = y; this.z = z; this.g = g; this.f = f; this.parent = parent;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    private List<long[]> search(double[] source, double[] listener, Set<Long> blocked, int margin) {
        int sx = floor(source[0]), sy = floor(source[1]), sz = floor(source[2]);
        int gx = floor(listener[0]), gy = floor(listener[1]), gz = floor(listener[2]);
        int minX = Math.min(sx, gx) - margin, maxX = Math.max(sx, gx) + margin;
        int minY = Math.min(sy, gy) - margin, maxY = Math.max(sy, gy) + margin;
        int minZ = Math.min(sz, gz) - margin, maxZ = Math.max(sz, gz) + margin;
        long startKey = key(sx, sy, sz), goalKey = key(gx, gy, gz);

        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<Long, Double> best = new HashMap<>();
        open.add(new Node(sx, sy, sz, 0, heuristic(sx, sy, sz, gx, gy, gz), null));
        best.put(startKey, 0.0);
        int expanded = 0;

        while (!open.isEmpty()) {
            Node cur = open.poll();
            long curKey = key(cur.x, cur.y, cur.z);
            if (cur.g > best.getOrDefault(curKey, Double.MAX_VALUE) + 1e-9) continue; // stale entry
            if (curKey == goalKey) {
                List<long[]> path = new ArrayList<>();
                for (Node n = cur; n != null; n = n.parent) path.add(0, new long[]{n.x, n.y, n.z});
                return path;
            }
            if (++expanded > maxNodes) return null;

            for (int[] s : STEPS) {
                int nx = cur.x + s[0], ny = cur.y + s[1], nz = cur.z + s[2];
                if (nx < minX || nx > maxX || ny < minY || ny > maxY || nz < minZ || nz > maxZ) continue;
                long nKey = key(nx, ny, nz);
                boolean endpoint = nKey == goalKey || nKey == startKey;
                if (!endpoint) {
                    if (blocked.contains(nKey)) continue;
                    if (!world.isOpen(nx, ny, nz)) continue;
                }
                if (!stepAllowed(cur.x, cur.y, cur.z, s, curKey == startKey || curKey == goalKey, endpoint)) continue;

                double g = cur.g + (s[0] != 0 && s[1] != 0 || s[0] != 0 && s[2] != 0 || s[1] != 0 && s[2] != 0
                        ? 1.41421356 : 1.0);
                if (g >= best.getOrDefault(nKey, Double.MAX_VALUE) - 1e-9) continue;
                best.put(nKey, g);
                open.add(new Node(nx, ny, nz, g, g + heuristic(nx, ny, nz, gx, gy, gz), cur));
            }
        }
        return null;
    }

    /** Can sound step from a cell to its neighbour? Diagonals need both in-between cells open. */
    private boolean stepAllowed(int x, int y, int z, int[] s, boolean fromEndpoint, boolean toEndpoint) {
        int nx = x + s[0], ny = y + s[1], nz = z + s[2];
        boolean simple = (fromEndpoint || world.isEmpty(x, y, z)) && (toEndpoint || world.isEmpty(nx, ny, nz));
        int moved = Math.abs(s[0]) + Math.abs(s[1]) + Math.abs(s[2]);
        if (moved == 2) {
            // The two cells the diagonal brushes past.
            int[][] between = new int[2][];
            int i = 0;
            if (s[0] != 0) between[i++] = new int[]{x + s[0], y, z};
            if (s[1] != 0) between[i++] = new int[]{x, y + s[1], z};
            if (s[2] != 0) between[i] = new int[]{x, y, z + s[2]};
            for (int[] c : between) {
                if (!world.isOpen(c[0], c[1], c[2])) return false;
                if (!world.isEmpty(c[0], c[1], c[2])) simple = false;
            }
        }
        if (simple) return true;
        // Something here has a partial shape (a door, a slab, a trapdoor): ask the exact check.
        // Steps out of the source's or listener's own cell are trusted; tightening re-checks them.
        if (fromEndpoint || toEndpoint) return true;
        return world.segmentClear(x + 0.5, y + 0.5, z + 0.5, nx + 0.5, ny + 0.5, nz + 0.5);
    }

    private static double heuristic(int x, int y, int z, int gx, int gy, int gz) {
        double dx = x - gx, dy = y - gy, dz = z - gz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ---------------------------------------------------------------- string pulling

    private Route tighten(double[] source, double[] listener, List<long[]> cells) {
        List<double[]> pts = new ArrayList<>();
        pts.add(source.clone());
        for (int i = 1; i < cells.size() - 1; i++) {
            long[] c = cells.get(i);
            pts.add(new double[]{c[0] + 0.5, c[1] + 0.5, c[2] + 0.5});
        }
        pts.add(listener.clone());

        pts = shortcut(pts);
        for (int round = 0; round < 4; round++) {
            boolean changed = false;
            for (int pass = 0; pass < 4; pass++) {
                if (!relax(pts)) break;
                changed = true;
            }
            if (cutCorners(pts)) {
                changed = true;
                for (int pass = 0; pass < 4; pass++) {
                    if (!relax(pts)) break;
                }
            }
            pts = shortcut(pts);
            if (!changed) break;
        }
        return new Route(cleanUp(pts).toArray(new double[0][]));
    }

    /**
     * A bend hanging in mid-air means the string is held off by two different edges (for
     * example both top edges of a thick wall). Replace it with two bends by cutting the
     * corner with the longest chord that still clears the blocks; relaxing then seats each
     * new bend on its own edge.
     */
    private boolean cutCorners(List<double[]> pts) {
        boolean any = false;
        for (int i = 1; i < pts.size() - 1; i++) {
            double[] prev = pts.get(i - 1), cur = pts.get(i), next = pts.get(i + 1);
            double reach = Math.min(dist(cur, prev), dist(cur, next));
            if (reach < 0.1) continue;
            double lo = 0, hi = 1;
            for (int step = 0; step < 7; step++) {
                double mid = (lo + hi) / 2;
                double[] a = toward(cur, prev, mid * reach), b = toward(cur, next, mid * reach);
                if (clear(a, b)) lo = mid; else hi = mid;
            }
            if (lo * reach < 0.08) continue; // already sitting on a single edge
            pts.set(i, toward(cur, prev, lo * reach));
            pts.add(i + 1, toward(cur, next, lo * reach));
            i++; // skip the point just inserted
            any = true;
        }
        return any;
    }

    /** Merges bends that ended up on top of each other and drops ones that barely turn. */
    private static List<double[]> cleanUp(List<double[]> pts) {
        List<double[]> out = new ArrayList<>();
        out.add(pts.get(0));
        for (int i = 1; i < pts.size() - 1; i++) {
            double[] prev = out.get(out.size() - 1), cur = pts.get(i), next = pts.get(i + 1);
            if (dist(prev, cur) < 0.15 && out.size() > 1) continue;      // same edge as the previous bend
            double ax = cur[0] - prev[0], ay = cur[1] - prev[1], az = cur[2] - prev[2];
            double bx = next[0] - cur[0], by = next[1] - cur[1], bz = next[2] - cur[2];
            double la = Math.sqrt(ax * ax + ay * ay + az * az), lb = Math.sqrt(bx * bx + by * by + bz * bz);
            if (la > 1e-9 && lb > 1e-9) {
                double cos = (ax * bx + ay * by + az * bz) / (la * lb);
                if (cos > 0.9986) continue;                               // turns less than about 3 degrees
            }
            out.add(cur);
        }
        out.add(pts.get(pts.size() - 1));
        return out;
    }

    private static double[] toward(double[] from, double[] to, double distance) {
        double d = dist(from, to);
        if (d < 1e-9) return from.clone();
        return lerp(from, to, Math.min(1.0, distance / d));
    }

    /** Drops every waypoint that can be skipped with a clear straight line. */
    private List<double[]> shortcut(List<double[]> pts) {
        List<double[]> out = new ArrayList<>();
        int i = 0;
        out.add(pts.get(0));
        while (i < pts.size() - 1) {
            int next = i + 1;
            for (int j = pts.size() - 1; j > i + 1; j--) {
                if (clear(pts.get(i), pts.get(j))) {
                    next = j;
                    break;
                }
            }
            out.add(pts.get(next));
            i = next;
        }
        return out;
    }

    /**
     * Slides each bend toward the straight line between its neighbours as far as the blocks
     * allow. That moves the bend from the middle of an air cell onto the actual edge.
     */
    private boolean relax(List<double[]> pts) {
        boolean movedAny = false;
        for (int i = 1; i < pts.size() - 1; i++) {
            double[] prev = pts.get(i - 1), cur = pts.get(i), next = pts.get(i + 1);
            double[] target = closestPointOnSegment(cur, prev, next);
            if (dist(cur, target) < 0.02) continue;
            double lo = 0, hi = 1; // lo: known clear, hi: known (or assumed) blocked
            for (int step = 0; step < 7; step++) {
                double mid = (lo + hi) / 2;
                double[] q = lerp(cur, target, mid);
                if (clear(prev, q) && clear(q, next)) lo = mid; else hi = mid;
            }
            if (lo > 0.01) {
                pts.set(i, lerp(cur, target, lo));
                movedAny = true;
            }
        }
        return movedAny;
    }

    private boolean clear(double[] a, double[] b) {
        return world.segmentClear(a[0], a[1], a[2], b[0], b[1], b[2]);
    }

    // ---------------------------------------------------------------- small helpers

    private static double[] closestPointOnSegment(double[] p, double[] a, double[] b) {
        double abx = b[0] - a[0], aby = b[1] - a[1], abz = b[2] - a[2];
        double len2 = abx * abx + aby * aby + abz * abz;
        if (len2 < 1e-12) return a.clone();
        double t = ((p[0] - a[0]) * abx + (p[1] - a[1]) * aby + (p[2] - a[2]) * abz) / len2;
        t = Math.max(0, Math.min(1, t));
        return new double[]{a[0] + abx * t, a[1] + aby * t, a[2] + abz * t};
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Angle at {@code apex} between the directions to {@code a} and {@code b}, in radians. */
    static double angleBetween(double[] apex, double[] a, double[] b) {
        double ax = a[0] - apex[0], ay = a[1] - apex[1], az = a[2] - apex[2];
        double bx = b[0] - apex[0], by = b[1] - apex[1], bz = b[2] - apex[2];
        double la = Math.sqrt(ax * ax + ay * ay + az * az), lb = Math.sqrt(bx * bx + by * by + bz * bz);
        if (la < 1e-9 || lb < 1e-9) return 0;
        return Math.acos(Math.max(-1, Math.min(1, (ax * bx + ay * by + az * bz) / (la * lb))));
    }

    static int floor(double v) {
        return (int) Math.floor(v);
    }

    private static long cellOf(double[] p) {
        return key(floor(p[0]), floor(p[1]), floor(p[2]));
    }

    /** Packs a block position into one long (same idea as Minecraft's BlockPos.asLong). */
    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (long) (z & 0x3FFFFFF);
    }
}
