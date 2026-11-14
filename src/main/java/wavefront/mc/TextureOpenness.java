package wavefront.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looks at a block's texture to see how much of it is see-through. Used for blocks the
 * database has never heard of (from other mods): a grate or a mesh is mostly holes, and its
 * picture shows it. Textures are only read on the game thread, so the sound threads ask, get
 * "not known yet" the first time, and find the answer there a tick later.
 */
public final class TextureOpenness {
    private TextureOpenness() {}

    private static final Map<Block, Double> KNOWN = new ConcurrentHashMap<>();
    private static final Set<Block> WAITING = ConcurrentHashMap.newKeySet();

    /** The see-through share of this block's texture (0..1), or null if it has not been looked at yet. */
    static Double of(Block block) {
        Double known = KNOWN.get(block);
        if (known == null) WAITING.add(block);
        return known;
    }

    /** Forgets everything, for when textures may have changed. */
    public static void clear() {
        KNOWN.clear();
        WAITING.clear();
    }

    /** Called once a game tick on the game thread. */
    public static void tick() {
        if (WAITING.isEmpty()) return;
        for (Block block : WAITING) {
            KNOWN.put(block, measure(block));
            WAITING.remove(block);
        }
    }

    private static double measure(Block block) {
        try {
            TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager().getBlockStateModelSet()
                    .getParticleMaterial(block.defaultBlockState()).sprite();
            if (!sprite.transparency().hasTransparent()) return 0;
            SpriteContents contents = sprite.contents();
            int width = contents.width(), height = contents.height();
            if (width <= 0 || height <= 0) return 0;
            int clear = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (contents.isTransparent(0, x, y)) clear++;
                }
            }
            return (double) clear / (width * height);
        } catch (Throwable t) {
            return 0; // no usable texture: treat the block as solid
        }
    }
}
