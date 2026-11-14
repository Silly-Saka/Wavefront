package wavefront.mc;

import com.redsmods.wavefront.WavefrontMod;
import com.redsmods.wavefront.config.Config;
import dev.isxander.yacl3.platform.YACLPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import wavefront.acoustics.AcousticDatabase;
import wavefront.acoustics.AcousticMaterial;
import wavefront.acoustics.AirAbsorption;
import wavefront.acoustics.Aperture;
import wavefront.acoustics.Bands;
import wavefront.acoustics.Echo;
import wavefront.acoustics.MediaPath;
import wavefront.acoustics.Medium;
import wavefront.acoustics.Occlusion;
import wavefront.acoustics.PathFinder;
import wavefront.acoustics.Propagation;
import wavefront.acoustics.VoxelLine;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Works out how a sound reaches the listener in the Minecraft world: what the blocks in the
 * way do to it, and which openings and edges it can bend round. This class is the bridge
 * between Minecraft and the game-independent maths in {@code wavefront.acoustics}.
 */
public final class AcousticSolver {
    private AcousticSolver() {}

    /** What to do with a sound: where it should appear to be, and its filter settings. */
    public static final class Solved {
        public final Vec3 position;
        /** Overall volume factor from occlusion and diffraction, 0..1. */
        public final float gain;
        /** Extra bass factor, 0..1. */
        public final float gainLF;
        /** Extra treble factor, 0..1. */
        public final float gainHF;
        /** Length of the path the sound effectively travelled, in blocks. */
        public final float pathLength;
        /** How long the sound took to arrive, in seconds. Shorter than the path suggests if part of it was under water. */
        public final float seconds;

        Solved(Vec3 position, float gain, float gainLF, float gainHF, float pathLength, float seconds) {
            this.position = position;
            this.gain = gain;
            this.gainLF = gainLF;
            this.gainHF = gainHF;
            this.pathLength = pathLength;
            this.seconds = seconds;
        }

        /**
         * True if time ran out before the search for ways round obstacles finished, so the sound
         * may come out quieter than it should. A sound that is already playing ignores such a
         * result and keeps what it had.
         */
        public boolean partial = false;
    }

    private static final String DATABASE_RESOURCE = "/assets/wavefront/acoustics/acoustic_materials.json";
    private static final String OVERRIDE_FILE = "wavefront/acoustic_materials.json";

    private static volatile AcousticDatabase database;
    private static volatile boolean databaseFailed = false;
    private static final Map<Block, BlockInfo> blockInfo = new ConcurrentHashMap<>();

    /** What the database says about one kind of block. */
    private static final class BlockInfo {
        final AcousticMaterial material;
        /** -1: use the collision shape. 0: ignore the block. Between: fraction of the cell that is solid. */
        final double fill;

        BlockInfo(AcousticMaterial material, double fill) {
            this.material = material;
            this.fill = fill;
        }

        boolean ignored() {
            return fill == 0;
        }

        boolean partlyOpen() {
            return fill > 0 && fill < 1;
        }
    }

    // ------------------------------------------------------------------ database

    /** Loads the block database, preferring a user file in the config folder over the built-in one. */
    public static AcousticDatabase database() {
        AcousticDatabase db = database;
        if (db != null || databaseFailed) return db;
        synchronized (AcousticSolver.class) {
            if (database != null || databaseFailed) return database;
            try {
                Path override = YACLPlatform.getConfigDir().resolve(OVERRIDE_FILE);
                if (Files.isRegularFile(override)) {
                    try (Reader r = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                        database = AcousticDatabase.load(r);
                        WavefrontMod.DEBUG_LOGGER.info("Loaded acoustic database from {}", override);
                    }
                } else {
                    try (InputStream in = AcousticSolver.class.getResourceAsStream(DATABASE_RESOURCE)) {
                        if (in == null) throw new IllegalStateException("Missing " + DATABASE_RESOURCE);
                        database = AcousticDatabase.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                    }
                }
                blockInfo.clear();
            } catch (Exception e) {
                databaseFailed = true;
                WavefrontMod.DEBUG_LOGGER.error("Could not load the acoustic database; physics mode is off", e);
            }
            return database;
        }
    }

    /** Forgets the loaded database so the next sound reloads it (after the user edits the file). */
    public static void reloadDatabase() {
        synchronized (AcousticSolver.class) {
            database = null;
            databaseFailed = false;
            blockInfo.clear();
        }
    }

