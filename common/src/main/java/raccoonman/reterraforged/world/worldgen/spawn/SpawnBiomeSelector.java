package raccoonman.reterraforged.world.worldgen.spawn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.tags.FluidTags;
import raccoonman.reterraforged.RTFCommon;

public final class SpawnBiomeSelector {
	private static final int SEARCH_STEP_QUARTS = 16;
	private static final int LOCAL_ATTEMPTS = 192;
	private static final int LOCAL_RADIUS = 256;
	private static final Map<MinecraftServer, SpawnTarget> TARGETS = Collections.synchronizedMap(new WeakHashMap<>());

	private SpawnBiomeSelector() {
	}

	public static void initialize(MinecraftServer server) {
		try {
			initializeInternal(server);
		} catch (Exception e) {
			TARGETS.remove(server);
			RTFCommon.LOGGER.error("Failed to initialize configured spawn biome selection; using Minecraft's normal spawn", e);
		}
	}

	private static void initializeInternal(MinecraftServer server) {
		ServerLevel overworld = server.overworld();
		SpawnBiomeConfig.Settings config = SpawnBiomeConfig.synchronize(server.registryAccess());
		Set<ResourceLocation> configured = config.enabledBiomes();
		int searchRadius = config.searchRadius();
		SpawnCondition condition = config.condition();
		String fingerprint = fingerprint(configured, searchRadius, condition);
		SpawnBiomeSelectionData data = overworld.getDataStorage().computeIfAbsent(
				SpawnBiomeSelectionData.FACTORY,
				SpawnBiomeSelectionData.DATA_NAME
		);
		data.refreshOriginalSpawn(overworld.getSharedSpawnPos());

		if(configured.isEmpty()) {
			TARGETS.remove(server);
			data.clear(fingerprint);
			overworld.setDefaultSpawnPos(data.originalSpawn(), 0.0F);
			data.setOverworldSpawnApplied(false);
			RTFCommon.LOGGER.info("No spawn biomes are enabled; using Minecraft's normal overworld spawn selection");
			return;
		}

		SpawnTarget target = data.matches(fingerprint) ? data.target() : null;
		if(target != null && !isStillValid(server, target, configured, condition)) {
			target = null;
		}
		if(target == null) {
			target = findTarget(server, configured, data.originalSpawn(), searchRadius, condition);
		}

		if(target == null) {
			TARGETS.remove(server);
			data.clear(fingerprint);
			overworld.setDefaultSpawnPos(data.originalSpawn(), 0.0F);
			data.setOverworldSpawnApplied(false);
			RTFCommon.LOGGER.warn("None of the enabled spawn biomes had a legal surface spawn; using Minecraft's normal overworld spawn");
			return;
		}

		TARGETS.put(server, target);
		data.set(fingerprint, target);
		if(target.dimension().equals(Level.OVERWORLD)) {
			overworld.setDefaultSpawnPos(target.position(), 0.0F);
			data.setOverworldSpawnApplied(true);
		} else {
			overworld.setDefaultSpawnPos(data.originalSpawn(), 0.0F);
			data.setOverworldSpawnApplied(false);
		}
		RTFCommon.LOGGER.info(
				"Selected spawn biome {} in dimension {} at {}",
				target.biome().location(),
				target.dimension().location(),
				target.position()
		);
	}

	public static ResourceKey<Level> dimensionForNewPlayer(MinecraftServer server, ResourceKey<Level> fallback) {
		SpawnTarget target = TARGETS.get(server);
		return target == null ? fallback : target.dimension();
	}

	public static void positionNewPlayer(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if(server == null) {
			return;
		}
		SpawnTarget target = TARGETS.get(server);
		if(target == null || !player.serverLevel().dimension().equals(target.dimension())) {
			return;
		}
		BlockPos position = target.position();
		player.moveTo(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D, player.getYRot(), player.getXRot());
	}

	private static SpawnTarget findTarget(MinecraftServer server, Set<ResourceLocation> configured, BlockPos overworldOrigin, int searchRadius, SpawnCondition condition) {
		List<DimensionSearch> dimensions = dimensionsWithEnabledBiomes(server, configured);
		Set<ResourceLocation> available = dimensions.stream()
				.flatMap(dimension -> dimension.biomes().stream())
				.map(ResourceKey::location)
				.collect(java.util.stream.Collectors.toSet());
		configured.stream()
				.filter(id -> !available.contains(id))
				.forEach(id -> RTFCommon.LOGGER.warn(
						"Enabled spawn biome {} is not used by any loaded dimension and will be ignored",
						id
				));
		long seed = server.overworld().getSeed() ^ fingerprint(configured, searchRadius, condition).hashCode() ^ 0x525446535041574EL;
		RandomSource random = RandomSource.create(seed);
		RTFCommon.LOGGER.info(
				"Searching up to {} blocks from each dimension origin for a legal spawn in any of {} enabled biomes with condition {}",
				searchRadius,
				configured.size(),
				condition.canonical()
		);

		while(!dimensions.isEmpty()) {
			DimensionSearch dimension = removeWeighted(dimensions, random);
			ServerLevel level = dimension.level();
			BlockPos origin = level.dimension().equals(Level.OVERWORLD)
					? overworldOrigin
					: new BlockPos(0, level.getChunkSource().getGenerator().getSeaLevel(), 0);
			LocatedSpawn located = locateSurfaceSpawn(level, dimension.biomes(), origin, searchRadius, random, condition);
			if(located != null) {
				return new SpawnTarget(level.dimension(), located.biome(), located.position());
			}
		}
		return null;
	}

