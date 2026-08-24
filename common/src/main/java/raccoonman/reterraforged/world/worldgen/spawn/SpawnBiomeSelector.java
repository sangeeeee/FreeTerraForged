package raccoonman.reterraforged.world.worldgen.spawn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
import net.minecraft.core.registries.Registries;
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
	private static final int SEARCH_RADIUS = 16_384;
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
		Set<ResourceLocation> configured = SpawnBiomeConfig.synchronize(server.registryAccess());
		String fingerprint = fingerprint(configured);
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
		if(target != null && !isStillValid(server, target, configured)) {
			target = null;
		}
		if(target == null) {
			target = findTarget(server, configured, data.originalSpawn());
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

	private static SpawnTarget findTarget(MinecraftServer server, Set<ResourceLocation> configured, BlockPos overworldOrigin) {
		Map<ResourceKey<Biome>, List<ServerLevel>> dimensionsByBiome = dimensionsByBiome(server);
		Set<ResourceLocation> available = dimensionsByBiome.keySet().stream()
				.map(ResourceKey::location)
				.collect(java.util.stream.Collectors.toSet());
		configured.stream()
				.filter(id -> !available.contains(id))
				.forEach(id -> RTFCommon.LOGGER.warn(
						"Enabled spawn biome {} is not used by any loaded dimension and will be ignored",
						id
				));
		List<ResourceKey<Biome>> choices = configured.stream()
				.map(id -> ResourceKey.create(Registries.BIOME, id))
				.filter(dimensionsByBiome::containsKey)
				.collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
		long seed = server.overworld().getSeed() ^ fingerprint(configured).hashCode() ^ 0x525446535041574EL;
		Collections.shuffle(choices, new java.util.Random(seed));
		RandomSource random = RandomSource.create(seed);

		for(ResourceKey<Biome> biome : choices) {
			List<ServerLevel> dimensions = new ArrayList<>(dimensionsByBiome.get(biome));
			Collections.shuffle(dimensions, new java.util.Random(seed ^ biome.location().hashCode()));
			for(ServerLevel level : dimensions) {
				BlockPos origin = level.dimension().equals(Level.OVERWORLD)
						? overworldOrigin
						: new BlockPos(0, level.getChunkSource().getGenerator().getSeaLevel(), 0);
				BlockPos position = locateSurfaceSpawn(level, biome, origin, random);
				if(position != null) {
					return new SpawnTarget(level.dimension(), biome, position);
				}
			}
			RTFCommon.LOGGER.warn("Could not find a legal surface spawn for enabled biome {}", biome.location());
		}
		return null;
	}

	private static Map<ResourceKey<Biome>, List<ServerLevel>> dimensionsByBiome(MinecraftServer server) {
		Map<ResourceKey<Biome>, List<ServerLevel>> result = new HashMap<>();
		for(ServerLevel level : server.getAllLevels()) {
			for(Holder<Biome> holder : level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
				holder.unwrapKey().ifPresent(key -> result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(level));
			}
		}
		return result;
	}

	private static BlockPos locateSurfaceSpawn(ServerLevel level, ResourceKey<Biome> target, BlockPos origin, RandomSource random) {
		BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
		RandomState randomState = level.getChunkSource().randomState();
		Predicate<Holder<Biome>> predicate = holder -> holder.unwrapKey().filter(target::equals).isPresent();
		Set<Integer> searchHeights = new LinkedHashSet<>();
		int minY = level.getMinBuildHeight() + 1;
		int maxY = level.getMaxBuildHeight() - 2;
		searchHeights.add(clamp(level.getChunkSource().getGenerator().getSeaLevel(), minY, maxY));
		searchHeights.add(clamp(level.getChunkSource().getGenerator().getSpawnHeight(level), minY, maxY));
		searchHeights.add(minY + (maxY - minY) / 2);

		for(int y : searchHeights) {
			Pair<BlockPos, Holder<Biome>> nearest = source.findBiomeHorizontal(
					origin.getX(), y, origin.getZ(), SEARCH_RADIUS, SEARCH_STEP_QUARTS,
					predicate, random, true, randomState.sampler()
			);
			if(nearest != null) {
				BlockPos safe = findSafeNear(level, target, nearest.getFirst(), random);
				if(safe != null) {
					return safe;
				}
			}

			Pair<BlockPos, Holder<Biome>> randomMatch = source.findBiomeHorizontal(
					origin.getX(), y, origin.getZ(), SEARCH_RADIUS, SEARCH_STEP_QUARTS,
					predicate, random, false, randomState.sampler()
			);
			if(randomMatch != null) {
				BlockPos safe = findSafeNear(level, target, randomMatch.getFirst(), random);
				if(safe != null) {
					return safe;
				}
			}
		}
		return null;
	}

	private static BlockPos findSafeNear(ServerLevel level, ResourceKey<Biome> target, BlockPos located, RandomSource random) {
		BlockPos exact = findSafeInColumn(level, target, located.getX(), located.getY(), located.getZ());
		if(exact != null) {
			return exact;
		}
		for(int attempt = 0; attempt < LOCAL_ATTEMPTS; attempt++) {
			int x = located.getX() + random.nextInt(LOCAL_RADIUS * 2 + 1) - LOCAL_RADIUS;
			int z = located.getZ() + random.nextInt(LOCAL_RADIUS * 2 + 1) - LOCAL_RADIUS;
			BlockPos safe = findSafeInColumn(level, target, x, located.getY(), z);
			if(safe != null) {
				return safe;
			}
		}
		return null;
	}

	private static BlockPos findSafeInColumn(ServerLevel level, ResourceKey<Biome> target, int x, int locatedY, int z) {
		if(level.dimensionType().hasCeiling()) {
			int minY = level.getMinBuildHeight() + 1;
			int maxY = level.getMaxBuildHeight() - 2;
			int startY = clamp(Math.max(locatedY + 32, level.getChunkSource().getGenerator().getSpawnHeight(level)), minY, maxY);
			for(int y = startY; y >= minY; y--) {
				BlockPos position = new BlockPos(x, y, z);
				if(matchesBiome(level, position, target) && isSafe(level, position, false)) {
					return position;
				}
			}
			return null;
		}

		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos position = new BlockPos(x, y, z);
		return matchesBiome(level, position, target) && isSafe(level, position, true) ? position : null;
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

	private static boolean isStillValid(MinecraftServer server, SpawnTarget target, Set<ResourceLocation> configured) {
		if(!configured.contains(target.biome().location())) {
			return false;
		}
		ServerLevel level = server.getLevel(target.dimension());
		return level != null
				&& level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream()
						.anyMatch(holder -> holder.unwrapKey().filter(target.biome()::equals).isPresent())
				&& matchesBiome(level, target.position(), target.biome())
				&& isSafe(level, target.position(), !level.dimensionType().hasCeiling());
	}

	private static String fingerprint(Set<ResourceLocation> configured) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
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
}
