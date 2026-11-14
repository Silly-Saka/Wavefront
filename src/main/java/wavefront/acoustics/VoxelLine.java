package wavefront.acoustics;

/**
 * Walks every block cell a straight line passes through, in order
 * (the Amanatides-Woo grid traversal). Used to find what lies between a sound and the listener.
 */
public final class VoxelLine {
    private VoxelLine() {}

    public interface Visitor {
        /** Called for each cell on the line. Return false to stop early. */
        boolean visit(int x, int y, int z);
    }

    public static void walk(double ax, double ay, double az, double bx, double by, double bz,
                            int maxCells, Visitor visitor) {
        int x = (int) Math.floor(ax), y = (int) Math.floor(ay), z = (int) Math.floor(az);
        int endX = (int) Math.floor(bx), endY = (int) Math.floor(by), endZ = (int) Math.floor(bz);
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;

        // Fraction of the segment at which the line next crosses a cell boundary on each axis,
        // and how much that fraction grows per cell.
        double tMaxX = boundary(ax, dx, x, stepX), tMaxY = boundary(ay, dy, y, stepY), tMaxZ = boundary(az, dz, z, stepZ);
        double tDeltaX = stepX == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tDeltaY = stepY == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);

        for (int i = 0; i < maxCells; i++) {
            if (!visitor.visit(x, y, z)) return;
            if (x == endX && y == endY && z == endZ) return;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                if (tMaxX > 1.0) return;
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                if (tMaxY > 1.0) return;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                if (tMaxZ > 1.0) return;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
    }

    private static double boundary(double origin, double delta, int cell, int step) {
        if (step == 0) return Double.MAX_VALUE;
        double edge = step > 0 ? cell + 1 : cell;
        return (edge - origin) / delta;
    }
}
