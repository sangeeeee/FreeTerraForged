package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import raccoonman.reterraforged.client.gui.screen.worldselection.AutoPresetManager;

@Mixin(CreateWorldScreen.class)
public abstract class MixinCreateWorldScreenAutoPreset {
	@Shadow
	private boolean recreated;

	@Unique
	private boolean reterraforged$autoPresetAttempted;

	@Inject(method = "init", at = @At("TAIL"))
	private void reterraforged$applyAutoPreset(CallbackInfo callbackInfo) {
		if(this.recreated || this.reterraforged$autoPresetAttempted) {
			return;
		}

		this.reterraforged$autoPresetAttempted = true;
		AutoPresetManager.apply((CreateWorldScreen) (Object) this);
	}
}
