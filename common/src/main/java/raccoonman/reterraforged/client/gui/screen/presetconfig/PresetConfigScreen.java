package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import com.mojang.datafixers.util.Pair;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.client.gui.screen.page.LinkedPageScreen;
import raccoonman.reterraforged.client.gui.screen.presetconfig.PresetListPage.PresetEntry;
import raccoonman.reterraforged.data.worldgen.PresetDatapackExporter;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;

public class PresetConfigScreen extends LinkedPageScreen {
	private static final String MANUAL_PRESET_PACK_FILE = "reterraforged-preset.zip";
	private static final String MANUAL_PRESET_PACK_ID = "file/" + MANUAL_PRESET_PACK_FILE;

	private CreateWorldScreen parent;
	private final PreviewComputationCache previewCache = new PreviewComputationCache();
	private String seed;
	private boolean seedInitialized;
	private boolean applySeedOnClose;
	private boolean applyingPreset;

	public PresetConfigScreen(CreateWorldScreen parent) {
		this.parent = parent;
		this.currentPage = new PresetListPage(this);
	}
	
	@Override
	public void onClose() {
		this.previewCache.close();
		super.onClose();
		if(this.applySeedOnClose) {
			this.applySeedToParent();
		}

		// applyNewPackConfig has already installed vanilla's datapack-validation
		// screen. Do not replace it with the parent until validation finishes.
		if(!this.applyingPreset) {
			this.minecraft.setScreen(this.parent);
		}
	}

	PreviewComputationCache previewCache() {
		return this.previewCache;
	}

	public <T extends GuiEventListener & Renderable & NarratableEntry> T addWidgetToScreen(T widget) {
		return this.addRenderableWidget(widget);
	}

	public void removeWidgetFromScreen(AbstractWidget widget) {
		this.removeWidget(widget);
	}

	public void setSeed(String seed) {
		this.seed = seed;
		this.seedInitialized = true;
	}

	public String getSeed() {
		if(!this.seedInitialized) {
			String parentSeed = this.parent.getUiState().getSeed();
			this.seed = parentSeed == null || parentSeed.trim().isEmpty() ? String.valueOf(this.parent.getUiState().getSettings().options().seed()) : parentSeed;
			this.seedInitialized = true;
		}
		return this.seed;
	}
	
	public WorldCreationContext getSettings() {
		WorldCreationContext settings = this.parent.getUiState().getSettings();
		if(this.seedInitialized && this.seed != null && !this.seed.trim().isEmpty()) {
			settings = settings.withOptions((options) -> options.withSeed(WorldOptions.parseSeed(this.seed)));
		}
		return settings;
	}

	@Override
	public void onDone() {
		this.applySeedOnClose = true;
		this.applySeedToParent();
		super.onDone();
		this.applySeedToParent();
	}

	private void applySeedToParent() {
		this.parent.getUiState().setSeed(this.getSeed());
	}

	public void applyPreset(PresetEntry preset) throws IOException {
		Pair<Path, PackRepository> path = this.parent.getDataPackSelectionSettings(this.parent.getUiState().getSettings().dataConfiguration());
		if(path == null) {
			throw new IOException("Could not create the temporary datapack directory");
		}

		Path exportPath = path.getFirst().resolve(MANUAL_PRESET_PACK_FILE);
		this.exportAsDatapack(exportPath, preset);
		PackRepository repository = path.getSecond();
		repository.reload();
		if(!repository.addPack(MANUAL_PRESET_PACK_ID)) {
			throw new IOException("Exported preset datapack was not discovered as " + MANUAL_PRESET_PACK_ID);
		}

		List<String> enabled = List.copyOf(repository.getSelectedIds());
		List<String> disabled = repository.getAvailableIds().stream().filter(id -> !enabled.contains(id)).toList();
		WorldDataConfiguration current = this.parent.getUiState().getSettings().dataConfiguration();
		WorldDataConfiguration requested = new WorldDataConfiguration(
				new DataPackConfig(enabled, disabled),
				current.enabledFeatures()
		);

		this.applyingPreset = true;
		// This pack is overwritten under a stable ID. tryApplyNewDataPacks would
		// skip the reload if that ID was already selected, despite changed contents.
		// Force the vanilla asynchronous reload: it owns the validation screen and
		// restores CreateWorldScreen only after the registries are fully ready.
		try {
			this.parent.applyNewPackConfig(repository, requested, ignored -> this.minecraft.setScreen(this.parent));
		} catch(RuntimeException e) {
			this.applyingPreset = false;
			throw e;
		}
	}
	
	public void exportAsDatapack(Path outputPath, PresetEntry presetEntry) throws IOException {
		RegistryAccess registryAccess = this.getSettings().worldgenLoadContext();

		Preset preset = presetEntry.getPreset();
		Component presetName = presetEntry.getName();
		PresetDatapackExporter.export(preset, registryAccess, outputPath, presetName.getString());
		
		RTFCommon.LOGGER.info("Exported datapack to {}", outputPath);
	}
}
