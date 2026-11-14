package wavefront.mc;

import com.redsmods.wavefront.WavefrontMod;
import com.redsmods.wavefront.config.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import wavefront.acoustics.AcousticDatabase;
import wavefront.acoustics.BassField;
import wavefront.acoustics.WaveGrid;
import wavefront.acoustics.WaveShape;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps a wave simulation of bass going for the space round the listener. It copies the blocks
 * nearby into a {@link WaveGrid}, runs it on background threads, and hands the newest result
 * to the solver. The game never waits for it: until a result is ready, and wherever it has no
 * answer, the figures from walls and diffraction are used as before.
 */
public final class BassLayer {
    private BassLayer() {}

    /** A new run starts once the listener has moved this far from where the last one was made, in blocks. */
    private static final double MOVE = 0.75;
    /** A result is used until the listener is this far from where it was made. */
    private static final double TOLERANCE = 2.5;
    /** Standing still, the run is repeated this often so doors, pistons and new blocks are noticed. */
    private static final long REFRESH_NANOS = 1_000_000_000L;
    /** With no sound asking for this long, the runs stop. */
    private static final long IDLE_NANOS = 5_000_000_000L;
    /** Bass passes through blocks that take less than this off it over a full block (leaves). */
    private static final double TRANSPARENT_DB = 6.0;
    /** Water and lava bounce nearly all airborne sound back. */
    private static final float FLUID_SURFACE = WaveGrid.admittanceFor(0.01);

    private static final int MAX_THREADS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();
    private static final ThreadFactory THREADS = job -> {
        Thread t = new Thread(job, "Wavefront bass " + THREAD_NUMBER.incrementAndGet());
        t.setDaemon(true);
        t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
        return t;
    };
    private static final ExecutorService CONTROL = Executors.newSingleThreadExecutor(THREADS);
    private static final ExecutorService HELPERS = Executors.newFixedThreadPool(MAX_THREADS, THREADS);

