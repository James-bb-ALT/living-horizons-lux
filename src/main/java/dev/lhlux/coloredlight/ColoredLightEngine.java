package dev.lhlux.coloredlight;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.lhlux.compat.ModCompat;
import dev.lhlux.config.LuxAddonConfig;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Coordinates background RGB colored block light propagation on a dedicated daemon worker thread
 * and lock-free/zero-allocation sampling during Embeddium chunk meshing.
 */
public final class ColoredLightEngine {
    private static final ColoredLightEngine INSTANCE = new ColoredLightEngine();
    private static final int MAX_PENDING_BLOCK_UPDATES = 16_384;
    private static final ThreadLocal<float[]> SCRATCH_RGB = ThreadLocal.withInitial(() -> new float[3]);

    private final ColoredLightStorage storage = new ColoredLightStorage();
    private final ColoredLightPropagator propagator = new ColoredLightPropagator();
    private final LongArrayFIFOQueue pendingBlockUpdates = new LongArrayFIFOQueue(256);
    private final Object pendingBlockLock = new Object();
    private final ConcurrentLinkedQueue<LevelChunk> pendingChunkScans = new ConcurrentLinkedQueue<>();
    private final LongOpenHashSet dirtySectionsForRender = new LongOpenHashSet();
    private final ConcurrentHashMap<Long, Integer> activeEmitters = new ConcurrentHashMap<>();
    private final Object dirtyLock = new Object();
    private final ReentrantLock propagatorLock = new ReentrantLock();
    private volatile boolean pendingResetClear;
    private final Semaphore workSignal = new Semaphore(0);
    private final AtomicInteger levelGeneration = new AtomicInteger();

    private volatile ClientLevel currentLevel;
    private volatile boolean active;
    private volatile Thread workerThread;

    private boolean lastColoredLightEnabled;
    private float lastSaturation;
    private boolean lastStainedGlass;

    private ColoredLightEngine() {
        LuxAddonConfig cfg = LuxAddonConfig.get();
        this.lastColoredLightEnabled = cfg.coloredLightEnabled;
        this.lastSaturation = cfg.coloredLightSaturation;
        this.lastStainedGlass = cfg.stainedGlassFiltering;
    }

    public static ColoredLightEngine get() {
        return INSTANCE;
    }

    public boolean isActive() {
        return active && LuxAddonConfig.get().coloredLightEnabled
                && ModCompat.isLivingHorizonsActive()
                && !ModCompat.isExternalShaderPackActive();
    }

