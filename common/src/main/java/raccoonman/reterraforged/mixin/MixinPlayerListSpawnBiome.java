package raccoonman.reterraforged.mixin;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import raccoonman.reterraforged.world.worldgen.spawn.SpawnBiomeSelector;

@Mixin(PlayerList.class)
public abstract class MixinPlayerListSpawnBiome {
	@Unique
	private boolean reterraforged$firstJoin;

	@Inject(method = "placeNewPlayer", at = @At("HEAD"))
	private void reterraforged$resetFirstJoinState(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo callbackInfo) {
		this.reterraforged$firstJoin = false;
	}

	@Redirect(
			method = "placeNewPlayer",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/players/PlayerList;load(Lnet/minecraft/server/level/ServerPlayer;)Ljava/util/Optional;"
			)
	)
	private Optional<CompoundTag> reterraforged$captureFirstJoin(PlayerList playerList, ServerPlayer player) {
		Optional<CompoundTag> playerData = playerList.load(player);
		this.reterraforged$firstJoin = playerData.isEmpty();
		return playerData;
	}

	@ModifyArg(
			method = "placeNewPlayer",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/MinecraftServer;getLevel(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;"
			),
			index = 0
	)
	private ResourceKey<Level> reterraforged$selectFirstJoinDimension(ResourceKey<Level> original) {
		if(!this.reterraforged$firstJoin) {
			return original;
		}
		MinecraftServer server = ((PlayerList) (Object) this).getServer();
		return SpawnBiomeSelector.dimensionForNewPlayer(server, original);
	}

	@Inject(
			method = "placeNewPlayer",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/level/ServerPlayer;setServerLevel(Lnet/minecraft/server/level/ServerLevel;)V",
					shift = At.Shift.AFTER
			)
	)
	private void reterraforged$positionFirstJoinPlayer(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo callbackInfo) {
		if(this.reterraforged$firstJoin) {
			SpawnBiomeSelector.positionNewPlayer(player);
		}
	}

	@Inject(method = "placeNewPlayer", at = @At("RETURN"))
	private void reterraforged$clearFirstJoinState(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo callbackInfo) {
		this.reterraforged$firstJoin = false;
	}
}
