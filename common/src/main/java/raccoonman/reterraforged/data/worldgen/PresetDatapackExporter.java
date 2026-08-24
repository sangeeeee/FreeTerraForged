package raccoonman.reterraforged.data.worldgen;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import org.apache.commons.io.file.PathUtils;

import com.google.common.collect.ImmutableMap;

import net.minecraft.core.RegistryAccess;
import net.minecraft.data.DataGenerator;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;

public final class PresetDatapackExporter {
	private PresetDatapackExporter() {
	}

	public static void export(Preset preset, RegistryAccess registryAccess, Path outputPath, String presetName) throws IOException {
		export(preset, registryAccess, outputPath, presetName, null);
	}

	public static void exportAutoPreset(Preset preset, RegistryAccess registryAccess, Path outputPath, String presetName, ResourceKey<WorldPreset> worldPresetKey) throws IOException {
		export(preset, registryAccess, outputPath, presetName, worldPresetKey);
	}

	private static void export(Preset preset, RegistryAccess registryAccess, Path outputPath, String presetName, ResourceKey<WorldPreset> worldPresetKey) throws IOException {
		Path dataGenPath = Files.createTempDirectory("reterraforged-datagen-");
		Path dataGenOutputPath = dataGenPath.resolve("output");

		try {
			DataGenerator dataGenerator = worldPresetKey == null
					? Datapacks.makePreset(preset, registryAccess, dataGenPath, dataGenOutputPath, presetName)
					: Datapacks.makeAutoPreset(preset, registryAccess, dataGenPath, dataGenOutputPath, presetName, worldPresetKey);
			dataGenerator.run();
			copyToZip(dataGenOutputPath, outputPath);
		} finally {
			PathUtils.deleteDirectory(dataGenPath);
		}
	}

	private static void copyToZip(Path input, Path output) throws IOException {
		Path parent = output.getParent();
		if(parent != null) {
			Files.createDirectories(parent);
		}
		Files.deleteIfExists(output);

		Map<String, String> env = ImmutableMap.of("create", "true");
		URI uri = URI.create("jar:" + output.toUri());
		try (FileSystem fs = FileSystems.newFileSystem(uri, env)) {
			PathUtils.copyDirectory(input, fs.getPath("/"), StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
