package raccoonman.reterraforged.neoforge;

import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent;
import raccoonman.reterraforged.client.gui.screen.presetconfig.PresetConfigScreen;
import raccoonman.reterraforged.client.gui.screen.worldselection.AutoPresetConfig;

class RTFNeoForgeClient {

	public static void registerPresetEditors(RegisterPresetEditorsEvent event) {
		AutoPresetConfig.ensureCreated();
		// TODO we probably shouldn't register this for the default preset
		event.register(WorldPresets.NORMAL, (screen, ctx) -> new PresetConfigScreen(screen));
	}
}
