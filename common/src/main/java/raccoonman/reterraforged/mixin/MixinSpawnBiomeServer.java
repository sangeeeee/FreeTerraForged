package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import raccoonman.reterraforged.world.worldgen.spawn.SpawnBiomeSelector;

@Mixin(MinecraftServer.class)
public abstract class MixinSpawnBiomeServer {
	@Inject(method = "createLevels", at = @At("TAIL"))
	private void reterraforged$initializeSpawnBiomeSelection(ChunkProgressListener listener, CallbackInfo callbackInfo) {
		SpawnBiomeSelector.initialize((MinecraftServer) (Object) this);
	}
}