    private static BlockInfo info(AcousticDatabase db, BlockState state) {
        Block block = state.getBlock();
        BlockInfo cached = blockInfo.get(block);
        if (cached != null) return cached;

        String id = BuiltInRegistries.BLOCK.getKey(block).toString();
        AcousticMaterial material = db.materialForBlock(id);
        double fill = db.fillForBlock(id);
        if (db.isNonPhysical(id)) fill = 0;

        if (material == null) {
            // A block the database has never heard of (usually from another mod): try its tags,
            // then guess from the sound it makes when you walk on it.
            for (Map.Entry<String, String> e : db.tagFallbacks().entrySet()) {
                Identifier tagId = Identifier.tryParse(e.getKey());
                if (tagId != null && state.is(TagKey.create(Registries.BLOCK, tagId))) {
                    material = db.material(e.getValue());
                    break;
                }
            }
            if (material == null) material = db.material(materialFromSoundType(state.getSoundType()));
            if (material == null) material = db.fallbackMaterial();
        }
        BlockInfo result = new BlockInfo(material, fill);
        blockInfo.put(block, result);
        return result;
    }

    private static String materialFromSoundType(SoundType type) {
        if (type == SoundType.WOOD) return "wood_planks";
        if (type == SoundType.WOOL) return "wool";
        if (type == SoundType.GLASS) return "glass";
        if (type == SoundType.METAL) return "iron";
        if (type == SoundType.SAND) return "sand";
        if (type == SoundType.GRAVEL) return "gravel";
        if (type == SoundType.SNOW) return "snow";
        if (type == SoundType.GRASS) return "packed_earth";
        return AcousticDatabase.FALLBACK_MATERIAL;
    }

    // ------------------------------------------------------------------ solving

    /**
     * Works out how the sound at {@code source} reaches {@code listener}.
     * Safe to call from the mod's worker threads (it only reads the world, as the ray caster does).
     */
    public static Solved solve(Level level, Player player, Vec3 listener, Vec3 source) {
        return solve(level, player, listener, source, 0L);
    }

    /** A recent result, kept briefly so that sounds from the same spot need not all be worked out separately. */
    private record Recent(Solved result, Vec3 source, Vec3 listener, long at) {}

    private static final Map<Long, Recent> recent = new ConcurrentHashMap<>();
    private static final long RECENT_NANOS = 1_000_000_000L;
    private static final double RECENT_LISTENER_MOVE_SQ = 0.5 * 0.5;

    /**
     * As {@link #solve}, but reuses a result from the last second if it was for the same source
     * spot and the listener has moved less than half a block since. A note block firing over
     * and over, with several of its notes still ringing, is then worked out once instead of
     * once per note. Two threads asking about the same spot share one calculation.
     *
     * @param deadline a moment (System.nanoTime) after which searching stops, or 0 for no limit
     */
    public static Solved solveShared(Level level, Player player, Vec3 listener, Vec3 source, long deadline) {
        long key = BlockPos.asLong((int) Math.floor(source.x * 4), (int) Math.floor(source.y * 4), (int) Math.floor(source.z * 4));
        if (recent.size() > 512) {
            long cutoff = System.nanoTime() - 5 * RECENT_NANOS;
            recent.values().removeIf(r -> r.at() < cutoff);
        }
        return recent.compute(key, (k, old) -> {
            long now = System.nanoTime();
            if (old != null && !old.result().partial && now - old.at() < RECENT_NANOS
                    && old.source().distanceToSqr(source) < 0.1 && old.listener().distanceToSqr(listener) < RECENT_LISTENER_MOVE_SQ) {
                return old;
            }
            return new Recent(solve(level, player, listener, source, deadline), source, listener, now);
        }).result();
    }

