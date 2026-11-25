package dev.lhlux.coloredlight;

import net.minecraft.core.BlockPos;

/**
 * Clean-room per-channel 12-bit RGB BFS flood-fill propagator (increase + decrease) with stained-glass
 * filter support, opaque occlusion, and zero-allocation primitive ring-buffer queues.
 */
public final class ColoredLightPropagator {
    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    private static final int FULL_FILTER = Rgb4Color.pack(15, 15, 15);
    private static final int INITIAL_QUEUE_CAPACITY = 4096;
    private static final int MAX_QUEUE_CAPACITY = 262_144;

    /**
     * World-independent block query adapter so propagation is easily unit-tested without a live ClientLevel.
     */
    public interface BlockQuery {
        boolean isOpaque(int x, int y, int z);
        int getEmissionRgb4(int x, int y, int z);
        int getFilterRgb4(int x, int y, int z);
        int minY();
        int maxY();
    }

    /**
     * Primitive ring-buffer queue packing coordinates into {@code long} via {@link BlockPos#asLong}
     * and 12-bit RGB4 into {@code short} to eliminate per-node heap allocations during BFS floods.
     */
    private static final class PrimitiveLightQueue {
        private long[] positions = new long[INITIAL_QUEUE_CAPACITY];
        private short[] values = new short[INITIAL_QUEUE_CAPACITY];
        private int mask = INITIAL_QUEUE_CAPACITY - 1;
        private int head;
        private int tail;
        private int size;

        boolean isEmpty() {
            return size == 0;
        }

        void add(int x, int y, int z, int rgb4) {
            if (size >= positions.length) {
                if (!grow()) {
                    return; // Cap reached; drop excess rather than risking OOM
                }
            }
            positions[tail] = BlockPos.asLong(x, y, z);
            values[tail] = (short) rgb4;
            tail = (tail + 1) & mask;
            size++;
        }

        long peekPos() {
            return positions[head];
        }

        int pollValue() {
            int val = values[head] & 0xFFFF;
            head = (head + 1) & mask;
            size--;
            return val;
        }

        void clear() {
            head = 0;
            tail = 0;
            size = 0;
        }

        private boolean grow() {
            int oldCap = positions.length;
            if (oldCap >= MAX_QUEUE_CAPACITY) {
                return false;
            }
            int newCap = Math.min(MAX_QUEUE_CAPACITY, oldCap << 1);
            long[] newPos = new long[newCap];
            short[] newVal = new short[newCap];
            int firstHalf = Math.min(size, oldCap - head);
            System.arraycopy(positions, head, newPos, 0, firstHalf);
            System.arraycopy(values, head, newVal, 0, firstHalf);
            if (firstHalf < size) {
                int rem = size - firstHalf;
                System.arraycopy(positions, 0, newPos, firstHalf, rem);
                System.arraycopy(values, 0, newVal, firstHalf, rem);
            }
            positions = newPos;
            values = newVal;
            mask = newCap - 1;
            head = 0;
            tail = size;
            return true;
        }
    }

    private final PrimitiveLightQueue increaseQueue = new PrimitiveLightQueue();
    private final PrimitiveLightQueue decreaseQueue = new PrimitiveLightQueue();

    public void enqueueIncrease(int x, int y, int z, int rgb4) {
        if (rgb4 != 0) {
            increaseQueue.add(x, y, z, rgb4);
        }
    }

    public void enqueueDecrease(int x, int y, int z, int prevRgb4) {
        if (prevRgb4 != 0) {
            decreaseQueue.add(x, y, z, prevRgb4);
        }
    }

