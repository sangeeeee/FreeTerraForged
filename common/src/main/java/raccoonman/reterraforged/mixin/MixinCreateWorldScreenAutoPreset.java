package raccoonman.reterraforged.mixin;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import raccoonman.reterraforged.client.gui.screen.worldselection.AutoPresetManager;

@Mixin(CreateWorldScreen.class)
public abstract class MixinCreateWorldScreenAutoPreset {
	@Shadow
	private boolean recreated;

	@Inject(
			method = "openFresh(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/Screen;)V",
			at = @At("HEAD")
	)
	private static void reterraforged$prepareFreshWorld(Minecraft minecraft, Screen parent, CallbackInfo callbackInfo) {
		AutoPresetManager.prepareFreshWorld();
	}

	@ModifyArg(
			method = "openFresh(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/Screen;)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/packs/repository/PackRepository;<init>([Lnet/minecraft/server/packs/repository/RepositorySource;)V"
			),
			index = 0
	)
	private static RepositorySource[] reterraforged$includeCachedRepositorySource(RepositorySource[] sources) {
		return AutoPresetManager.appendCachedRepositorySource(sources);
	}

	@ModifyArg(
			method = "openFresh(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/Screen;)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/screens/worldselection/CreateWorldScreen;createDefaultLoadConfig(Lnet/minecraft/server/packs/repository/PackRepository;Lnet/minecraft/world/level/WorldDataConfiguration;)Lnet/minecraft/server/WorldLoader$InitConfig;"
			),
			index = 1
	)
	private static WorldDataConfiguration reterraforged$enableCachedPack(WorldDataConfiguration configuration) {
		return AutoPresetManager.addCachedPack(configuration);
	}

	@ModifyArg(
			method = "openFresh(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/Screen;)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/screens/worldselection/CreateWorldScreen;<init>(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/gui/screens/worldselection/WorldCreationContext;Ljava/util/Optional;Ljava/util/OptionalLong;)V"
			),
			index = 3
	)
	private static Optional<ResourceKey<WorldPreset>> reterraforged$selectInitialPreset(Optional<ResourceKey<WorldPreset>> preset) {
		return AutoPresetManager.selectInitialPreset(preset);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void reterraforged$applyAutoPreset(CallbackInfo callbackInfo) {
		if(!this.recreated) {
			AutoPresetManager.onInit((CreateWorldScreen) (Object) this);
		}
	}

	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"))
	private void reterraforged$selectAutoPresetAfterReload(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callbackInfo) {
		if(!this.recreated) {
			AutoPresetManager.onRender((CreateWorldScreen) (Object) this);
		}
	}
}