    /**
     * @param deadline a moment (System.nanoTime) after which the search for ways round obstacles
     *                 stops, or 0 for no limit. A result cut short this way is marked partial.
     */
    public static Solved solve(Level level, Player player, Vec3 listener, Vec3 source, long deadline) {
        float straight = (float) listener.distanceTo(source);
        AcousticDatabase db = database();
        if (db == null || straight < 0.05f) return new Solved(source, 1f, 1f, 1f, straight, straight / 343f);

        Config cfg = Config.getInstance();
        db.setWallStrength(cfg.wallStrength);
        BlockPos sourceCell = BlockPos.containing(source);

        double[] lis = {listener.x, listener.y, listener.z};
        double[] src = {source.x, source.y, source.z};
        List<Propagation.Arrival> arrivals = Propagation.list();

        boolean[] touchedFluid = {false};
        Occlusion direct = traceDirect(level, db, listener, source, sourceCell, touchedFluid);
        double[] throughWalls = direct.energy();
        // Water and lava only need working out when some of it is actually in the way.
        Medium listenerIn = cfg.water ? mediumAt(level, db, new BlockPos.MutableBlockPos(), listener.x, listener.y, listener.z) : Medium.AIR;
        boolean wet = cfg.water && (touchedFluid[0] || isFluid(listenerIn)
                || isFluid(mediumAt(level, db, new BlockPos.MutableBlockPos(), source.x, source.y, source.z)));
        Propagation.Arrival straightArrival = Propagation.straight(src, straight, throughWalls);
        if (wet) applyMedia(level, db, cfg, straightArrival, src, lis);
        arrivals.add(straightArrival);
        if (!direct.blocked()) return finish(listener, lis, src, arrivals, cfg, listenerIn); // in plain view

        // Only look for ways round when the walls take away a meaningful amount (more than about 6 dB).
        double midThrough = (throughWalls[3] + throughWalls[4] + throughWalls[5]) / 3.0;
        boolean partial = false;
        if (cfg.diffraction && midThrough < 0.25 && straight <= cfg.bendRange()) {
            // No time left this round: leave the search out and say so.
            partial = deadline != 0L && System.nanoTime() > deadline;
        }
        if (cfg.diffraction && midThrough < 0.25 && straight <= cfg.bendRange() && !partial) {
            WorldView view = new WorldView(level, player, db, sourceCell);
            int margin = Math.max(6, Math.min(16, (int) (straight / 2) + 4));
            PathFinder finder = new PathFinder(view, cfg.searchBudget()).deadline(deadline);
            List<PathFinder.Route> routes = finder.findRoutes(src, lis, cfg.routes(), margin);
            partial = finder.timedOut();
            for (PathFinder.Route route : routes) {
                if (route.bends == 0) continue; // a clear straight line is already covered above
                // A doorway or small hole can only pass the sound that lands on it.
                Aperture.Opening opening = cfg.openingSize ? Aperture.tightest(view, route) : null;
                Propagation.Arrival arrival = Propagation.diffracted(route, straight, opening);
                if (cfg.diffractionStrength != 1f) {
                    for (int b = 0; b < Bands.COUNT; b++) {
                        arrival.energy[b] = Math.pow(arrival.energy[b], cfg.diffractionStrength);
                    }
                }
                if (wet) applyMedia(level, db, cfg, arrival, route.points);
                arrivals.add(arrival);
            }
        }

        Solved result = finish(listener, lis, src, arrivals, cfg, listenerIn);
        result.partial = partial;
        return result;
    }

    /** Lets the air take its share from every arrival, then merges them into one result. */
    private static Solved finish(Vec3 listener, double[] lis, double[] src, List<Propagation.Arrival> arrivals, Config cfg, Medium listenerIn) {
        for (Propagation.Arrival arrival : arrivals) {
            AirAbsorption.apply(arrival.energy, arrival.airLength, cfg.airAbsorptionStrength);
        }
        Propagation.Result r = Propagation.combine(lis, src, arrivals);
        Vec3 apparent = new Vec3(r.apparentPosition[0], r.apparentPosition[1], r.apparentPosition[2]);
        double gainHF = isFluid(listenerIn) ? r.gainHF * submergedTreble(cfg) : r.gainHF;
        return new Solved(apparent, (float) r.gain, (float) r.gainLF, (float) gainHF, (float) listener.distanceTo(apparent), (float) r.seconds);
    }

    // ------------------------------------------------------------------ water and lava

    /** How finely a path is sampled for what it passes through, in blocks. */
    private static final double MEDIA_STEP = 0.25;
    private static final int MEDIA_MAX_SAMPLES = 512;
    /**
     * Treble lost with the head under water, in dB, at a Submerged Hearing setting of 1. People
     * hear under water through the bones of the skull, and measured sensitivity is best around
     * 500 to 1000 Hz instead of 3 to 4 kHz. The size of the cut is an estimate from that shift.
     */
    private static final double SUBMERGED_TREBLE_DB = 12.0;

