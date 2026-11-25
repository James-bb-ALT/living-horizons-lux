package dev.lhlux.coloredlight;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Automatically extracts true dominant light colors from vanilla and modded block/item textures.
 * For torches/candles/lanterns/campfires, prioritizes the upper flame region (top 55%) so brown wooden
 * handles never wash out green, blue, purple, or crimson modded flames.
 */
public final class SpriteColorExtractor {
    public enum RegionMode {
        FULL,
        TOP_FLAME,
        CENTER_CORE
    }

    private static final Map<Block, Integer> BLOCK_CACHE = new ConcurrentHashMap<>();
    private static final Map<Item, Integer> ITEM_CACHE = new ConcurrentHashMap<>();
    private static final int NO_COLOR_SENTINEL = -1;

    private SpriteColorExtractor() {
    }

    public static void clearCache() {
        BLOCK_CACHE.clear();
        ITEM_CACHE.clear();
    }

    public static int extractBlockRgb4(BlockState state, int emissionLevel) {
        if (state == null || state.isAir() || emissionLevel <= 0) {
            return 0;
        }
        Block block = state.getBlock();
        Integer cached = BLOCK_CACHE.get(block);
        if (cached != null) {
            return cached == NO_COLOR_SENTINEL ? 0 : LightColorRegistry.scaleToLevel(cached, emissionLevel);
        }

        Minecraft mc = safeMinecraft();
        if (mc == null || mc.getBlockRenderer() == null) {
            return 0;
        }

        try {
            BakedModel model = mc.getBlockRenderer().getBlockModel(state);
            TextureAtlasSprite sprite = pickBestSprite(model, state);
            if (sprite == null) {
                return 0;
            }
            ResourceLocation id = ForgeRegistries.BLOCKS != null ? ForgeRegistries.BLOCKS.getKey(block) : null;
            RegionMode mode = detectRegionMode(id != null ? id.getPath() : "");
            int extracted = extractFromSprite(sprite, mode, 15);
            BLOCK_CACHE.put(block, extracted != 0 ? extracted : NO_COLOR_SENTINEL);
            return extracted != 0 ? LightColorRegistry.scaleToLevel(extracted, emissionLevel) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static int extractItemRgb4(ItemStack stack, int emissionLevel) {
        if (stack == null || stack.isEmpty() || emissionLevel <= 0) {
            return 0;
        }
        Item item = stack.getItem();
        if (item instanceof BlockItem bi) {
            int fromBlock = extractBlockRgb4(bi.getBlock().defaultBlockState(), emissionLevel);
            if (fromBlock != 0) {
                return fromBlock;
            }
        }

        Integer cached = ITEM_CACHE.get(item);
        if (cached != null) {
            return cached == NO_COLOR_SENTINEL ? 0 : LightColorRegistry.scaleToLevel(cached, emissionLevel);
        }

        Minecraft mc = safeMinecraft();
        if (mc == null || mc.getItemRenderer() == null) {
            return 0;
        }

        try {
            BakedModel model = mc.getItemRenderer().getModel(stack, null, null, 0);
            TextureAtlasSprite sprite = pickBestSprite(model, null);
            if (sprite == null) {
                return 0;
            }
            ResourceLocation id = ForgeRegistries.ITEMS != null ? ForgeRegistries.ITEMS.getKey(item) : null;
            RegionMode mode = detectRegionMode(id != null ? id.getPath() : "");
            int extracted = extractFromSprite(sprite, mode, 15);
            ITEM_CACHE.put(item, extracted != 0 ? extracted : NO_COLOR_SENTINEL);
            return extracted != 0 ? LightColorRegistry.scaleToLevel(extracted, emissionLevel) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static TextureAtlasSprite pickBestSprite(BakedModel model, BlockState state) {
        if (model == null) {
            return null;
        }
        RandomSource rand = RandomSource.create(42L);
        TextureAtlasSprite best = null;
        int bestScore = -1;

        for (Direction dir : new Direction[]{null, Direction.UP, Direction.NORTH, Direction.SOUTH}) {
            List<BakedQuad> quads = model.getQuads(state, dir, rand);
            if (quads == null) {
                continue;
            }
            for (BakedQuad quad : quads) {
                if (quad == null || quad.getSprite() == null) {
                    continue;
                }
                TextureAtlasSprite s = quad.getSprite();
                String name = s.contents().name().getPath();
                if ("missingno".equals(name)) {
                    continue;
                }
                int score = isEmissiveSpriteName(name) ? 10 : 1;
                if (score > bestScore) {
                    bestScore = score;
                    best = s;
                }
            }
        }

        if (best != null) {
            return best;
        }
        TextureAtlasSprite particle = model.getParticleIcon();
        if (particle != null && !"missingno".equals(particle.contents().name().getPath())) {
            return particle;
        }
        return null;
    }

    private static int extractFromSprite(TextureAtlasSprite sprite, RegionMode mode, int emissionLevel) {
        SpriteContents contents = sprite.contents();
        int w = contents.width();
        int h = contents.height();
        if (w <= 0 || h <= 0 || w > 128 || h > 128) {
            return 0;
        }
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels[y * w + x] = sprite.getPixelRGBA(0, x, y);
            }
        }
        return extractFromAbgrPixels(pixels, w, h, mode, emissionLevel);
    }

    /**
     * Pure testable dominant emissive color extractor over ABGR pixels (Minecraft NativeImage format).
     */
    public static int extractFromAbgrPixels(
            int[] abgrPixels,
            int width,
            int height,
            boolean topFlameRegionOnly,
            int emissionLevel
    ) {
        return extractFromAbgrPixels(
                abgrPixels, width, height,
                topFlameRegionOnly ? RegionMode.TOP_FLAME : RegionMode.FULL,
                emissionLevel
        );
    }

    public static int extractFromAbgrPixels(
            int[] abgrPixels,
            int width,
            int height,
            RegionMode mode,
            int emissionLevel
    ) {
        if (abgrPixels == null || width <= 0 || height <= 0 || abgrPixels.length < width * height || emissionLevel <= 0) {
            return 0;
        }
        int xStart = 0;
        int xEnd = width;
        int yStart = 0;
        int yEnd = height;
        boolean coreBoost = false;

        if (mode == RegionMode.TOP_FLAME) {
            yEnd = Math.max(1, (int) Math.ceil(height * 0.55f));
        } else if (mode == RegionMode.CENTER_CORE) {
            xStart = Math.max(0, width / 4);
            xEnd = Math.min(width, Math.max(xStart + 1, (width * 3) / 4));
            yStart = Math.max(0, height / 4);
            yEnd = Math.min(height, Math.max(yStart + 1, (int) Math.ceil(height * 0.80f)));
            coreBoost = true;
        }

        int result = accumulateRegion(abgrPixels, width, xStart, xEnd, yStart, yEnd, coreBoost, emissionLevel);
        if (result == 0 && mode != RegionMode.FULL) {
            result = accumulateRegion(abgrPixels, width, 0, width, 0, height, false, emissionLevel);
        }
        return result;
    }

    private static int accumulateRegion(
            int[] abgrPixels,
            int width,
            int xStart,
            int xEnd,
            int yStart,
            int yEnd,
            boolean coreBoost,
            int emissionLevel
    ) {
        double sumR = 0.0;
        double sumG = 0.0;
        double sumB = 0.0;
        double sumW = 0.0;

        for (int y = yStart; y < yEnd; y++) {
            int rowOffset = y * width;
            for (int x = xStart; x < xEnd; x++) {
                int abgr = abgrPixels[rowOffset + x];
                int a = (abgr >>> 24) & 0xFF;
                if (a < 128) {
                    continue;
                }
                float r = (abgr & 0xFF) / 255.0f;
                float g = ((abgr >>> 8) & 0xFF) / 255.0f;
                float b = ((abgr >>> 16) & 0xFF) / 255.0f;

                float maxC = Math.max(r, Math.max(g, b));
                float minC = Math.min(r, Math.min(g, b));
                if (maxC < 0.08f) {
                    continue;
                }
                float sat = (maxC - minC) / Math.max(maxC, 1.0e-4f);
                // Strongly favor bright, chromatic emissive pixels over neutral/metallic frames
                float weight = (maxC * maxC) * (0.20f + sat * sat * 2.5f);
                if (coreBoost && maxC >= 0.55f && sat >= 0.25f) {
                    weight *= 3.5f;
                }

                sumR += r * weight;
                sumG += g * weight;
                sumB += b * weight;
                sumW += weight;
            }
        }

        if (sumW <= 1.0e-6) {
            return 0;
        }

        float r = (float) (sumR / sumW);
        float g = (float) (sumG / sumW);
        float b = (float) (sumB / sumW);

        // Gently enhance chroma so colored lights remain distinct against sand/stone
        float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        r = Math.max(0.0f, luma + (r - luma) * 1.15f);
        g = Math.max(0.0f, luma + (g - luma) * 1.15f);
        b = Math.max(0.0f, luma + (b - luma) * 1.15f);

        float peak = Math.max(r, Math.max(g, b));
        if (peak <= 1.0e-4f) {
            return 0;
        }
        float scale = (float) emissionLevel / peak;
        return Rgb4Color.pack(Math.round(r * scale), Math.round(g * scale), Math.round(b * scale));
    }

    static RegionMode detectRegionMode(String path) {
        if (isLanternLikePath(path)) {
            return RegionMode.CENTER_CORE;
        }
        if (isTorchLikePath(path)) {
            return RegionMode.TOP_FLAME;
        }
        return RegionMode.FULL;
    }

    static boolean isLanternLikePath(String path) {
        return path.contains("lantern")
                || path.contains("lamp")
                || path.contains("bulb")
                || path.contains("brazier")
                || path.contains("sconce")
                || path.contains("candelabra")
                || path.contains("beacon");
    }

    static boolean isTorchLikePath(String path) {
        return path.contains("torch")
                || path.contains("candle")
                || path.contains("campfire")
                || path.contains("flame");
    }

    private static boolean isEmissiveSpriteName(String path) {
        return path.contains("emissive")
                || path.contains("_e")
                || path.contains("glow")
                || path.contains("flame")
                || path.contains("fire")
                || path.contains("light")
                || path.contains("core")
                || path.contains("bulb");
    }

    private static Minecraft safeMinecraft() {
        try {
            return Minecraft.getInstance();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
