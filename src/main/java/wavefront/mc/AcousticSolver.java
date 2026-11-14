package wavefront.mc;

import com.redsmods.wavefront.WavefrontMod;
import com.redsmods.wavefront.config.Config;
import dev.isxander.yacl3.platform.YACLPlatform;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import wavefront.acoustics.AcousticDatabase;
import wavefront.acoustics.AcousticMaterial;
import wavefront.acoustics.Aperture;
import wavefront.acoustics.Bands;
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

        Solved(Vec3 position, float gain, float gainLF, float gainHF, float pathLength) {
            this.position = position;
            this.gain = gain;
            this.gainLF = gainLF;
            this.gainHF = gainHF;
            this.pathLength = pathLength;
        }
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
        float straight = (float) listener.distanceTo(source);
        AcousticDatabase db = database();
        if (db == null || straight < 0.05f) return new Solved(source, 1f, 1f, 1f, straight);

        Config cfg = Config.getInstance();
        db.setWallStrength(cfg.wallStrength);
        BlockPos sourceCell = BlockPos.containing(source);

        Occlusion direct = traceDirect(level, db, listener, source, sourceCell);
        if (!direct.blocked()) return new Solved(source, 1f, 1f, 1f, straight);

        double[] lis = {listener.x, listener.y, listener.z};
        double[] src = {source.x, source.y, source.z};
        List<Propagation.Arrival> arrivals = Propagation.list();
        double[] throughWalls = direct.energy();
        arrivals.add(Propagation.straight(src, straight, throughWalls));

        // Only look for ways round when the walls take away a meaningful amount (more than about 6 dB).
        double midThrough = (throughWalls[3] + throughWalls[4] + throughWalls[5]) / 3.0;
        if (cfg.diffraction && midThrough < 0.25 && straight <= cfg.bendRange()) {
            WorldView view = new WorldView(level, player, db, sourceCell);
            int margin = Math.max(6, Math.min(16, (int) (straight / 2) + 4));
            List<PathFinder.Route> routes = new PathFinder(view, cfg.searchBudget())
                    .findRoutes(src, lis, cfg.routes(), margin);
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
                arrivals.add(arrival);
            }
        }

        Propagation.Result r = Propagation.combine(lis, src, arrivals);
        Vec3 apparent = new Vec3(r.apparentPosition[0], r.apparentPosition[1], r.apparentPosition[2]);
        return new Solved(apparent, (float) r.gain, (float) r.gainLF, (float) r.gainHF, (float) listener.distanceTo(apparent));
    }

    /** Adds up what every block on the straight line between listener and source does to the sound. */
    private static Occlusion traceDirect(Level level, AcousticDatabase db, Vec3 listener, Vec3 source, BlockPos sourceCell) {
        Occlusion occlusion = new Occlusion();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int maxCells = (int) (listener.distanceTo(source) * 3) + 8;

        VoxelLine.walk(listener.x, listener.y, listener.z, source.x, source.y, source.z, maxCells, (x, y, z) -> {
            // Block sounds (pistons, doors, note blocks) start at the centre of their own block.
            if (x == sourceCell.getX() && y == sourceCell.getY() && z == sourceCell.getZ()) return true;
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) return true;
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