    private static double submergedTreble(Config cfg) {
        return Math.pow(10.0, -SUBMERGED_TREBLE_DB * Math.max(0f, cfg.submergedHearing) / 20.0);
    }

    /**
     * What fills this exact point: air, water or lava, or null inside a solid block (see
     * {@link MediaPath#step}). The surface of a fluid block counts, not just the block.
     */
    private static Medium mediumAt(Level level, AcousticDatabase db, BlockPos.MutableBlockPos pos, double x, double y, double z) {
        pos.set((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        FluidState fluid = level.getFluidState(pos);
        if (fluid.isEmpty()) {
            BlockState state = level.getBlockState(pos);
            return state.isAir() || state.getCollisionShape(level, pos).isEmpty() ? Medium.AIR : null;
        }
        if (y > pos.getY() + fluid.getHeight(level, pos)) return Medium.AIR; // above the surface, in the same block
        return fluid.is(FluidTags.LAVA) ? db.lava() : db.water();
    }

    /** True for water or lava; false for air and for the inside of a solid block. */
    private static boolean isFluid(Medium medium) {
        return medium != null && medium != Medium.AIR;
    }

    /** Follows a path point to point, noting what it passes through every quarter block. */
    private static MediaPath media(Level level, AcousticDatabase db, double[]... points) {
        MediaPath path = new MediaPath();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 1; i < points.length; i++) {
            double[] a = points[i - 1], b = points[i];
            double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 1e-6) continue;
            int samples = Math.max(1, Math.min(MEDIA_MAX_SAMPLES, (int) Math.ceil(length / MEDIA_STEP)));
            for (int k = 0; k < samples; k++) {
                double t = (k + 0.5) / samples;
                path.step(mediumAt(level, db, pos, a[0] + dx * t, a[1] + dy * t, a[2] + dz * t), length / samples);
            }
        }
        return path;
    }

    /** Gives one arrival its travel time through water and lava, and takes off what each surface crossing costs. */
    private static void applyMedia(Level level, AcousticDatabase db, Config cfg, Propagation.Arrival arrival, double[]... points) {
        MediaPath path = media(level, db, points);
        if (path.length() <= 0) return;
        arrival.seconds = path.seconds();
        arrival.airLength = path.airLength();
        double loss = path.crossingLossDb() * Math.max(0f, cfg.waterSurfaceStrength);
        if (loss <= 0) return;
        double kept = Bands.dbToEnergy(loss);
        for (int b = 0; b < Bands.COUNT; b++) arrival.energy[b] *= kept;
    }

    /** The speed of sound where the listener is, for the Doppler shift: 343 in air, about 1447 under water. */
    public static double speedOfSoundAt(Level level, Vec3 point) {
        AcousticDatabase db = database();
        if (db == null || !Config.getInstance().water) return Medium.AIR.speed;
        Medium medium = mediumAt(level, db, new BlockPos.MutableBlockPos(), point.x, point.y, point.z);
        return medium == null ? Medium.AIR.speed : medium.speed;
    }

