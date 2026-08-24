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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.JsonOps;

import dev.architectury.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.data.worldgen.PresetDatapackExporter;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.platform.ConfigUtil;

public final class AutoPresetManager {
	public static final ResourceKey<WorldPreset> WORLD_PRESET_KEY = ResourceKey.create(Registries.WORLD_PRESET, RTFCommon.location("auto_preset"));
	public static final String STAGED_PACK_FILE_NAME = "reterraforged-auto-preset.zip";

	private static final String PACK_ID = "file/" + STAGED_PACK_FILE_NAME;
	private static final Path CACHE_DIRECTORY = ConfigUtil.rtf("cache");
	private static final Path PACK_DIRECTORY = CACHE_DIRECTORY.resolve("auto_preset");
	private static final Path CACHED_PACK = PACK_DIRECTORY.resolve(STAGED_PACK_FILE_NAME);
	private static final Path CACHE_METADATA = CACHE_DIRECTORY.resolve("auto_preset_cache.json");
	private static final String CACHE_FORMAT = "reterraforged-auto-preset-v2";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<CreateWorldScreen, State> STATES = new WeakHashMap<>();

	private static volatile boolean useCachedPack;
	private static volatile String activeWorldTypeName = "";

	private AutoPresetManager() {
	}

	/**
	 * Runs before Minecraft creates the initial worldgen registry load. A valid cache
	 * can therefore participate in that first load instead of forcing a second one.
	 */
	public static void prepareFreshWorld() {
		AutoPresetConfig config = AutoPresetConfig.reload();
		activeWorldTypeName = config.enabled() ? config.worldTypeName() : "";
		useCachedPack = isCacheValid(config);
		if(config.enabled()) {
			RTFCommon.LOGGER.info("Automatic preset cache {} for {}", useCachedPack ? "hit" : "miss", config.preset());
		}
	}

	public static RepositorySource[] appendCachedRepositorySource(RepositorySource[] sources) {
		if(!useCachedPack) {
			return sources;
		}

		RepositorySource[] expanded = Arrays.copyOf(sources, sources.length + 1);
		expanded[sources.length] = new FolderRepositorySource(
				PACK_DIRECTORY,
				PackType.SERVER_DATA,
				PackSource.FEATURE,
				Minecraft.getInstance().directoryValidator()
		);
		return expanded;
	}

	public static WorldDataConfiguration addCachedPack(WorldDataConfiguration configuration) {
		if(!useCachedPack) {
			return configuration;
		}

		DataPackConfig packs = configuration.dataPacks();
		List<String> enabled = new ArrayList<>(packs.getEnabled());
		if(!enabled.contains(PACK_ID)) {
			enabled.add(PACK_ID);
		}
		List<String> disabled = packs.getDisabled().stream().filter(id -> !PACK_ID.equals(id)).toList();
		return new WorldDataConfiguration(new DataPackConfig(List.copyOf(enabled), disabled), configuration.enabledFeatures());
	}

	public static Optional<ResourceKey<WorldPreset>> selectInitialPreset(Optional<ResourceKey<WorldPreset>> preset) {
		return useCachedPack ? Optional.of(WORLD_PRESET_KEY) : preset;
	}

	public static void onInit(CreateWorldScreen screen) {
		AutoPresetConfig config = AutoPresetConfig.get();
		if(!config.enabled()) {
			return;
		}

		State state = STATES.get(screen);
		// Reloading datapacks initializes the same CreateWorldScreen again. Once
		// this screen has been handled, preserve the user's explicit world-type
		// selection instead of selecting the configured auto preset again.
		if(state != null) {
			return;
		}
		if(hasPackLoaded(screen) && selectAutoPreset(screen)) {
			try {
				stageCachedPack(screen);
				STATES.put(screen, State.APPLIED);
			} catch (Exception e) {
				fail(screen, config, e);
			}
			return;
		}
		STATES.put(screen, State.APPLYING);
		try {
			validate(config);
			Preset preset = readPreset(config.presetPath());
			Pair<Path, PackRepository> settings = screen.getDataPackSelectionSettings(screen.getUiState().getSettings().dataConfiguration());
			if(settings == null) {
				throw new IOException("Could not create the temporary datapack directory");
			}

			Path stagedPack = settings.getFirst().resolve(STAGED_PACK_FILE_NAME);
			if(isCacheValid(config)) {
				copyCachedPackTo(stagedPack);
			} else {
				rebuildCache(preset, screen, config);
				copyCachedPackTo(stagedPack);
				RTFCommon.LOGGER.info("Updated automatic preset cache at {}", CACHED_PACK);
			}

			PackRepository repository = settings.getSecond();
			repository.reload();
			if(!repository.addPack(PACK_ID)) {
				throw new IOException("Cached datapack was not discovered as " + PACK_ID);
			}
			screen.tryApplyNewDataPacks(repository, false, ignored -> fail(
					screen,
					config,
					new IOException("Minecraft rejected the automatic preset datapack")
			));
		} catch (Exception e) {
			fail(screen, config, e);
		}
	}

	/** Minecraft replaces the UI state after datapack reload, so selection is retried here. */
	public static void onRender(CreateWorldScreen screen) {
		if(STATES.get(screen) == State.APPLYING && hasPackLoaded(screen) && selectAutoPreset(screen)) {
			STATES.put(screen, State.APPLIED);
		}
	}

	public static boolean isAutoPreset(WorldCreationUiState.WorldTypeEntry entry) {
		return entry != null
				&& entry.preset() != null
				&& entry.preset().unwrapKey().filter(WORLD_PRESET_KEY::equals).isPresent();
	}

	public static String getActiveWorldTypeName() {
		return activeWorldTypeName;
	}

