package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import raccoonman.reterraforged.client.gui.screen.worldselection.AutoPresetManager;

@Mixin(WorldCreationUiState.WorldTypeEntry.class)
public abstract class MixinWorldTypeEntry {
	@Inject(method = "describePreset", at = @At("HEAD"), cancellable = true)
	private void reterraforged$describeAutoPreset(CallbackInfoReturnable<Component> callbackInfo) {
		WorldCreationUiState.WorldTypeEntry entry = (WorldCreationUiState.WorldTypeEntry) (Object) this;
		String name = AutoPresetManager.getActiveWorldTypeName();
		if(!name.isEmpty() && AutoPresetManager.isAutoPreset(entry)) {
			callbackInfo.setReturnValue(Component.literal(name));
		}
	}
}