	private static List<DimensionSearch> dimensionsWithEnabledBiomes(MinecraftServer server, Set<ResourceLocation> configured) {
		List<DimensionSearch> result = new ArrayList<>();
		for(ServerLevel level : server.getAllLevels()) {
			Set<ResourceKey<Biome>> enabled = new LinkedHashSet<>();
			for(Holder<Biome> holder : level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
				holder.unwrapKey().filter(key -> configured.contains(key.location())).ifPresent(enabled::add);
			}
			if(!enabled.isEmpty()) {
				result.add(new DimensionSearch(level, Set.copyOf(enabled)));
			}
		}
		return result;
	}

	private static DimensionSearch removeWeighted(List<DimensionSearch> dimensions, RandomSource random) {
		int totalWeight = dimensions.stream().mapToInt(dimension -> dimension.biomes().size()).sum();
		int choice = random.nextInt(totalWeight);
		for(int i = 0; i < dimensions.size(); i++) {
			choice -= dimensions.get(i).biomes().size();
			if(choice < 0) {
				return dimensions.remove(i);
			}
		}
		throw new IllegalStateException("Failed to choose an enabled spawn dimension");
	}

	private static LocatedSpawn locateSurfaceSpawn(ServerLevel level, Set<ResourceKey<Biome>> enabled, BlockPos origin, int searchRadius, RandomSource random, SpawnCondition condition) {
		BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
		RandomState randomState = level.getChunkSource().randomState();
		Predicate<Holder<Biome>> predicate = holder -> holder.unwrapKey().filter(enabled::contains).isPresent();
		Set<Integer> searchHeights = new LinkedHashSet<>();
		int minY = level.getMinBuildHeight() + 1;
		int maxY = level.getMaxBuildHeight() - 2;
		searchHeights.add(clamp(level.getChunkSource().getGenerator().getSeaLevel(), minY, maxY));
		searchHeights.add(clamp(level.getChunkSource().getGenerator().getSpawnHeight(level), minY, maxY));
		searchHeights.add(minY + (maxY - minY) / 2);
		boolean foundBiomeSample = false;

		for(int y : searchHeights) {
			Pair<BlockPos, Holder<Biome>> randomMatch = source.findBiomeHorizontal(
					origin.getX(), y, origin.getZ(), searchRadius, SEARCH_STEP_QUARTS,
					predicate, random, false, randomState.sampler()
			);
			if(randomMatch != null) {
				foundBiomeSample = true;
				LocatedSpawn safe = findSafeNear(level, enabled, randomMatch.getFirst(), random, condition);
				if(safe != null) {
					return safe;
				}
			}

			Pair<BlockPos, Holder<Biome>> nearest = source.findBiomeHorizontal(
					origin.getX(), y, origin.getZ(), searchRadius, SEARCH_STEP_QUARTS,
					predicate, random, true, randomState.sampler()
			);
			if(nearest != null) {
				foundBiomeSample = true;
				LocatedSpawn safe = findSafeNear(level, enabled, nearest.getFirst(), random, condition);
				if(safe != null) {
					return safe;
				}
			}
		}
		if(foundBiomeSample) {
			RTFCommon.LOGGER.warn(
					"Found enabled biome samples within {} blocks in dimension {}, but none had a legal surface spawn",
					searchRadius,
					level.dimension().location()
			);
		} else {
			RTFCommon.LOGGER.warn(
					"No sample from any of the {} enabled biomes was found within {} blocks in dimension {}",
					enabled.size(),
					searchRadius,
					level.dimension().location()
			);
		}
		return null;
	}

	private static LocatedSpawn findSafeNear(ServerLevel level, Set<ResourceKey<Biome>> enabled, BlockPos located, RandomSource random, SpawnCondition condition) {
		LocatedSpawn exact = findSafeInColumn(level, enabled, located.getX(), located.getY(), located.getZ(), condition);
		if(exact != null) {
			return exact;
		}
		for(int attempt = 0; attempt < LOCAL_ATTEMPTS; attempt++) {
			int x = located.getX() + random.nextInt(LOCAL_RADIUS * 2 + 1) - LOCAL_RADIUS;
			int z = located.getZ() + random.nextInt(LOCAL_RADIUS * 2 + 1) - LOCAL_RADIUS;
			LocatedSpawn safe = findSafeInColumn(level, enabled, x, located.getY(), z, condition);
			if(safe != null) {
				return safe;
			}
		}
		return null;
	}