	private static void validate(AutoPresetConfig config) throws IOException {
		if(config.worldTypeName().isEmpty()) {
			throw new IOException("enabled auto_preset.json requires a non-empty worldTypeName value");
		}
		Path presetPath = config.presetPath();
		if(!Files.isRegularFile(presetPath)) {
			throw new IOException("Auto preset does not exist: " + presetPath);
		}
	}

	private static Preset readPreset(Path presetPath) throws IOException {
		if(!Files.isRegularFile(presetPath)) {
			throw new IOException("Auto preset does not exist: " + presetPath);
		}
		try (Reader reader = Files.newBufferedReader(presetPath, StandardCharsets.UTF_8)) {
			return Preset.DIRECT_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader))
					.getOrThrow(error -> new IllegalArgumentException("Invalid ReTerraForged preset " + presetPath + ": " + error));
		}
	}

	private static boolean hasPackLoaded(CreateWorldScreen screen) {
		return screen.getUiState().getSettings().dataConfiguration().dataPacks().getEnabled().contains(PACK_ID);
	}

	private static boolean selectAutoPreset(CreateWorldScreen screen) {
		Optional<WorldCreationUiState.WorldTypeEntry> entry = screen.getUiState().getNormalPresetList().stream()
				.filter(AutoPresetManager::isAutoPreset)
				.findFirst();
		entry.filter(found -> !isAutoPreset(screen.getUiState().getWorldType())).ifPresent(screen.getUiState()::setWorldType);
		return entry.isPresent();
	}

	private static boolean isCacheValid(AutoPresetConfig config) {
		if(!config.enabled() || !Files.isRegularFile(CACHED_PACK) || !Files.isRegularFile(CACHE_METADATA)) {
			return false;
		}
		try {
			validate(config);
			if(Files.size(CACHED_PACK) <= 0L) {
				return false;
			}
			try (Reader reader = Files.newBufferedReader(CACHE_METADATA, StandardCharsets.UTF_8)) {
				CacheMetadata metadata = GSON.fromJson(reader, CacheMetadata.class);
				return metadata != null
						&& computeSourceHash(config).equals(metadata.sourceHash())
						&& metadata.packHash() != null
						&& metadata.packHash().equals(hashFile(CACHED_PACK));
			}
		} catch (Exception e) {
			RTFCommon.LOGGER.warn("Could not validate the automatic preset cache", e);
			return false;
		}
	}

	private static void rebuildCache(Preset preset, CreateWorldScreen screen, AutoPresetConfig config) throws IOException {
		Files.createDirectories(CACHE_DIRECTORY);
		Files.createDirectories(PACK_DIRECTORY);
		Path temporaryPack = CACHE_DIRECTORY.resolve("auto_preset.zip.tmp");
		Path temporaryMetadata = CACHE_DIRECTORY.resolve("auto_preset_cache.json.tmp");
		Files.deleteIfExists(temporaryPack);
		Files.deleteIfExists(temporaryMetadata);

		try {
			PresetDatapackExporter.exportAutoPreset(
					preset,
					screen.getUiState().getSettings().worldgenLoadContext(),
					temporaryPack,
					config.worldTypeName(),
					WORLD_PRESET_KEY
			);
			String packHash = hashFile(temporaryPack);
			moveReplacing(temporaryPack, CACHED_PACK);
			Files.writeString(
					temporaryMetadata,
					GSON.toJson(new CacheMetadata(computeSourceHash(config), packHash)) + System.lineSeparator(),
					StandardCharsets.UTF_8
			);
			moveReplacing(temporaryMetadata, CACHE_METADATA);
		} finally {
			Files.deleteIfExists(temporaryPack);
			Files.deleteIfExists(temporaryMetadata);
		}
	}

	private static void copyCachedPackTo(Path target) throws IOException {
		Files.copy(CACHED_PACK, target, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void stageCachedPack(CreateWorldScreen screen) throws IOException {
		Pair<Path, PackRepository> settings = screen.getDataPackSelectionSettings(screen.getUiState().getSettings().dataConfiguration());
		if(settings == null) {
			throw new IOException("Could not create the temporary datapack directory");
		}
		copyCachedPackTo(settings.getFirst().resolve(STAGED_PACK_FILE_NAME));
	}

	private static String computeSourceHash(AutoPresetConfig config) throws IOException {
		MessageDigest digest = sha256();
		update(digest, CACHE_FORMAT.getBytes(StandardCharsets.UTF_8));
		update(digest, Platform.getMinecraftVersion().getBytes(StandardCharsets.UTF_8));
		update(digest, Platform.getMod(RTFCommon.MOD_ID).getVersion().getBytes(StandardCharsets.UTF_8));
		update(digest, config.preset().getBytes(StandardCharsets.UTF_8));
		update(digest, config.worldTypeName().getBytes(StandardCharsets.UTF_8));
		update(digest, Files.readAllBytes(config.presetPath()));
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
		digest.update(value);
		digest.update((byte) 0);
	}

	private static void moveReplacing(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void fail(CreateWorldScreen screen, AutoPresetConfig config, Exception exception) {
		STATES.put(screen, State.FAILED);
		RTFCommon.LOGGER.error("Failed to automatically apply preset {}", config.preset(), exception);
		String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
		SystemToast.add(
				Minecraft.getInstance().getToasts(),
				SystemToast.SystemToastId.PACK_LOAD_FAILURE,
				Component.literal("ReTerraForged preset"),
				Component.literal(message)
		);
	}

	private record CacheMetadata(String sourceHash, String packHash) {
	}

	private enum State {
		APPLYING,
		APPLIED,
		FAILED
	}
}
