package dev.lhlux.coloredlight;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.lhlux.LivingHorizonsLuxMod;
import dev.lhlux.config.LuxAddonConfig;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Maps Minecraft/modded blocks and handheld items to RGB4 emitted colors and stained-glass RGB4 transmission filters.
 */
public final class LightColorRegistry {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int FULL_PASS_FILTER = Rgb4Color.pack(15, 15, 15);

    private static final Map<Block, Integer> EMITTERS = new ConcurrentHashMap<>();
    private static final Map<Block, Integer> FILTERS = new ConcurrentHashMap<>();
    private static final Map<Item, Integer> ITEM_EMITTERS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    private LightColorRegistry() {
    }

    public static void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (LightColorRegistry.class) {
            if (initialized) {
                return;
            }
            registerDefaults();
            initialized = true;
        }
    }

    public static void reloadUserOverrides(Path configPath) {
        ensureInitialized();
        if (configPath == null || !Files.exists(configPath)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(configPath)) {
            Type mapType = new TypeToken<Map<String, int[]>>() {}.getType();
            Map<String, int[]> overrides = GSON.fromJson(reader, mapType);
            if (overrides != null) {
                for (Map.Entry<String, int[]> entry : overrides.entrySet()) {
                    int[] rgb = entry.getValue();
                    if (rgb == null || rgb.length < 3) {
                        continue;
                    }
                    ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
                    if (id != null && ForgeRegistries.BLOCKS.containsKey(id)) {
                        Block b = ForgeRegistries.BLOCKS.getValue(id);
                        if (b != null && b != Blocks.AIR) {
                            EMITTERS.put(b, Rgb4Color.pack(rgb[0], rgb[1], rgb[2]));
                        }
                    }
                }
            }
        } catch (Exception e) {
            LivingHorizonsLuxMod.LOGGER.warn("Failed to parse custom light color overrides at {}", configPath, e);
        }
    }

    public static void registerDefaults() {
        EMITTERS.clear();
        FILTERS.clear();

        // Warm fire & torches
        putEmit(Blocks.TORCH, 15, 11, 5);
        putEmit(Blocks.WALL_TORCH, 15, 11, 5);
        putEmit(Blocks.LANTERN, 15, 11, 6);
        putEmit(Blocks.CAMPFIRE, 15, 10, 4);
        putEmit(Blocks.FIRE, 15, 9, 3);
        putEmit(Blocks.JACK_O_LANTERN, 15, 10, 4);
        putEmit(Blocks.FURNACE, 15, 9, 4);
        putEmit(Blocks.BLAST_FURNACE, 15, 10, 4);
        putEmit(Blocks.SMOKER, 15, 9, 4);
        putEmit(Blocks.LAVA, 15, 6, 1);
        putEmit(Blocks.LAVA_CAULDRON, 15, 6, 1);
        putEmit(Blocks.MAGMA_BLOCK, 14, 5, 1);
        putEmit(Blocks.SHROOMLIGHT, 15, 10, 5);
        putEmit(Blocks.GLOWSTONE, 15, 13, 7);
        putEmit(Blocks.OCHRE_FROGLIGHT, 15, 13, 7);

        // Soul / Cyan / Ocean / Magic
        putEmit(Blocks.SOUL_TORCH, 4, 13, 15);
        putEmit(Blocks.SOUL_WALL_TORCH, 4, 13, 15);
        putEmit(Blocks.SOUL_LANTERN, 4, 13, 15);
        putEmit(Blocks.SOUL_CAMPFIRE, 4, 13, 15);
        putEmit(Blocks.SOUL_FIRE, 4, 13, 15);
        putEmit(Blocks.SEA_LANTERN, 7, 14, 15);
        putEmit(Blocks.CONDUIT, 6, 14, 15);
        putEmit(Blocks.BEACON, 10, 15, 15);
        putEmit(Blocks.END_ROD, 14, 14, 15);
        putEmit(Blocks.VERDANT_FROGLIGHT, 8, 15, 10);
        putEmit(Blocks.GLOW_LICHEN, 8, 13, 10);
        putEmit(Blocks.SEA_PICKLE, 9, 14, 10);
        putEmit(Blocks.SCULK_CATALYST, 3, 10, 13);

        // Redstone / Nether / Crimson / Purple
        putEmit(Blocks.REDSTONE_TORCH, 15, 3, 2);
        putEmit(Blocks.REDSTONE_WALL_TORCH, 15, 3, 2);
        putEmit(Blocks.REDSTONE_ORE, 14, 3, 2);
        putEmit(Blocks.DEEPSLATE_REDSTONE_ORE, 14, 3, 2);
        putEmit(Blocks.REDSTONE_LAMP, 15, 11, 6);
        putEmit(Blocks.AMETHYST_CLUSTER, 12, 6, 15);
        putEmit(Blocks.LARGE_AMETHYST_BUD, 11, 5, 14);
        putEmit(Blocks.MEDIUM_AMETHYST_BUD, 10, 4, 13);
        putEmit(Blocks.SMALL_AMETHYST_BUD, 9, 4, 12);
        putEmit(Blocks.CRYING_OBSIDIAN, 12, 4, 15);
        putEmit(Blocks.RESPAWN_ANCHOR, 14, 5, 15);
        putEmit(Blocks.PEARLESCENT_FROGLIGHT, 15, 10, 15);
        putEmit(Blocks.NETHER_PORTAL, 12, 5, 15);
        putEmit(Blocks.END_PORTAL, 6, 12, 14);
        putEmit(Blocks.ENCHANTING_TABLE, 9, 12, 15);
        putEmit(Blocks.ENDER_CHEST, 6, 13, 12);
        putEmit(Blocks.BREWING_STAND, 14, 11, 6);

        // Stained glass & panes RGB transmission filters
        putFilterPair(Blocks.RED_STAINED_GLASS, Blocks.RED_STAINED_GLASS_PANE, 15, 4, 4);
        putFilterPair(Blocks.ORANGE_STAINED_GLASS, Blocks.ORANGE_STAINED_GLASS_PANE, 15, 9, 3);
        putFilterPair(Blocks.YELLOW_STAINED_GLASS, Blocks.YELLOW_STAINED_GLASS_PANE, 15, 15, 4);
        putFilterPair(Blocks.LIME_STAINED_GLASS, Blocks.LIME_STAINED_GLASS_PANE, 8, 15, 4);
        putFilterPair(Blocks.GREEN_STAINED_GLASS, Blocks.GREEN_STAINED_GLASS_PANE, 4, 14, 5);
        putFilterPair(Blocks.CYAN_STAINED_GLASS, Blocks.CYAN_STAINED_GLASS_PANE, 4, 14, 15);
        putFilterPair(Blocks.LIGHT_BLUE_STAINED_GLASS, Blocks.LIGHT_BLUE_STAINED_GLASS_PANE, 6, 12, 15);
        putFilterPair(Blocks.BLUE_STAINED_GLASS, Blocks.BLUE_STAINED_GLASS_PANE, 4, 6, 15);
        putFilterPair(Blocks.PURPLE_STAINED_GLASS, Blocks.PURPLE_STAINED_GLASS_PANE, 12, 5, 15);
        putFilterPair(Blocks.MAGENTA_STAINED_GLASS, Blocks.MAGENTA_STAINED_GLASS_PANE, 15, 6, 14);
        putFilterPair(Blocks.PINK_STAINED_GLASS, Blocks.PINK_STAINED_GLASS_PANE, 15, 9, 12);
        putFilterPair(Blocks.WHITE_STAINED_GLASS, Blocks.WHITE_STAINED_GLASS_PANE, 14, 14, 14);
        putFilterPair(Blocks.LIGHT_GRAY_STAINED_GLASS, Blocks.LIGHT_GRAY_STAINED_GLASS_PANE, 11, 11, 11);
        putFilterPair(Blocks.GRAY_STAINED_GLASS, Blocks.GRAY_STAINED_GLASS_PANE, 8, 8, 8);
        putFilterPair(Blocks.BLACK_STAINED_GLASS, Blocks.BLACK_STAINED_GLASS_PANE, 4, 4, 4);
        putFilterPair(Blocks.BROWN_STAINED_GLASS, Blocks.BROWN_STAINED_GLASS_PANE, 11, 8, 5);
        putFilter(Blocks.WATER, 9, 13, 15);
        putFilter(Blocks.ICE, 11, 14, 15);
        putFilter(Blocks.BLUE_ICE, 9, 13, 15);

        // Handheld / entity emissive non-block items: packed as (emissionLevel << 12) | rgb4
        ITEM_EMITTERS.clear();
        putItemEmit(Items.GLOWSTONE_DUST, 10, 15, 12, 6);
        putItemEmit(Items.BLAZE_ROD, 12, 15, 10, 3);
        putItemEmit(Items.BLAZE_POWDER, 10, 15, 9, 2);
        putItemEmit(Items.MAGMA_CREAM, 9, 15, 7, 2);
        putItemEmit(Items.NETHER_STAR, 14, 15, 14, 15);
        putItemEmit(Items.PRISMARINE_CRYSTALS, 9, 8, 15, 14);
        putItemEmit(Items.GLOW_INK_SAC, 9, 5, 14, 13);
        putItemEmit(Items.GLOW_BERRIES, 10, 15, 11, 4);
        putItemEmit(Items.SPECTRAL_ARROW, 10, 15, 14, 7);
        putItemEmit(Items.LAVA_BUCKET, 13, 15, 6, 1);
        putItemEmit(Items.ENCHANTED_GOLDEN_APPLE, 9, 14, 10, 15);
        putItemEmit(Items.END_CRYSTAL, 12, 14, 7, 15);
        putItemEmit(Items.TORCHFLOWER, 9, 15, 11, 4);
    }

    private static void putItemEmit(Item item, int level, int r, int g, int b) {
        ITEM_EMITTERS.put(item, ((level & 0xF) << 12) | Rgb4Color.pack(r, g, b));
    }

    private static void putEmit(Block block, int r, int g, int b) {
        EMITTERS.put(block, Rgb4Color.pack(r, g, b));
    }

    private static void putFilter(Block block, int r, int g, int b) {
        FILTERS.put(block, Rgb4Color.pack(r, g, b));
    }

    private static void putFilterPair(Block glass, Block pane, int r, int g, int b) {
        int packed = Rgb4Color.pack(r, g, b);
        FILTERS.put(glass, packed);
        FILTERS.put(pane, packed);
    }

    public static int getEmissionRgb4(BlockState state, BlockGetter level, BlockPos pos) {
        ensureInitialized();
        if (state == null || state.isAir()) {
            return 0;
        }
        int emissionLevel;
        try {
            emissionLevel = state.getLightEmission(level, pos);
        } catch (Throwable ignored) {
            emissionLevel = state.getLightEmission();
        }
        if (emissionLevel <= 0) {
            return 0;
        }
        Integer configured = EMITTERS.get(state.getBlock());
        if (configured != null) {
            return scaleToLevel(configured, emissionLevel);
        }
        if (LuxAddonConfig.get().autoModdedEmitterColors) {
            int fromSprite = SpriteColorExtractor.extractBlockRgb4(state, emissionLevel);
            if (fromSprite != 0) {
                return fromSprite;
            }
            int inferred = inferFromMapColor(state, level, pos, emissionLevel);
            if (inferred != 0) {
                return inferred;
            }
        }
        return Rgb4Color.pack(emissionLevel, Math.max(0, emissionLevel - 2), Math.max(0, emissionLevel - 5));
    }

    /**
     * Resolves emissive handheld or dropped item level and RGB4 tint.
     *
     * @return packed {@code (emissionLevel << 12) | rgb4}, or 0 if not emissive
     */
    public static int getItemEmissionLevelAndRgb4(ItemStack stack) {
        ensureInitialized();
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        Item item = stack.getItem();
        if (item instanceof BlockItem bi) {
            BlockState bs = bi.getBlock().defaultBlockState();
            int level = bs.getLightEmission();
            if (level > 0) {
                int rgb4 = getEmissionRgb4(bs, EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                return ((level & 0xF) << 12) | (rgb4 & 0xFFF);
            }
        }

        Integer mapped = ITEM_EMITTERS.get(item);
        if (mapped != null) {
            return mapped;
        }

        if (LuxAddonConfig.get().autoModdedEmitterColors && ForgeRegistries.ITEMS != null) {
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
            if (key != null) {
                String path = key.getPath();
                int inferredLevel = inferItemLevelFromPath(path);
                if (inferredLevel > 0) {
                    int rgb4 = SpriteColorExtractor.extractItemRgb4(stack, inferredLevel);
                    if (rgb4 == 0) {
                        rgb4 = Rgb4Color.pack(inferredLevel, Math.max(0, inferredLevel - 3), Math.max(0, inferredLevel - 7));
                    }
                    return ((inferredLevel & 0xF) << 12) | (rgb4 & 0xFFF);
                }
            }
        }
        return 0;
    }

    private static int inferItemLevelFromPath(String path) {
        if (path.contains("torch") || path.contains("lantern") || path.contains("lamp")
                || path.contains("flare") || path.contains("flame") || path.contains("brazier")) {
            return 13;
        }
        if (path.contains("glow") || path.contains("crystal") || path.contains("ember") || path.contains("soul")) {
            return 10;
        }
        return 0;
    }

    public static int getFilterRgb4(BlockState state) {
        ensureInitialized();
        if (state == null || state.isAir() || !LuxAddonConfig.get().stainedGlassFiltering) {
            return FULL_PASS_FILTER;
        }
        Integer filter = FILTERS.get(state.getBlock());
        return filter != null ? filter : FULL_PASS_FILTER;
    }

    private static int inferFromMapColor(BlockState state, BlockGetter level, BlockPos pos, int levelVal) {
        try {
            MapColor mc = state.getMapColor(level, pos);
            if (mc == null || mc.col == 0) {
                return 0;
            }
            int col = mc.col;
            int r8 = (col >>> 16) & 0xFF;
            int g8 = (col >>> 8) & 0xFF;
            int b8 = col & 0xFF;
            int peak = Math.max(r8, Math.max(g8, b8));
            if (peak <= 16) {
                return 0;
            }
            // Gently boost saturation from map color while hitting requested emission level
            int r4 = Math.round((r8 / (float) peak) * levelVal);
            int g4 = Math.round((g8 / (float) peak) * levelVal);
            int b4 = Math.round((b8 / (float) peak) * levelVal);
            return Rgb4Color.pack(r4, g4, b4);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static int scaleToLevel(int baseRgb4, int level) {
        int peak = Rgb4Color.maxComponent(baseRgb4);
        if (peak <= 0 || peak == level) {
            return baseRgb4;
        }
        float scale = (float) level / (float) peak;
        return Rgb4Color.pack(
                Math.round(Rgb4Color.red(baseRgb4) * scale),
                Math.round(Rgb4Color.green(baseRgb4) * scale),
                Math.round(Rgb4Color.blue(baseRgb4) * scale)
        );
    }
}
