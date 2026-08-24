package raccoonman.reterraforged.world.worldgen.spawn;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.core.registries.Registries;

final class SpawnBiomeSelectionData extends SavedData {
	static final String DATA_NAME = "reterraforged_spawn_biome";
	static final Factory<SpawnBiomeSelectionData> FACTORY = new Factory<>(
			SpawnBiomeSelectionData::new,
			SpawnBiomeSelectionData::load,
			DataFixTypes.LEVEL
	);

	private boolean initialized;
	private boolean overworldSpawnApplied;
	private BlockPos originalSpawn = BlockPos.ZERO;
	private String fingerprint = "";
	private String dimension = "";
	private String biome = "";
	private BlockPos position = BlockPos.ZERO;

	private SpawnBiomeSelectionData() {
	}

	private static SpawnBiomeSelectionData load(CompoundTag tag, HolderLookup.Provider registries) {
		SpawnBiomeSelectionData data = new SpawnBiomeSelectionData();
		data.initialized = tag.getBoolean("initialized");
		data.overworldSpawnApplied = tag.getBoolean("overworldSpawnApplied");
		data.originalSpawn = new BlockPos(tag.getInt("originalX"), tag.getInt("originalY"), tag.getInt("originalZ"));
		data.fingerprint = tag.getString("fingerprint");
		data.dimension = tag.getString("dimension");
		data.biome = tag.getString("biome");
		data.position = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
		return data;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		tag.putBoolean("initialized", this.initialized);
		tag.putBoolean("overworldSpawnApplied", this.overworldSpawnApplied);
		tag.putInt("originalX", this.originalSpawn.getX());
		tag.putInt("originalY", this.originalSpawn.getY());
		tag.putInt("originalZ", this.originalSpawn.getZ());
		tag.putString("fingerprint", this.fingerprint);
		tag.putString("dimension", this.dimension);
		tag.putString("biome", this.biome);
		tag.putInt("x", this.position.getX());
		tag.putInt("y", this.position.getY());
		tag.putInt("z", this.position.getZ());
		return tag;
	}

	void refreshOriginalSpawn(BlockPos spawn) {
		boolean currentIsAppliedTarget = this.overworldSpawnApplied
				&& Level.OVERWORLD.location().toString().equals(this.dimension)
				&& this.position.equals(spawn);
		if(!this.initialized || !currentIsAppliedTarget) {
			this.initialized = true;
			this.overworldSpawnApplied = false;
			this.originalSpawn = spawn.immutable();
			this.setDirty();
		}
	}

	BlockPos originalSpawn() {
		return this.originalSpawn;
	}

	boolean matches(String fingerprint) {
		return this.fingerprint.equals(fingerprint);
	}

	SpawnBiomeSelector.SpawnTarget target() {
		if(this.dimension.isEmpty() || this.biome.isEmpty()) {
			return null;
		}
		ResourceLocation dimensionId = ResourceLocation.tryParse(this.dimension);
		ResourceLocation biomeId = ResourceLocation.tryParse(this.biome);
		if(dimensionId == null || biomeId == null) {
			return null;
		}
		return new SpawnBiomeSelector.SpawnTarget(
				ResourceKey.create(Registries.DIMENSION, dimensionId),
				ResourceKey.create(Registries.BIOME, biomeId),
				this.position
		);
	}

	void set(String fingerprint, SpawnBiomeSelector.SpawnTarget target) {
		this.fingerprint = fingerprint;
		this.dimension = target.dimension().location().toString();
		this.biome = target.biome().location().toString();
		this.position = target.position().immutable();
		this.setDirty();
	}

	void setOverworldSpawnApplied(boolean applied) {
		if(this.overworldSpawnApplied != applied) {
			this.overworldSpawnApplied = applied;
			this.setDirty();
		}
	}

	void clear(String fingerprint) {
		this.fingerprint = fingerprint;
		this.dimension = "";
		this.biome = "";
		this.position = BlockPos.ZERO;
		this.setDirty();
	}
}