    public void onConfigChanged() {
        LuxAddonConfig cfg = LuxAddonConfig.get();
        boolean enabledChanged = cfg.coloredLightEnabled != lastColoredLightEnabled;
        boolean satChanged = Math.abs(cfg.coloredLightSaturation - lastSaturation) > 1e-4f;
        boolean filterChanged = cfg.stainedGlassFiltering != lastStainedGlass;

        if (!enabledChanged && !satChanged && !filterChanged) {
            return;
        }

        lastColoredLightEnabled = cfg.coloredLightEnabled;
        lastSaturation = cfg.coloredLightSaturation;
        lastStainedGlass = cfg.stainedGlassFiltering;

        ClientLevel prevLevel = currentLevel;
        if (!cfg.coloredLightEnabled) {
            reset();
            if (prevLevel != null) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.levelRenderer != null) {
                    mc.levelRenderer.allChanged();
                }
            }
            return;
        }

        if (prevLevel != null) {
            active = true;
            Minecraft mc = Minecraft.getInstance();
            if (mc.levelRenderer != null) {
                mc.levelRenderer.allChanged();
            }
            signalWorker();
        }
    }

    public void onChunkLoaded(ClientLevel level, LevelChunk chunk) {
        if (!LuxAddonConfig.get().coloredLightEnabled || level == null || chunk == null) {
            return;
        }
        ensureLevel(level);
        pendingChunkScans.add(chunk);
        signalWorker();
    }

    public void onChunkUnloaded(int chunkX, int chunkZ, ClientLevel level) {
        if (level == null) {
            return;
        }
        storage.removeChunk(chunkX, chunkZ, level.getMinSection(), level.getMaxSection() - 1);
        activeEmitters.keySet().removeIf(k -> (BlockPos.getX(k) >> 4) == chunkX && (BlockPos.getZ(k) >> 4) == chunkZ);
    }

    public void onBlockChanged(ClientLevel level, BlockPos pos) {
        if (!LuxAddonConfig.get().coloredLightEnabled || level == null || pos == null) {
            return;
        }
        ensureLevel(level);
        active = true;
        long packed = pos.asLong();

        // Update lock-free emitter map immediately so GPU dynamic colored lights respond on the same frame
        BlockState state = level.getBlockState(pos);
        int immediateEmit = LightColorRegistry.getEmissionRgb4(state, EmptyBlockGetter.INSTANCE, pos);
        if (immediateEmit != 0) {
            activeEmitters.put(packed, immediateEmit);
        } else {
            activeEmitters.remove(packed);
        }

        if (RenderSystem.isOnRenderThread() && propagatorLock.tryLock()) {
            try {
                if (pendingResetClear) {
                    propagator.clear();
                    pendingResetClear = false;
                }
                LevelBlockQuery query = new LevelBlockQuery(level);
                handleBlockUpdateLocked(query, packed);
                if (propagator.hasPendingWork()) {
                    propagator.propagate(storage, query, 512, (sx, sy, sz) -> {
                        synchronized (dirtyLock) {
                            dirtySectionsForRender.add(SectionPos.asLong(sx, sy, sz));
                        }
                    });
                }
            } finally {
                propagatorLock.unlock();
            }
            flushDirtySections(Minecraft.getInstance(), 24);
            if (hasAnyPendingWork()) {
                ensureWorkerStarted();
                signalWorker();
            }
            return;
        }

        synchronized (pendingBlockLock) {
            if (pendingBlockUpdates.size() < MAX_PENDING_BLOCK_UPDATES) {
                pendingBlockUpdates.enqueue(packed);
            }
        }
        ensureWorkerStarted();
        signalWorker();
    }

    public void tickClient(ClientLevel level) {
        LuxAddonConfig cfg = LuxAddonConfig.get();
        if (!cfg.coloredLightEnabled || ModCompat.isExternalShaderPackActive()) {
            active = false;
            return;
        }
        ensureLevel(level);
        ensureWorkerStarted();
        active = true;

        if (hasAnyPendingWork()) {
            signalWorker();
        }

        // Flush up to 24 sections per tick so placed lights propagate smoothly without delay
        flushDirtySections(Minecraft.getInstance(), 24);
    }

    private void ensureWorkerStarted() {
        if (workerThread != null) {
            return;
        }
        synchronized (this) {
            if (workerThread != null) {
                return;
            }
            Thread t = new Thread(this::runWorkerLoop, "LHLux-ColoredLight-Worker");
            t.setDaemon(true);
            t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            t.start();
            workerThread = t;
        }
    }

    private void signalWorker() {
        if (workerThread != null && workSignal.availablePermits() == 0) {
            workSignal.release();
        }
    }

    private boolean hasAnyPendingWork() {
        if (!pendingChunkScans.isEmpty()) {
            return true;
        }
        synchronized (pendingBlockLock) {
            if (!pendingBlockUpdates.isEmpty()) {
                return true;
            }
        }
        if (propagatorLock.tryLock()) {
            try {
                return propagator.hasPendingWork();
            } finally {
                propagatorLock.unlock();
            }
        }
        return true;
    }

    private void runWorkerLoop() {
        while (true) {
            try {
                workSignal.acquire();
            } catch (InterruptedException ignored) {
                continue;
            }
            while (true) {
                int gen = levelGeneration.get();
                ClientLevel level = currentLevel;
                LuxAddonConfig cfg = LuxAddonConfig.get();
                if (level == null || !active || !cfg.coloredLightEnabled || ModCompat.isExternalShaderPackActive()) {
                    break;
                }
                boolean didWork = processWorkerBatch(level, gen, cfg);
                if (!didWork || levelGeneration.get() != gen || currentLevel != level || !hasAnyPendingWork()) {
                    break;
                }
                LockSupport.parkNanos(250_000L);
            }
        }
    }

    private boolean processWorkerBatch(ClientLevel level, int gen, LuxAddonConfig cfg) {
        boolean didWork = false;
        try {
            int chunkBudget = 4;
            while (chunkBudget-- > 0) {
                if (levelGeneration.get() != gen || currentLevel != level) {
                    return false;
                }
                LevelChunk chunk = pendingChunkScans.poll();
                if (chunk == null) {
                    break;
                }
                didWork = true;
                scanChunkEmitters(chunk, level.getMinSection());
            }

            LevelBlockQuery query = new LevelBlockQuery(level);
            int blockBudget = 64;
            while (blockBudget-- > 0) {
                if (levelGeneration.get() != gen || currentLevel != level) {
                    return false;
                }
                long packedPos;
                synchronized (pendingBlockLock) {
                    if (pendingBlockUpdates.isEmpty()) {
                        break;
                    }
                    packedPos = pendingBlockUpdates.dequeueLong();
                }
                didWork = true;
                handleBlockUpdate(query, packedPos);
            }

            if (levelGeneration.get() != gen || currentLevel != level) {
                return false;
            }
            int steps = 0;
            propagatorLock.lock();
            try {
                if (pendingResetClear) {
                    propagator.clear();
                    pendingResetClear = false;
                } else if (propagator.hasPendingWork() && levelGeneration.get() == gen && currentLevel == level) {
                    int stepBudget = Math.min(512, cfg.coloredLightUpdatesPerTick * 8);
                    steps = propagator.propagate(storage, query, stepBudget, (sx, sy, sz) -> {
                        synchronized (dirtyLock) {
                            dirtySectionsForRender.add(SectionPos.asLong(sx, sy, sz));
                        }
                    });
                }
            } finally {
                propagatorLock.unlock();
            }
            if (steps > 0) {
                didWork = true;
            }
        } catch (Throwable ignored) {
            // Protect worker from transient chunk unloads or modded block state edge cases
        }
        return didWork;
    }

    private void scanChunkEmitters(LevelChunk chunk, int minSec) {
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        for (int sIdx = 0; sIdx < sections.length; sIdx++) {
            LevelChunkSection sec = sections[sIdx];
            if (sec == null || sec.hasOnlyAir()) {
                continue;
            }
            int baseY = (minSec + sIdx) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = sec.getBlockState(x, y, z);
                        if (state.getLightEmission() <= 0) {
                            continue;
                        }
                        int wx = baseX + x;
                        int wy = baseY + y;
                        int wz = baseZ + z;
                        mpos.set(wx, wy, wz);
                        int emit = LightColorRegistry.getEmissionRgb4(state, EmptyBlockGetter.INSTANCE, mpos);
                        if (emit != 0) {
                            activeEmitters.put(BlockPos.asLong(wx, wy, wz), emit);
                            int prev = storage.getLight(wx, wy, wz);
                            int merged = Rgb4Color.componentMax(prev, emit);
                            if (merged != prev) {
                                storage.setLight(wx, wy, wz, merged);
                                propagatorLock.lock();
                                try {
                                    propagator.enqueueIncrease(wx, wy, wz, merged);
                                } finally {
                                    propagatorLock.unlock();
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void handleBlockUpdate(LevelBlockQuery query, long packedPos) {
        propagatorLock.lock();
        try {
            handleBlockUpdateLocked(query, packedPos);
        } finally {
            propagatorLock.unlock();
        }
    }

    private void handleBlockUpdateLocked(LevelBlockQuery query, long packedPos) {
        int x = BlockPos.getX(packedPos);
        int y = BlockPos.getY(packedPos);
        int z = BlockPos.getZ(packedPos);
        int prev = storage.getLight(x, y, z);
        BlockState state = query.getState(x, y, z);
        int emit = query.getEmissionRgb4(x, y, z);

        if (prev != 0 && (emit == 0 || state.canOcclude())) {
            storage.setLight(x, y, z, 0);
            propagator.enqueueDecrease(x, y, z, prev);
        }
        if (emit != 0) {
            activeEmitters.put(packedPos, emit);
            storage.setLight(x, y, z, emit);
            propagator.enqueueIncrease(x, y, z, emit);
        } else {
            activeEmitters.remove(packedPos);
            if (!state.canOcclude()) {
                // Re-seed from brightest neighbor if block was broken/opened
                int best = 0;
                for (Direction dir : Direction.values()) {
                    int nVal = storage.getLight(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
                    best = Rgb4Color.componentMax(best, nVal);
                }
                if (Rgb4Color.maxComponent(best) > 1) {
                    propagator.enqueueIncrease(x, y, z, best);
                }
            }
        }
        synchronized (dirtyLock) {
            ColoredLightPropagator.markSectionAndBorders(
                    (sx, sy, sz) -> dirtySectionsForRender.add(SectionPos.asLong(sx, sy, sz)),
                    x, y, z
            );
        }
    }

    private void flushDirtySections(Minecraft mc, int maxSections) {
        if (mc.levelRenderer == null) {
            return;
        }
        long[] toRebuild;
        synchronized (dirtyLock) {
            if (dirtySectionsForRender.isEmpty()) {
                return;
            }
            int count = Math.min(maxSections, dirtySectionsForRender.size());
            toRebuild = new long[count];
            var it = dirtySectionsForRender.iterator();
            for (int i = 0; i < count && it.hasNext(); i++) {
                toRebuild[i] = it.nextLong();
                it.remove();
            }
        }
        for (long key : toRebuild) {
            mc.levelRenderer.setSectionDirty(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
        }
    }

    /**
     * Samples the RGB color tint multiplier for a vertex/block position.
     * Safe to call from Embeddium chunk meshing worker threads.
     */
    public void sampleTintMultiplier(BlockAndTintGetter level, BlockPos pos, int blockLight, int skyLight, float[] outRgb) {
        outRgb[0] = 1.0f;
        outRgb[1] = 1.0f;
        outRgb[2] = 1.0f;
        if (!isActive() || blockLight <= 0) {
            return;
        }
        int packed = storage.getLight(pos.getX(), pos.getY(), pos.getZ());
        if (packed == 0) {
            // Check 6 neighbors gently for faces adjacent to solid emissive blocks
            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();
            packed = Rgb4Color.componentMax(
                    Rgb4Color.componentMax(storage.getLight(x + 1, y, z), storage.getLight(x - 1, y, z)),
                    Rgb4Color.componentMax(
                            Rgb4Color.componentMax(storage.getLight(x, y + 1, z), storage.getLight(x, y - 1, z)),
                            Rgb4Color.componentMax(storage.getLight(x, y, z + 1), storage.getLight(x, y, z - 1))
                    )
            );
            if (packed == 0) {
                return;
            }
        }

        Rgb4Color.toNormalizedTint(packed, outRgb);
        // Weight tint by block-light share vs sky-light with user-configurable daytime boost
        LuxAddonConfig cfg = LuxAddonConfig.get();
        float bNorm = blockLight / 15.0f;
        float sNorm = skyLight / 15.0f;
        float skySuppression = 0.65f * (1.0f - cfg.coloredLightDaytimeBoost);
        float blockShare = Math.min(1.0f, (bNorm * bNorm) / (bNorm * bNorm + sNorm * sNorm * skySuppression + 0.02f));
        float mixAmount = Math.min(1.0f, cfg.coloredLightSaturation * blockShare);

        outRgb[0] = 1.0f + (outRgb[0] - 1.0f) * mixAmount;
        outRgb[1] = 1.0f + (outRgb[1] - 1.0f) * mixAmount;
        outRgb[2] = 1.0f + (outRgb[2] - 1.0f) * mixAmount;
    }

    @FunctionalInterface
    public interface EmitterConsumer {
        void accept(double wx, double wy, double wz, int rgb4, double distSq);
    }

    public void collectNearbyEmitters(double camX, double camY, double camZ, double maxDistSq, EmitterConsumer consumer) {
        if (!isActive() || activeEmitters.isEmpty()) {
            return;
        }
        for (var entry : activeEmitters.entrySet()) {
            long packed = entry.getKey();
            double wx = BlockPos.getX(packed) + 0.5;
            double wy = BlockPos.getY(packed) + 0.5;
            double wz = BlockPos.getZ(packed) + 0.5;
            double dx = wx - camX;
            double dy = wy - camY;
            double dz = wz - camZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= maxDistSq) {
                consumer.accept(wx, wy, wz, entry.getValue(), distSq);
            }
        }
    }

    public static int multiplyAbgrColor(int abgr, float rMul, float gMul, float bMul) {
        int a = (abgr >>> 24) & 0xFF;
        int b = (abgr >>> 16) & 0xFF;
        int g = (abgr >>> 8) & 0xFF;
        int r = abgr & 0xFF;

        int nr = Math.min(255, Math.max(0, Math.round(r * rMul)));
        int ng = Math.min(255, Math.max(0, Math.round(g * gMul)));
        int nb = Math.min(255, Math.max(0, Math.round(b * bMul)));
        return (a << 24) | (nb << 16) | (ng << 8) | nr;
    }

    public static int packClientBlockTint(BlockAndTintGetter level, BlockPos pos, int packedLight, int abgrColor) {
        int blockLight = (packedLight >>> 4) & 0xF;
        int skyLight = (packedLight >>> 20) & 0xF;
        if (blockLight <= 0) {
            return abgrColor;
        }
        float[] mul = SCRATCH_RGB.get();
        INSTANCE.sampleTintMultiplier(level, pos, blockLight, skyLight, mul);
        return multiplyAbgrColor(abgrColor, mul[0], mul[1], mul[2]);
    }

    public ColoredLightStorage storageForTest() {
        return storage;
    }

    private void ensureLevel(ClientLevel level) {
        if (currentLevel != level) {
            reset();
            currentLevel = level;
        }
    }

    public void reset() {
        levelGeneration.incrementAndGet();
        storage.clear();
        if (propagatorLock.tryLock()) {
            try {
                propagator.clear();
                pendingResetClear = false;
            } finally {
                propagatorLock.unlock();
            }
        } else {
            pendingResetClear = true;
        }
        synchronized (pendingBlockLock) {
            pendingBlockUpdates.clear();
        }
        pendingChunkScans.clear();
        synchronized (dirtyLock) {
            dirtySectionsForRender.clear();
        }
        activeEmitters.clear();
        currentLevel = null;
        active = false;
    }

    /**
     * Thread-safe, section-cached BlockQuery operating directly on loaded {@link LevelChunkSection}s
     * without triggering chunk loads or main-thread level queries.
     */
    private static final class LevelBlockQuery implements ColoredLightPropagator.BlockQuery {
        private final ClientLevel level;
        private final int minBuildY;
        private final int maxBuildY;
        private final int minSection;
        private final BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        private int lastChunkX = Integer.MIN_VALUE;
        private int lastChunkZ = Integer.MIN_VALUE;
        private LevelChunk lastChunk;
        private int lastSecY = Integer.MIN_VALUE;
        private LevelChunkSection lastSection;
        private int lastBlockX = Integer.MIN_VALUE;
        private int lastBlockY = Integer.MIN_VALUE;
        private int lastBlockZ = Integer.MIN_VALUE;
        private BlockState lastState;

        private LevelBlockQuery(ClientLevel level) {
            this.level = level;
            this.minBuildY = level.getMinBuildHeight();
            this.maxBuildY = level.getMaxBuildHeight();
            this.minSection = level.getMinSection();
        }

        BlockState getState(int x, int y, int z) {
            if (lastState != null && x == lastBlockX && y == lastBlockY && z == lastBlockZ) {
                return lastState;
            }
            int cx = x >> 4;
            int cz = z >> 4;
            if (cx != lastChunkX || cz != lastChunkZ) {
                lastChunkX = cx;
                lastChunkZ = cz;
                lastChunk = level.getChunkSource().getChunkNow(cx, cz);
                lastSecY = Integer.MIN_VALUE;
                lastSection = null;
            }
            BlockState state;
            if (lastChunk == null) {
                state = Blocks.AIR.defaultBlockState();
            } else {
                int sy = y >> 4;
                if (sy != lastSecY) {
                    lastSecY = sy;
                    int idx = sy - minSection;
                    LevelChunkSection[] secs = lastChunk.getSections();
                    lastSection = (idx >= 0 && idx < secs.length) ? secs[idx] : null;
                }
                state = (lastSection == null || lastSection.hasOnlyAir())
                        ? Blocks.AIR.defaultBlockState()
                        : lastSection.getBlockState(x & 15, y & 15, z & 15);
            }
            lastBlockX = x;
            lastBlockY = y;
            lastBlockZ = z;
            lastState = state;
            return state;
        }

        @Override
        public boolean isOpaque(int x, int y, int z) {
            BlockState state = getState(x, y, z);
            if (!state.canOcclude()) {
                return false;
            }
            try {
                return state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) >= 15;
            } catch (Throwable ignored) {
                return true;
            }
        }

        @Override
        public int getEmissionRgb4(int x, int y, int z) {
            mpos.set(x, y, z);
            return LightColorRegistry.getEmissionRgb4(getState(x, y, z), EmptyBlockGetter.INSTANCE, mpos);
        }

        @Override
        public int getFilterRgb4(int x, int y, int z) {
            return LightColorRegistry.getFilterRgb4(getState(x, y, z));
        }

        @Override
        public int minY() {
            return minBuildY;
        }

        @Override
        public int maxY() {
            return maxBuildY;
        }
    }
}
