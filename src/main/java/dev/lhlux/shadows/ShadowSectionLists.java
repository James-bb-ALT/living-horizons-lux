package dev.lhlux.shadows;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Clean-room distance/frustum-culled RenderSection list builder for near and far shadow cascades.
 */
public final class ShadowSectionLists implements ChunkRenderListIterable {
    private static final int SECTION_GEOMETRY_BIT = 1;
    private static final float SECTION_HALF = 8.0f;

    private final List<ChunkRenderList> lists;

    private ShadowSectionLists(List<ChunkRenderList> lists) {
        this.lists = lists;
    }

    @Override
    public Iterator<ChunkRenderList> iterator(boolean reverse) {
        if (!reverse) {
            return lists.iterator();
        }
        ListIterator<ChunkRenderList> it = lists.listIterator(lists.size());
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return it.hasPrevious();
            }

            @Override
            public ChunkRenderList next() {
                return it.previous();
            }
        };
    }

    public int regionCount() {
        return lists.size();
    }

    public static BuildResult build(
            ChunkRenderer chunkRenderer,
            Long2ReferenceMap<RenderSection> sections,
            ShadowCascade nearCascade,
            boolean buildNear,
            ShadowCascade farCascade,
            boolean buildFar,
            int frameId,
            Cache nearCache,
            Cache farCache,
            Vector3f cameraPos
    ) {
        Accumulator nearAcc = buildNear ? new Accumulator(nearCache, frameId) : null;
        Accumulator farAcc = buildFar ? new Accumulator(farCache, frameId) : null;
        OrthoBox nearBox = buildNear ? new OrthoBox(nearCascade, nearCascade.snappedAnchor()) : null;
        OrthoBox farBox = buildFar ? new OrthoBox(farCascade, farCascade.snappedAnchor()) : null;
        Vector3f scratch = new Vector3f();

        for (RenderSection section : sections.values()) {
            if (section == null || section.isDisposed() || !section.isBuilt() || (section.getFlags() & SECTION_GEOMETRY_BIT) == 0) {
                continue;
            }
            RenderRegion region = section.getRegion();
            if (region == null) {
                continue;
            }
            if (nearAcc != null && nearBox.intersects(section, scratch)) {
                nearAcc.add(region, section);
            }
            if (farAcc != null && farBox.intersects(section, scratch)) {
                farAcc.add(region, section);
            }
        }

        return new BuildResult(
                chunkRenderer,
                nearAcc != null ? nearAcc.finish() : null,
                farAcc != null ? farAcc.finish() : null
        );
    }

    private static final class OrthoBox {
        private final Vector3f center;
        private final Matrix4f lightView;
        private final float radius;
        private final float halfX;
        private final float halfY;

        private OrthoBox(ShadowCascade cascade, Vector3f cameraPos) {
            this.center = cameraPos;
            this.lightView = cascade.lightView();
            this.radius = cascade.radius();

            Vector3f wx = new Vector3f(SECTION_HALF, 0.0f, 0.0f);
            Vector3f wy = new Vector3f(0.0f, SECTION_HALF, 0.0f);
            Vector3f wz = new Vector3f(0.0f, 0.0f, SECTION_HALF);
            lightView.transformDirection(wx);
            lightView.transformDirection(wy);
            lightView.transformDirection(wz);
            this.halfX = Math.abs(wx.x) + Math.abs(wy.x) + Math.abs(wz.x);
            this.halfY = Math.abs(wx.y) + Math.abs(wy.y) + Math.abs(wz.y);
        }

        private boolean intersects(RenderSection section, Vector3f scratch) {
            float dx = (float) section.getCenterX() - center.x;
            float dy = (float) section.getCenterY() - center.y;
            float dz = (float) section.getCenterZ() - center.z;
            scratch.set(dx, dy, dz);
            lightView.transformDirection(scratch);
            return scratch.x + halfX >= -radius && scratch.x - halfX <= radius
                    && scratch.y + halfY >= -radius && scratch.y - halfY <= radius;
        }
    }

    public record BuildResult(ChunkRenderer chunkRenderer, ShadowSectionLists nearLists, ShadowSectionLists farLists) {
    }

    public static final class Cache {
        private final Reference2ObjectOpenHashMap<RenderRegion, ChunkRenderList> pool = new Reference2ObjectOpenHashMap<>();

        private ChunkRenderList obtain(RenderRegion region) {
            ChunkRenderList list = pool.get(region);
            if (list == null) {
                list = new ChunkRenderList(region);
                pool.put(region, list);
            }
            return list;
        }

        private void trim(Reference2ObjectLinkedOpenHashMap<RenderRegion, ChunkRenderList> active) {
            int maxRetained = active.size() * 2 + 48;
            if (pool.size() <= maxRetained) {
                return;
            }
            Iterator<RenderRegion> it = pool.keySet().iterator();
            while (it.hasNext()) {
                if (!active.containsKey(it.next())) {
                    it.remove();
                }
            }
        }

        public void clear() {
            pool.clear();
        }
    }

    private static final class Accumulator {
        private final Cache cache;
        private final int frameId;
        private final Reference2ObjectLinkedOpenHashMap<RenderRegion, ChunkRenderList> active = new Reference2ObjectLinkedOpenHashMap<>();

        private Accumulator(Cache cache, int frameId) {
            this.cache = cache;
            this.frameId = frameId;
        }

        private void add(RenderRegion region, RenderSection section) {
            ChunkRenderList list = active.get(region);
            if (list == null) {
                list = cache.obtain(region);
                list.reset(frameId);
                active.put(region, list);
            }
            list.add(section);
        }

        private ShadowSectionLists finish() {
            cache.trim(active);
            return new ShadowSectionLists(new ArrayList<>(active.values()));
        }
    }
}
