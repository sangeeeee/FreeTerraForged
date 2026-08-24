package raccoonman.reterraforged.data.worldgen.preset;

import java.util.Map;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;

public final class PresetWorldPresets {
	private PresetWorldPresets() {
	}

	public static void bootstrap(Preset preset, BootstrapContext<WorldPreset> context, ResourceKey<WorldPreset> key) {
		var dimensionTypes = context.lookup(Registries.DIMENSION_TYPE);
		HolderGetter<NoiseGeneratorSettings> noiseSettings = context.lookup(Registries.NOISE_SETTINGS);
		HolderGetter<Biome> biomes = context.lookup(Registries.BIOME);
		HolderGetter<MultiNoiseBiomeSourceParameterList> biomeParameters = context.lookup(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);

		LevelStem overworld = new LevelStem(
				dimensionTypes.getOrThrow(BuiltinDimensionTypes.OVERWORLD),
				new NoiseBasedChunkGenerator(
						MultiNoiseBiomeSource.createFromPreset(biomeParameters.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)),
						noiseSettings.getOrThrow(NoiseGeneratorSettings.OVERWORLD)
				)
		);
		LevelStem nether = new LevelStem(
				dimensionTypes.getOrThrow(BuiltinDimensionTypes.NETHER),
				new NoiseBasedChunkGenerator(
						MultiNoiseBiomeSource.createFromPreset(biomeParameters.getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER)),
						noiseSettings.getOrThrow(NoiseGeneratorSettings.NETHER)
				)
		);
		LevelStem end = new LevelStem(
				dimensionTypes.getOrThrow(BuiltinDimensionTypes.END),
				new NoiseBasedChunkGenerator(TheEndBiomeSource.create(biomes), noiseSettings.getOrThrow(NoiseGeneratorSettings.END))
		);

		context.register(key, new WorldPreset(Map.of(
				LevelStem.OVERWORLD, overworld,
				LevelStem.NETHER, nether,
				LevelStem.END, end
		)));
	}
}
