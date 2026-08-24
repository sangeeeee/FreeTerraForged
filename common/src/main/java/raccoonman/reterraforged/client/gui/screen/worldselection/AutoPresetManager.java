package raccoonman.reterraforged.client.gui.screen.worldselection;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.JsonOps;

import dev.architectury.platform.Platform;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.data.worldgen.PresetDatapackExporter;
import raccoonman.reterraforged.data.worldgen.preset.settings.FlowSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.platform.ConfigUtil;

public final class AutoPresetManager {
	public static final ResourceKey<WorldPreset> WORLD_PRESET_KEY = ResourceKey.create(Registries.WORLD_PRESET, RTFCommon.location("auto_preset"));
	public static final String STAGED_PACK_FILE_NAME = "reterraforged-auto-preset.zip";

	private static final Path PRESET_DIRECTORY = ConfigUtil.rtf("presets");
	private static final Path CACHE_DIRECTORY = ConfigUtil.rtf("cache");
	private static final Path CACHED_PACK = CACHE_DIRECTORY.resolve("auto_preset.zip");
	private static final Path CACHE_METADATA = CACHE_DIRECTORY.resolve("auto_preset_cache.json");
	private static final String CACHE_FORMAT = "reterraforged-auto-preset-v1";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static volatile String activeWorldTypeName = "";

	private AutoPresetManager() {
	}

	public static boolean apply(CreateWorldScreen screen) {
		try {
			AutoPresetConfig config = AutoPresetConfig.load();
			if(!config.enabled()) {
				activeWorldTypeName = "";
				return false;
			}

			String presetFileName = config.preset().trim();
			String worldTypeName = config.worldTypeName().trim();
			if(presetFileName.isEmpty() || worldTypeName.isEmpty()) {
				throw new IOException("enabled auto_preset.json requires non-empty preset and worldTypeName values");
			}

			Files.createDirectories(PRESET_DIRECTORY);
			Files.createDirectories(CACHE_DIRECTORY);
			Path presetPath = resolvePresetPath(presetFileName);
			byte[] configBytes = Files.readAllBytes(AutoPresetConfig.PATH);
			byte[] presetBytes = Files.readAllBytes(presetPath);
			String sourceHash = computeSourceHash(
					configBytes,
					presetBytes,
					Platform.getMinecraftVersion(),
					Platform.getMod(RTFCommon.MOD_ID).getVersion()
			);

			Preset preset = decodePreset(presetPath);
			if(!isCacheValid(sourceHash)) {
				rebuildCache(preset, screen, worldTypeName, sourceHash);
				RTFCommon.LOGGER.info("Generated automatic preset datapack from {}", presetPath);
			} else {
				RTFCommon.LOGGER.info("Using cached automatic preset datapack for {}", presetPath);
			}

			activeWorldTypeName = worldTypeName;
			FlowSettings.CurrentPresetState.set(preset.flow());
			stageAndApply(screen);
			return true;
		} catch (Exception e) {
			activeWorldTypeName = "";
			RTFCommon.LOGGER.error("Failed to apply automatic world preset; falling back to the normal world type", e);
			return false;
		}
	}

	public static boolean isAutoPreset(WorldCreationUiState.WorldTypeEntry entry) {
		return entry != null && entry.preset() != null && entry.preset().unwrapKey().filter(WORLD_PRESET_KEY::equals).isPresent();
	}

	public static String getActiveWorldTypeName() {
		return activeWorldTypeName;
	}

	private static Path resolvePresetPath(String configuredPath) throws IOException {
		Path presetDirectory = PRESET_DIRECTORY.toAbsolutePath().normalize();
		Path presetPath = presetDirectory.resolve(configuredPath).normalize();
		if(!presetPath.startsWith(presetDirectory) || presetPath.equals(presetDirectory)) {
			throw new IOException("Auto preset path must stay inside " + presetDirectory);
		}
		if(!Files.isRegularFile(presetPath)) {
			throw new IOException("Auto preset does not exist: " + presetPath);
		}
		return presetPath;
	}

