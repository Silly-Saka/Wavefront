package wavefront.acoustics;

/**
 * How a block that is not a plain full cube (a door, a slab, a glass pane, a fence) sits in
 * the wave grid. The grid is made of small cubes of air ("cells") that pass pressure to their
 * six neighbours. A block can fill whole cells, and it can also close the way between two
 * cells without filling either: that is what a thin door or pane does.
 */
public final class WaveShape {
    /** A block is sampled at this many points along each side. Minecraft shapes are multiples of 1/16. */
    private static final int V = 16;
    /** A cell counts as solid when at least this much of it is filled. */
    private static final double SOLID_FILL = 0.6;
    /** The way between two cells counts as closed when at least this much of it is covered. */
    private static final double PLATE_COVER = 0.7;

    /** Cells per block along each side. */
    public final int n;
    /** One entry per cell, index (z * n + y) * n + x. */
    final boolean[] solid;
    /** One entry per cell: bit d is set when a thin part of this block closes the way in direction d. */
    final byte[] plates;
    /** True when the block neither fills a cell nor closes a way, so the grid can skip it. */
    public final boolean open;

    private WaveShape(int n, boolean[] solid, byte[] plates) {
        this.n = n;
        this.solid = solid;
        this.plates = plates;
        boolean any = false;
        for (int i = 0; i < solid.length; i++) any |= solid[i] || plates[i] != 0;
        this.open = !any;
    }

    /**
     * @param boxes the block's solid parts, each {minX, minY, minZ, maxX, maxY, maxZ} with 0..1 across the block
     * @param n     cells per block along each side: 1, 2 or 4
     */
    public static WaveShape of(double[][] boxes, int n) {
        if (n != 1 && n != 2 && n != 4) throw new IllegalArgumentException("cells per block must be 1, 2 or 4");
        boolean[] filled = new boolean[V * V * V];
        for (double[] box : boxes) {
            int x0 = first(box[0]), x1 = last(box[3]);
            int y0 = first(box[1]), y1 = last(box[4]);
            int z0 = first(box[2]), z1 = last(box[5]);
            for (int z = z0; z <= z1; z++)
                for (int y = y0; y <= y1; y++)
                    for (int x = x0; x <= x1; x++) filled[(z * V + y) * V + x] = true;
        }

        int c = V / n; // sample points per cell along each side
        boolean[] solid = new boolean[n * n * n];
        byte[] plates = new byte[n * n * n];
        int[] cell = new int[3];
        for (cell[2] = 0; cell[2] < n; cell[2]++) {
            for (cell[1] = 0; cell[1] < n; cell[1]++) {
                for (cell[0] = 0; cell[0] < n; cell[0]++) {
                    int index = (cell[2] * n + cell[1]) * n + cell[0];
                    int count = 0;
                    for (int z = cell[2] * c; z < (cell[2] + 1) * c; z++)
                        for (int y = cell[1] * c; y < (cell[1] + 1) * c; y++)
                            for (int x = cell[0] * c; x < (cell[0] + 1) * c; x++)
                                if (filled[(z * V + y) * V + x]) count++;
                    solid[index] = count >= SOLID_FILL * c * c * c;

                    int bits = 0;
                    for (int axis = 0; axis < 3; axis++) {
                        int start = cell[axis] * c;
                        // Towards the lower neighbour: from that cell's middle (or this block's face) to this cell's middle.
                        if (covered(filled, cell, axis, Math.max(0, start - c / 2), start + c / 2, c)) bits |= 1 << (axis * 2);
                        // Towards the upper neighbour: from this cell's middle to that cell's middle (or this block's face).
                        if (covered(filled, cell, axis, start + c / 2, Math.min(V, start + c + c / 2), c)) bits |= 1 << (axis * 2 + 1);
                    }
                    plates[index] = (byte) bits;
                }
            }
        }
        return new WaveShape(n, solid, plates);
    }

    /**
     * Looks along one axis through a slice of the block, across the face of one cell, and says
     * whether enough of that face has something solid behind it to stop sound passing.
     */
    private static boolean covered(boolean[] filled, int[] cell, int axis, int from, int to, int c) {
        int u = (axis + 1) % 3, v = (axis + 2) % 3;
        int blocked = 0;
        int[] p = new int[3];
        for (int a = cell[u] * c; a < (cell[u] + 1) * c; a++) {
            for (int b = cell[v] * c; b < (cell[v] + 1) * c; b++) {
                p[u] = a;
                p[v] = b;
                for (int k = from; k < to; k++) {
                    p[axis] = k;
                    if (filled[(p[2] * V + p[1]) * V + p[0]]) {
                        blocked++;
                        break;
                    }
                }
            }
        }
        return blocked >= PLATE_COVER * c * c;
    }

    /** First sample point whose middle lies at or after this edge. */
    private static int first(double min) {
        return Math.max(0, (int) Math.ceil(min * V - 0.5 - 1e-6));
    }

    /** Last sample point whose middle lies at or before this edge. */
    private static int last(double max) {
        return Math.min(V - 1, (int) Math.floor(max * V - 0.5 + 1e-6));
    }
}