    /** Splits a step between the calling thread and the helpers. */
    private static final WaveGrid.Runner RUNNER = (parts, body) -> {
        CountDownLatch done = new CountDownLatch(parts - 1);
        for (int i = 1; i < parts; i++) {
            int part = i;
            HELPERS.execute(() -> {
                try {
                    body.accept(part);
                } finally {
                    done.countDown();
                }
            });
        }
        body.accept(0);
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    /** A finished run, with what it was made for. */
    private record Made(BassField field, Level level) {}

    private static volatile Made made;
    private static volatile long wantedAt = 0L;
    private static final AtomicBoolean running = new AtomicBoolean();
    private static final AtomicInteger generation = new AtomicInteger();
    private static volatile boolean warned = false;

    // The last run that was started: used to decide when the next one is due. Game thread only.
    private static Level attemptLevel;
    private static Vec3 attemptAt = Vec3.ZERO;
    private static long attemptNanos;
    private static int attemptDetail, attemptRange;

    /** How long the last run took, in milliseconds, for the debug line. */
    public static volatile float lastMillis = 0f;

    /** Called by the solver whenever a sound could use the bass field. Keeps the runs going. */
    public static void want() {
        wantedAt = System.nanoTime();
    }

    /** The newest result, if it was made in this world close enough to where the listener is now. */
    public static BassField fieldFor(Level level, Vec3 listener) {
        Made m = made;
        if (m == null || m.level != level || m.field == null) return null;
        return m.field.listenerMoved(listener.x, listener.y, listener.z) <= TOLERANCE ? m.field : null;
    }

    /** Forgets everything, for leaving a world. */
    public static void clear() {
        generation.incrementAndGet();
        made = null;
        attemptLevel = null;
        WaveGrid.releaseMemory();
    }

    /** Called once a game tick. Starts a new run when one is due and none is going. */
    public static void tick(Level level, Vec3 listener) {
        Config cfg = Config.getInstance();
        if (!cfg.wavePhysics || !cfg.waveBass) {
            if (made != null || attemptLevel != null) clear();
            return;
        }
        long now = System.nanoTime();
        if (now - wantedAt > IDLE_NANOS) return;
        int detail = Math.max(1, Math.min(3, cfg.waveDetail)), range = Math.max(8, Math.min(48, cfg.waveRange));
        boolean due = attemptLevel != level || attemptDetail != detail || attemptRange != range
                || attemptAt.distanceToSqr(listener) > MOVE * MOVE || now - attemptNanos > REFRESH_NANOS;
        if (!due || !running.compareAndSet(false, true)) return;
        attemptLevel = level;
        attemptAt = listener;
        attemptNanos = now;
        attemptDetail = detail;
        attemptRange = range;
        int started = generation.get();
        CONTROL.execute(() -> {
            try {
                long begin = System.nanoTime();
                BassField field = compute(level, listener, detail, range);
                lastMillis = (System.nanoTime() - begin) / 1e6f;
                if (started == generation.get()) made = new Made(field, level);
            } catch (Throwable t) {
                if (!warned) {
                    warned = true;
                    WavefrontMod.DEBUG_LOGGER.warn("Wavefront: the bass simulation failed; walls and diffraction are used for bass instead", t);
                }
            } finally {
                running.set(false);
            }
        });
    }

    // ------------------------------------------------------------------ one run

    private static BassField compute(Level level, Vec3 listener, int detail, int range) {
        AcousticDatabase db = AcousticSolver.database();
        if (db == null) return null;
        BlockPos head = BlockPos.containing(listener);
        if (!level.getFluidState(head).isEmpty()) return null; // head under water: the water rules apply instead

        int up = Math.max(8, range * 2 / 3);
        int ox = head.getX() - range, oy = head.getY() - up, oz = head.getZ() - range;
        int bx = 2 * range + 1, by = 2 * up + 1, bz = 2 * range + 1;
        // Detail 1: 63 Hz on 1 m cells. Detail 2: plus 125 Hz on half-metre cells. Detail 3: both on half-metre cells.
        WaveGrid coarse = detail >= 3 ? null : new WaveGrid(ox, oy, oz, bx, by, bz, 1);
        WaveGrid fine = detail >= 2 ? new WaveGrid(ox, oy, oz, bx, by, bz, 2) : null;
        copyBlocks(level, db, coarse, fine, ox, oy, oz, bx, by, bz);

        int threads = detail >= 3 ? MAX_THREADS : Math.max(1, Math.min(4, MAX_THREADS / 2));
        if (coarse == null) return fine.solveAll(listener.x, listener.y, listener.z, 2, threads, RUNNER);
        BassField.Layer low = coarse.solve(0, listener.x, listener.y, listener.z, threads, RUNNER);
        if (low == null) return null; // the listener's head is inside something solid
        if (fine == null) return new BassField(listener.x, listener.y, listener.z, low);
        return new BassField(listener.x, listener.y, listener.z, low, fine.solve(1, listener.x, listener.y, listener.z, threads, RUNNER));
    }

    /** How one kind of block sits in the wave grid. Worked out once per block state. */
    private static final class Cell {
        static final Cell AIR = new Cell(false, 0f, null);
        final boolean full;
        final float surface;
        final double[][] boxes;
        private volatile WaveShape one, two;

        Cell(boolean full, float surface, double[][] boxes) {
            this.full = full;
            this.surface = surface;
            this.boxes = boxes;
        }

        WaveShape shape(int n) {
            if (n == 1) {
                WaveShape s = one;
                return s != null ? s : (one = WaveShape.of(boxes, 1));
            }
            WaveShape s = two;
            return s != null ? s : (two = WaveShape.of(boxes, 2));
        }
    }

    private static final Map<BlockState, Cell> CELLS = new ConcurrentHashMap<>();
    private static volatile AcousticDatabase cellsFor;

    private static void copyBlocks(Level level, AcousticDatabase db, WaveGrid coarse, WaveGrid fine,
                                   int ox, int oy, int oz, int bx, int by, int bz) {
        if (cellsFor != db) { // the block database was reloaded
            CELLS.clear();
            cellsFor = db;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = oy; y < oy + by; y++) {
            for (int z = oz; z < oz + bz; z++) {
                for (int x = ox; x < ox + bx; x++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;
                    Cell cell = CELLS.get(state);
                    if (cell == null) {
                        cell = classify(level, db, pos, state);
                        CELLS.put(state, cell);
                    }
                    if (cell == Cell.AIR) continue;
                    if (cell.full) {
                        if (coarse != null) coarse.setFull(x, y, z, cell.surface);
                        if (fine != null) fine.setFull(x, y, z, cell.surface);
                    } else {
                        if (coarse != null) coarse.setShape(x, y, z, cell.shape(1), cell.surface);
                        if (fine != null) fine.setShape(x, y, z, cell.shape(2), cell.surface);
                    }
                }
            }
        }
        if (coarse != null) coarse.build();
        if (fine != null) fine.build();
    }

    private static Cell classify(Level level, AcousticDatabase db, BlockPos pos, BlockState state) {
        if (!state.getFluidState().isEmpty()) return new Cell(true, FLUID_SURFACE, null);
        AcousticSolver.BlockInfo info = AcousticSolver.info(db, state);
        if (info.ignored()) return Cell.AIR;
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) return Cell.AIR; // plants, torches, open fence gates
        if (info.material.realLoss1m[1] < TRANSPARENT_DB) return Cell.AIR; // leaves
        float surface = WaveGrid.admittanceFor((info.material.absorption[0] + info.material.absorption[1]) / 2.0);
        if (Block.isShapeFullBlock(shape)) return new Cell(true, surface, null);
        List<AABB> parts = shape.toAabbs();
        double[][] boxes = new double[parts.size()][];
        for (int i = 0; i < boxes.length; i++) {
            AABB a = parts.get(i);
            boxes[i] = new double[]{a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ};
        }
        return new Cell(false, surface, boxes);
    }
}