    /** Adds up what every block on the straight line between listener and source does to the sound. */
    private static Occlusion traceDirect(Level level, AcousticDatabase db, Vec3 listener, Vec3 source, BlockPos sourceCell, boolean[] touchedFluid) {
        Occlusion occlusion = new Occlusion();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int maxCells = (int) (listener.distanceTo(source) * 3) + 8;

        VoxelLine.walk(listener.x, listener.y, listener.z, source.x, source.y, source.z, maxCells, (x, y, z) -> {
            // Block sounds (pistons, doors, note blocks) start at the centre of their own block.
            if (x == sourceCell.getX() && y == sourceCell.getY() && z == sourceCell.getZ()) return true;
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) return true;
            if (!touchedFluid[0] && !state.getFluidState().isEmpty()) touchedFluid[0] = true;
            BlockInfo info = info(db, state);
            if (info.ignored()) return true;
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) return true; // open fence gates, plants, fluids
            if (info.partlyOpen()) {
                occlusion.throughPartlyOpen(info.material, info.fill);
                return true;
            }
            List<AABB> parts = shape.toAabbs();
            double[][] boxes = new double[parts.size()][];
            for (int i = 0; i < boxes.length; i++) {
                AABB a = parts.get(i);
                boxes[i] = new double[]{x + a.minX, y + a.minY, z + a.minZ, x + a.maxX, y + a.maxY, z + a.maxZ};
            }
            double thickness = Occlusion.lengthInsideBoxes(listener.x, listener.y, listener.z,
                    source.x, source.y, source.z, boxes);
            occlusion.throughSolid(info.material, thickness);
            return true;
        });
        return occlusion;
    }

    // ------------------------------------------------------------------ distinct echoes

    /** One echo to play: where it seems to come from, its filter settings and how far it travelled. */
    public static final class EchoResult {
        /** Where the echo's sound is placed: a short way from the listener, towards the bounce point. */
        public final Vec3 position;
        /** The point on the surface the sound bounces off. */
        public final Vec3 bounce;
        public final float gain, gainLF, gainHF, pathLength;
        /** How long the echo takes to arrive, in seconds. */
        public final float seconds;
        /** Which of the six block directions the surface lies in (0..5). */
        public final int direction;

        EchoResult(int direction, Vec3 bounce, Vec3 position, float gain, float gainLF, float gainHF, float pathLength, float seconds) {
            this.seconds = seconds;
            this.direction = direction;
            this.bounce = bounce;
            this.position = position;
            this.gain = gain;
            this.gainLF = gainLF;
            this.gainHF = gainHF;
            this.pathLength = pathLength;
        }
    }

    private static final Direction[] ECHO_DIRECTIONS = Direction.values();
    /**
     * How far from the listener an echo's sound is placed; its loudness is set by its filter
     * instead. Far enough that a step by the listener between two updates barely changes the
     * direction it is heard from, near enough to stay well inside every sound's hearing range.
     */
    private static final double ECHO_PLACEMENT = 4.0;
    /** With rough echoes on, how many blocks either side of the plane a surface may lie. */
    private static final int ROUGH_DEPTH = 2;

    /**
     * Looks for large surfaces that bounce this sound back to the listener late enough to be
     * heard as a separate echo. Returns the strongest few, loudest first. Surfaces must be flat
     * unless rough echoes are switched on.
     *
     * @param loudness how loud the sound itself is at the listener before any walls (0..1)
     */
    public static List<EchoResult> echoes(Level level, Player player, Vec3 listener, Vec3 source, float loudness) {
        List<EchoResult> found = new java.util.ArrayList<>();
        Config cfg = Config.getInstance();
        AcousticDatabase db = database();
        if (db == null || !cfg.echoes || loudness <= 0.01f) return found;

        double[] lis = {listener.x, listener.y, listener.z};
        double[] src = {source.x, source.y, source.z};
        double straight = listener.distanceTo(source);
        Vec3 middle = new Vec3((listener.x + source.x) / 2, (listener.y + source.y) / 2, (listener.z + source.z) / 2);
        WorldView view = new WorldView(level, player, db, BlockPos.containing(source));
        int range = cfg.echoRange;
        // Under water the echo is timed and weakened by what its path passes through.
        Medium listenerIn = cfg.water ? mediumAt(level, db, new BlockPos.MutableBlockPos(), listener.x, listener.y, listener.z) : Medium.AIR;
        boolean wet = cfg.water && (isFluid(listenerIn)
                || isFluid(mediumAt(level, db, new BlockPos.MutableBlockPos(), source.x, source.y, source.z)));
        double straightSeconds = wet ? media(level, db, src, lis).seconds() : straight / Medium.AIR.speed;

        for (Direction direction : ECHO_DIRECTIONS) {
            int axis = direction.getAxis().ordinal();
            int sign = direction.getAxisDirection().getStep();
            // Find the nearest surface straight out from between the source and the listener.
            Vec3 end = new Vec3(middle.x + direction.getStepX() * range, middle.y + direction.getStepY() * range,
                    middle.z + direction.getStepZ() * range);
            BlockHitResult hit = level.clip(new ClipContext(middle, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK) continue;
            Vec3 at = hit.getLocation();
            double plane = axis == 0 ? at.x : axis == 1 ? at.y : at.z;

            Echo.Reflection reflection = Echo.reflect(src, lis, axis, plane);
            if (reflection == null || !reflection.distinct() || reflection.pathLength > range * 2.0) continue;

            // Look at the 5 by 5 patch of surface round the bounce point. Normally every column must
            // have its face exactly on the plane; with rough echoes on, a face may lie up to
            // ROUGH_DEPTH blocks either side of it.
            double[] p = reflection.point;
            int[] cell = {(int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2])};
            int behind = (int) Math.floor(plane + sign * 0.5);
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            int reach = cfg.echoRough ? ROUGH_DEPTH : 0;
            int[] depths = new int[25];
            for (int du = -2; du <= 2; du++) {
                for (int dv = -2; dv <= 2; dv++) {
                    depths[(du + 2) * 5 + dv + 2] = surfaceDepth(view, cell, axis, u, v, du, dv, behind, sign, reach);
                }
            }
            Echo.Patch patch = Echo.patch(depths);
            double facePlane = plane; // where the bounce point sits: in front of whatever sticks out furthest
            if (cfg.echoRough) {
                if (patch.coverage < Echo.MIN_ROUGH_COVERAGE) continue;
                facePlane = plane + sign * patch.nearest;
                if (patch.meanDepth != 0) {
                    // Bounce off the average depth of the patch, not off the one block the search happened to hit.
                    reflection = Echo.reflect(src, lis, axis, plane + sign * patch.meanDepth);
                    if (reflection == null || !reflection.distinct() || reflection.pathLength > range * 2.0) continue;
                    p = reflection.point;
                }
            } else if (patch.count < 15) {
                continue; // a broad flat face needs at least 15 of the 25 blocks
            }

            // Both legs of the bounce must be clear. Step a little off the surface so it does not block itself.
            double[] off = p.clone();
            off[axis] = facePlane - sign * 0.05;
            if (!view.segmentClear(src[0], src[1], src[2], off[0], off[1], off[2])) continue;
            if (!view.segmentClear(off[0], off[1], off[2], lis[0], lis[1], lis[2])) continue;

            // The material of the surface: the block at the middle of the patch, or the first one found.
            int column = depths[12] != Echo.NO_SURFACE ? 12 : -1;
            for (int i = 0; i < depths.length && column < 0; i++) {
                if (depths[i] != Echo.NO_SURFACE) column = i;
            }
            int[] surface = cell.clone();
            surface[u] += column / 5 - 2;
            surface[v] += column % 5 - 2;
            surface[axis] = behind + sign * depths[column];
            AcousticMaterial material = info(db, level.getBlockState(new BlockPos(surface[0], surface[1], surface[2]))).material;
            double seconds = reflection.pathLength / Medium.AIR.speed, airShare = 1.0, crossingLoss = 0.0;
            if (wet) {
                MediaPath path = media(level, db, src, off, lis);
                seconds = path.seconds();
                // Sound is so fast in water that a bounce has to go much further to arrive late enough to be an echo.
                if (seconds - straightSeconds < 0.05) continue;
                airShare = path.airLength() / Math.max(1e-6, path.length());
                crossingLoss = path.crossingLossDb() * Math.max(0f, cfg.waterSurfaceStrength);
            }
            double[] energy = Echo.bounceEnergy(reflection, Echo.notReflected(material.absorption, material.realLoss1m), cfg.airAbsorptionStrength * airShare);
            for (int b = 0; b < Bands.COUNT; b++) {
                if (cfg.echoRough) energy[b] *= Echo.surfaceReturn(b, patch, reflection.cosIncidence);
                if (crossingLoss > 0) energy[b] *= Bands.dbToEnergy(crossingLoss);
            }
            Propagation.Result r = Propagation.combine(lis, src, List.of(new Propagation.Arrival(energy, reflection.pathLength, off)));
            double echoGainHF = isFluid(listenerIn) ? r.gainHF * submergedTreble(cfg) : r.gainHF;

            double amplitude = r.gain * Echo.spreading(straight, reflection.pathLength) * loudness * cfg.echoStrength;
            if (amplitude < 0.01) continue; // more than 40 dB down: not worth a sound channel
            Vec3 bounce = new Vec3(off[0], off[1], off[2]);
            found.add(new EchoResult(direction.ordinal(), bounce, echoPlacementFor(listener, bounce),
                    (float) Math.min(1.0, amplitude), (float) r.gainLF, (float) echoGainHF, (float) reflection.pathLength, (float) seconds));
        }
        found.sort((a, b) -> Float.compare(b.gain, a.gain));
        while (found.size() > cfg.echoCount) found.remove(found.size() - 1);
        return found;
    }

    /**
     * How deep one column of a surface patch lies: 0 if its face is on the plane, positive if it
     * is set back, negative if it sticks out, {@link Echo#NO_SURFACE} if there is no face within
     * {@code reach} blocks. A face is a solid block with an open block in front of it.
     */
    private static int surfaceDepth(WorldView view, int[] cell, int axis, int u, int v, int du, int dv, int behind, int sign, int reach) {
        int[] c = cell.clone();
        c[u] += du;
        c[v] += dv;
        for (int k = -reach; k <= reach; k++) {
            c[axis] = behind + sign * k;
            if (view.isOpen(c[0], c[1], c[2])) continue;
            c[axis] = behind + sign * (k - 1);
            if (view.isOpen(c[0], c[1], c[2])) return k;
        }
        return Echo.NO_SURFACE;
    }

    /** The distance from the listener at which echo sounds are placed. */
    public static double echoPlacement() {
        return ECHO_PLACEMENT;
    }

    /** Where to put an echo's sound for a listener here: a short way off, straight towards the bounce point. */
    public static Vec3 echoPlacementFor(Vec3 listener, Vec3 bounce) {
        double dx = bounce.x - listener.x, dy = bounce.y - listener.y, dz = bounce.z - listener.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return listener;
        double scale = ECHO_PLACEMENT / len;
        return new Vec3(listener.x + dx * scale, listener.y + dy * scale, listener.z + dz * scale);
    }

    // ------------------------------------------------------------------ world view for path finding

    /** Lets the path finder look at the Minecraft world. One instance per solve; it caches what it reads. */
    private static final class WorldView implements PathFinder.World {
        private static final byte OPEN_EMPTY = 0, OPEN_PARTIAL = 1, CLOSED = 2;

        private final Level level;
        private final Player player;
        private final AcousticDatabase db;
        private final BlockPos sourceCell;
        private final Map<Long, Byte> cells = new HashMap<>();
        private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

        WorldView(Level level, Player player, AcousticDatabase db, BlockPos sourceCell) {
            this.level = level;
            this.player = player;
            this.db = db;
            this.sourceCell = sourceCell;
        }

        private byte classify(int x, int y, int z) {
            long key = BlockPos.asLong(x, y, z);
            Byte known = cells.get(key);
            if (known != null) return known;
            scratch.set(x, y, z);
            BlockState state = level.getBlockState(scratch);
            byte result;
            if (state.isAir()) {
                result = OPEN_EMPTY;
            } else if (passesSound(state)) {
                result = OPEN_EMPTY;
            } else {
                VoxelShape shape = state.getCollisionShape(level, scratch);
                if (shape.isEmpty()) result = OPEN_EMPTY;
                else if (Block.isShapeFullBlock(shape)) result = CLOSED;
                else result = OPEN_PARTIAL; // doors, slabs, stairs, trapdoors: needs an exact check
            }
            cells.put(key, result);
            return result;
        }

        /** Blocks sound walks straight through when looking for a route: ignored and mostly-open ones. */
        private boolean passesSound(BlockState state) {
            BlockInfo info = info(db, state);
            return info.ignored() || (info.partlyOpen() && info.fill <= 0.5);
        }

        @Override
        public boolean isOpen(int x, int y, int z) {
            return classify(x, y, z) != CLOSED;
        }

        @Override
        public boolean isEmpty(int x, int y, int z) {
            return classify(x, y, z) == OPEN_EMPTY;
        }

        @Override
        public boolean segmentClear(double ax, double ay, double az, double bx, double by, double bz) {
            Vec3 from = new Vec3(ax, ay, az), to = new Vec3(bx, by, bz);
            ClipContext context = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player) {
                @Override
                public VoxelShape getBlockShape(BlockState blockState, BlockGetter world, BlockPos pos) {
                    // The sound's own block and anything sound passes through must not stop the line.
                    if (pos.equals(sourceCell) || passesSound(blockState)) return Shapes.empty();
                    return super.getBlockShape(blockState, world, pos);
                }
            };
            BlockHitResult hit = level.clip(context);
            return hit.getType() != HitResult.Type.BLOCK;
        }
    }
}