    public int propagate(ColoredLightStorage storage, BlockQuery query, int maxSteps, DirtySectionSink dirtySink) {
        int steps = 0;

        // 1. Drain decrease queue first so removed emitters clear old color cleanly before re-seeding
        while (!decreaseQueue.isEmpty() && steps < maxSteps) {
            long packedPos = decreaseQueue.peekPos();
            int oldVal = decreaseQueue.pollValue();
            steps++;
            int cx = BlockPos.getX(packedPos);
            int cy = BlockPos.getY(packedPos);
            int cz = BlockPos.getZ(packedPos);

            int rO = Rgb4Color.red(oldVal);
            int gO = Rgb4Color.green(oldVal);
            int bO = Rgb4Color.blue(oldVal);

            for (int dir = 0; dir < 6; dir++) {
                int nx = cx + DX[dir];
                int ny = cy + DY[dir];
                int nz = cz + DZ[dir];
                if (ny < query.minY() || ny >= query.maxY()) {
                    continue;
                }
                int neighborVal = storage.getLight(nx, ny, nz);
                if (neighborVal == 0) {
                    continue;
                }
                int rN = Rgb4Color.red(neighborVal);
                int gN = Rgb4Color.green(neighborVal);
                int bN = Rgb4Color.blue(neighborVal);

                boolean shouldClear = (rN > 0 && rN < rO) || (gN > 0 && gN < gO) || (bN > 0 && bN < bO);
                if (shouldClear) {
                    storage.setLight(nx, ny, nz, 0);
                    markSectionAndBorders(dirtySink, nx, ny, nz);
                    decreaseQueue.add(nx, ny, nz, neighborVal);
                    int emit = query.getEmissionRgb4(nx, ny, nz);
                    if (emit != 0) {
                        storage.setLight(nx, ny, nz, emit);
                        increaseQueue.add(nx, ny, nz, emit);
                    }
                } else {
                    increaseQueue.add(nx, ny, nz, neighborVal);
                }
            }
        }

        // 2. Drain increase queue
        while (!increaseQueue.isEmpty() && steps < maxSteps) {
            long packedPos = increaseQueue.peekPos();
            increaseQueue.pollValue();
            steps++;
            int cx = BlockPos.getX(packedPos);
            int cy = BlockPos.getY(packedPos);
            int cz = BlockPos.getZ(packedPos);

            int srcVal = storage.getLight(cx, cy, cz);
            if (srcVal == 0 || Rgb4Color.maxComponent(srcVal) <= 1) {
                continue;
            }

            for (int dir = 0; dir < 6; dir++) {
                int nx = cx + DX[dir];
                int ny = cy + DY[dir];
                int nz = cz + DZ[dir];
                if (ny < query.minY() || ny >= query.maxY()) {
                    continue;
                }
                if (query.isOpaque(nx, ny, nz)) {
                    continue;
                }
                int filter = query.getFilterRgb4(nx, ny, nz);
                int rF = filter == FULL_FILTER ? 15 : Rgb4Color.red(filter);
                int gF = filter == FULL_FILTER ? 15 : Rgb4Color.green(filter);
                int bF = filter == FULL_FILTER ? 15 : Rgb4Color.blue(filter);

                int attenuated = Rgb4Color.attenuate(srcVal, rF, gF, bF);
                if (attenuated == 0) {
                    continue;
                }

                int existing = storage.getLight(nx, ny, nz);
                int merged = Rgb4Color.componentMax(existing, attenuated);
                if (merged != existing) {
                    storage.setLight(nx, ny, nz, merged);
                    markSectionAndBorders(dirtySink, nx, ny, nz);
                    if (Rgb4Color.maxComponent(merged) > 1) {
                        increaseQueue.add(nx, ny, nz, merged);
                    }
                }
            }
        }
        return steps;
    }

    public boolean hasPendingWork() {
        return !increaseQueue.isEmpty() || !decreaseQueue.isEmpty();
    }

    public void clear() {
        increaseQueue.clear();
        decreaseQueue.clear();
    }

    /**
     * Marks the block's own chunk section dirty plus any neighboring sections touching a section border
     * (e.g. placing a torch at y=64 updates section y=3 below so the sand floor tints immediately).
     */
    static void markSectionAndBorders(DirtySectionSink sink, int x, int y, int z) {
        int sx = x >> 4;
        int sy = y >> 4;
        int sz = z >> 4;
        sink.markDirty(sx, sy, sz);
        int lx = x & 15;
        int ly = y & 15;
        int lz = z & 15;
        if (lx == 0) sink.markDirty(sx - 1, sy, sz);
        else if (lx == 15) sink.markDirty(sx + 1, sy, sz);
        if (ly == 0) sink.markDirty(sx, sy - 1, sz);
        else if (ly == 15) sink.markDirty(sx, sy + 1, sz);
        if (lz == 0) sink.markDirty(sx, sy, sz - 1);
        else if (lz == 15) sink.markDirty(sx, sy, sz + 1);
    }

    @FunctionalInterface
    public interface DirtySectionSink {
        void markDirty(int secX, int secY, int secZ);
    }
}
