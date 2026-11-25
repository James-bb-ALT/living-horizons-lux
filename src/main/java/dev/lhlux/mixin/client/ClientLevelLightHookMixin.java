package dev.lhlux.mixin.client;

import dev.lhlux.coloredlight.ColoredLightEngine;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds chunk load/unload and block change events into ColoredLightEngine.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelLightHookMixin {
    @Inject(method = "onChunkLoaded", at = @At("TAIL"))
    private void lhlux$onChunkLoaded(net.minecraft.world.level.ChunkPos pos, CallbackInfo ci) {
        ClientLevel self = (ClientLevel) (Object) this;
        LevelChunk chunk = self.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk != null) {
            ColoredLightEngine.get().onChunkLoaded(self, chunk);
        }
    }

    @Inject(method = "unload", at = @At("TAIL"))
    private void lhlux$onChunkUnloaded(LevelChunk chunk, CallbackInfo ci) {
        if (chunk != null) {
            ClientLevel self = (ClientLevel) (Object) this;
            ColoredLightEngine.get().onChunkUnloaded(chunk.getPos().x, chunk.getPos().z, self);
        }
    }

    @Inject(method = "sendBlockUpdated", at = @At("TAIL"))
    private void lhlux$onBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        ClientLevel self = (ClientLevel) (Object) this;
        ColoredLightEngine.get().onBlockChanged(self, pos);
    }
}