	private static LocatedSpawn findSafeInColumn(ServerLevel level, Set<ResourceKey<Biome>> enabled, int x, int locatedY, int z, SpawnCondition condition) {
		if(level.dimensionType().hasCeiling()) {
			int minY = level.getMinBuildHeight() + 1;
			int maxY = level.getMaxBuildHeight() - 2;
			int startY = clamp(Math.max(locatedY + 32, level.getChunkSource().getGenerator().getSpawnHeight(level)), minY, maxY);
			for(int y = startY; y >= minY; y--) {
				BlockPos position = new BlockPos(x, y, z);
				ResourceKey<Biome> biome = enabledBiomeAt(level, position, enabled);
				if(biome != null && isSafe(level, position, false) && condition.test(level, position)) {
					return new LocatedSpawn(biome, position);
				}
			}
			return null;
		}

		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos position = new BlockPos(x, y, z);
		ResourceKey<Biome> biome = enabledBiomeAt(level, position, enabled);
		return biome != null && isSafe(level, position, true) && condition.test(level, position)
				? new LocatedSpawn(biome, position)
				: null;
	}

	private static ResourceKey<Biome> enabledBiomeAt(ServerLevel level, BlockPos position, Set<ResourceKey<Biome>> enabled) {
		return level.getBiome(position).unwrapKey().filter(enabled::contains).orElse(null);
	}

	private static boolean isSafe(ServerLevel level, BlockPos position, boolean allowWaterSurface) {
		if(position.getY() <= level.getMinBuildHeight() || position.getY() >= level.getMaxBuildHeight() - 1) {
			return false;
		}
		if(!level.getWorldBorder().isWithinBounds(position)) {
			return false;
		}

		BlockState feet = level.getBlockState(position);
		BlockState head = level.getBlockState(position.above());
		BlockPos floorPosition = position.below();
		BlockState floor = level.getBlockState(floorPosition);
		if(!isPassable(level, position, feet) || !isPassable(level, position.above(), head) || isHazardous(floor)) {
			return false;
		}

		boolean solidFloor = floor.isFaceSturdy(level, floorPosition, Direction.UP);
		FluidState fluid = floor.getFluidState();
		boolean waterSurface = allowWaterSurface && fluid.is(FluidTags.WATER);
		return solidFloor || waterSurface;
	}

	private static boolean isPassable(ServerLevel level, BlockPos position, BlockState state) {
		return state.getFluidState().isEmpty()
				&& state.getCollisionShape(level, position).isEmpty()
				&& !state.is(Blocks.POWDER_SNOW);
	}

	private static boolean isHazardous(BlockState state) {
		return state.is(Blocks.MAGMA_BLOCK)
				|| state.is(Blocks.CACTUS)
				|| state.is(Blocks.CAMPFIRE)
				|| state.is(Blocks.SOUL_CAMPFIRE)
				|| state.is(Blocks.FIRE)
				|| state.is(Blocks.SOUL_FIRE)
				|| state.is(Blocks.POWDER_SNOW);
	}

	private static boolean matchesBiome(ServerLevel level, BlockPos position, ResourceKey<Biome> target) {
		return level.getBiome(position).unwrapKey().filter(target::equals).isPresent();
	}

	private static boolean isStillValid(MinecraftServer server, SpawnTarget target, Set<ResourceLocation> configured, SpawnCondition condition) {
		if(!configured.contains(target.biome().location())) {
			return false;
		}
		ServerLevel level = server.getLevel(target.dimension());
		return level != null
				&& level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream()
						.anyMatch(holder -> holder.unwrapKey().filter(target.biome()::equals).isPresent())
				&& matchesBiome(level, target.position(), target.biome())
				&& isSafe(level, target.position(), !level.dimensionType().hasCeiling())
				&& condition.test(level, target.position());
	}

	private static String fingerprint(Set<ResourceLocation> configured, int searchRadius, SpawnCondition condition) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(("search_radius=" + searchRadius + '\n').getBytes(StandardCharsets.UTF_8));
			digest.update(("spawn_condition=" + condition.canonical() + '\n').getBytes(StandardCharsets.UTF_8));
			configured.stream().sorted().forEach(location -> {
				digest.update(location.toString().getBytes(StandardCharsets.UTF_8));
				digest.update((byte) '\n');
			});
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	public record SpawnTarget(ResourceKey<Level> dimension, ResourceKey<Biome> biome, BlockPos position) {
	}

	private record DimensionSearch(ServerLevel level, Set<ResourceKey<Biome>> biomes) {
	}

	private record LocatedSpawn(ResourceKey<Biome> biome, BlockPos position) {
	}
}