	private static Preset decodePreset(Path presetPath) throws IOException {
		try (Reader reader = Files.newBufferedReader(presetPath, StandardCharsets.UTF_8)) {
			return Preset.DIRECT_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader))
					.resultOrPartial(error -> RTFCommon.LOGGER.error("Failed to decode automatic preset: {}", error))
					.orElseThrow(() -> new IOException("Invalid ReTerraForged preset: " + presetPath));
		}
	}

	private static boolean isCacheValid(String sourceHash) {
		if(!Files.isRegularFile(CACHED_PACK) || !Files.isRegularFile(CACHE_METADATA)) {
			return false;
		}
		try (Reader reader = Files.newBufferedReader(CACHE_METADATA, StandardCharsets.UTF_8)) {
			CacheMetadata metadata = GSON.fromJson(reader, CacheMetadata.class);
			return metadata != null
					&& sourceHash.equals(metadata.sourceHash())
					&& metadata.packHash() != null
					&& metadata.packHash().equals(hashFile(CACHED_PACK));
		} catch (Exception e) {
			RTFCommon.LOGGER.warn("Ignoring invalid automatic preset cache metadata", e);
			return false;
		}
	}

	private static void rebuildCache(Preset preset, CreateWorldScreen screen, String presetName, String sourceHash) throws IOException {
		Path temporaryPack = CACHE_DIRECTORY.resolve("auto_preset.zip.tmp");
		Path temporaryMetadata = CACHE_DIRECTORY.resolve("auto_preset_cache.json.tmp");
		Files.deleteIfExists(temporaryPack);
		Files.deleteIfExists(temporaryMetadata);

		try {
			PresetDatapackExporter.exportAutoPreset(
					preset,
					screen.getUiState().getSettings().worldgenLoadContext(),
					temporaryPack,
					presetName,
					WORLD_PRESET_KEY
			);
			String packHash = hashFile(temporaryPack);
			moveReplacing(temporaryPack, CACHED_PACK);
			Files.writeString(temporaryMetadata, GSON.toJson(new CacheMetadata(sourceHash, packHash)) + System.lineSeparator(), StandardCharsets.UTF_8);
			moveReplacing(temporaryMetadata, CACHE_METADATA);
		} finally {
			Files.deleteIfExists(temporaryPack);
			Files.deleteIfExists(temporaryMetadata);
		}
	}

	private static void stageAndApply(CreateWorldScreen screen) throws IOException {
		Pair<Path, PackRepository> selection = screen.getDataPackSelectionSettings(screen.getUiState().getSettings().dataConfiguration());
		Path stagedPack = selection.getFirst().resolve(STAGED_PACK_FILE_NAME);
		Files.copy(CACHED_PACK, stagedPack, StandardCopyOption.REPLACE_EXISTING);

		PackRepository repository = selection.getSecond();
		repository.reload();
		if(!repository.addPack("file/" + STAGED_PACK_FILE_NAME)) {
			throw new IOException("Minecraft did not discover staged automatic preset datapack: " + stagedPack);
		}

		AutoPresetSelection selectionListener = new AutoPresetSelection();
		screen.getUiState().addListener(selectionListener::selectWhenAvailable);
		screen.tryApplyNewDataPacks(repository, false, ignored -> {
		});
	}

	private static String computeSourceHash(byte[] configBytes, byte[] presetBytes, String minecraftVersion, String modVersion) {
		MessageDigest digest = sha256();
		update(digest, CACHE_FORMAT.getBytes(StandardCharsets.UTF_8));
		update(digest, minecraftVersion.getBytes(StandardCharsets.UTF_8));
		update(digest, modVersion.getBytes(StandardCharsets.UTF_8));
		update(digest, configBytes);
		update(digest, presetBytes);
		return HexFormat.of().formatHex(digest.digest());
	}

	private static String hashFile(Path path) throws IOException {
		MessageDigest digest = sha256();
		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[8192];
			int read;
			while((read = input.read(buffer)) >= 0) {
				if(read > 0) {
					digest.update(buffer, 0, read);
				}
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	private static void update(MessageDigest digest, byte[] value) {
		digest.update((byte) 0);
		digest.update(value);
	}

	private static void moveReplacing(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private record CacheMetadata(String sourceHash, String packHash) {
	}

	private static final class AutoPresetSelection {
		private boolean selected;

		private void selectWhenAvailable(WorldCreationUiState state) {
			if(this.selected) {
				return;
			}

			Registry<WorldPreset> registry = state.getSettings().worldgenLoadContext().registryOrThrow(Registries.WORLD_PRESET);
			registry.getHolder(WORLD_PRESET_KEY).ifPresent(holder -> {
				WorldCreationUiState.WorldTypeEntry entry = state.getNormalPresetList().stream()
						.filter(AutoPresetManager::isAutoPreset)
						.findFirst()
						.orElseGet(() -> {
							WorldCreationUiState.WorldTypeEntry created = new WorldCreationUiState.WorldTypeEntry(holder);
							state.getNormalPresetList().add(0, created);
							return created;
						});
				this.selected = true;
				state.setWorldType(entry);
			});
		}
	}
}
