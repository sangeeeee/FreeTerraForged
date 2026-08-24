# FreeTerraForged
A community driven fork of the Legendary ReTerraForged project for modern minecraft providing heavily customizable overworld terrain generation.
Several novel features have been community contributed to the fork so far including 3D rivers, waterfalls and island generation.
Additional feature contributions are welcomed via forking and raising a merge PR.

### We stand on the shoulders of giants
- Original project https://github.com/TerraForged/TerraForged
- Builds on the substantial post v1.19+ work of Racoonman2 in https://github.com/racoonman2/ReTerraForged
- Finishes the Neoforge port work started by Equalizer32 in https://github.com/equalizer32/NeoTerraForged/tree/1.21.1

### Licensing 
- Continued under the permissive MIT license as per all historic contributions.

***

# Getting started

### Customize a new world
<img width="856" height="526" alt="image" src="https://github.com/user-attachments/assets/941bc4b1-9334-4c08-9197-9ff729876369" />

---

### Select a preset 
<img width="1186" height="754" alt="image" src="https://github.com/user-attachments/assets/65a8e11e-1d76-42ce-bfd5-9fd34c9adfda" />

- Use existing settings directly via [Done]
- Customize the selected preset via [>>]
- Copy or create fresh presets using the right hand menu

---

### Customize your world
<img width="3840" height="2100" alt="image" src="https://github.com/user-attachments/assets/0b053a98-c8ec-4b92-b6ad-8dfc41eacf8a" />

- There are many pages of settings accessible via paging through using [<<] and [>>]
- Click Done to save your edits.
- Click Cancel to abandon your edits.

Please note that default presets will never be overwritten, but your edits will impact the world you generate.
To persist your edits between sessions please create or copy a preset to use as your base, then edit it.

---

### Use a preset as the default world type

On first client startup, ReTerraForged creates `config/reterraforged/auto_preset.json`:

```json
{
  "enabled": false,
  "preset": "",
  "worldTypeName": ""
}
```

Place the referenced preset in `config/reterraforged/presets`, then enable it. For example, `"preset": "earth2.json"` and `"worldTypeName": "Earth 2"` add and select an `Earth 2` world type whenever the fresh world creation screen opens. The automatic world type is read-only; select the normal world type to use the existing Customize workflow.

The generated datapack and its source/package hashes are stored in `config/reterraforged/cache`. The datapack is regenerated only when the configuration, preset, Minecraft version, mod version, or cached package changes. World seeds are not stored in this cache and remain random by default.

### Select the initial spawn biome

After mod loading completes, ReTerraForged creates `config/reterraforged/spawn_biomes.txt` from all installed vanilla and mod biome resources. When a world is created or loaded, its live registry is synchronized again so world-specific datapack biomes are included before spawn selection. New entries are disabled by default:

```text
!minecraft:desert
!minecraft:plains
!examplemod:custom_biome
```

Remove `!` from one or more lines to enable them. The mod selects one enabled biome per world/configuration, locates a legal surface or water-level position, and remembers the result. Existing line choices survive registry synchronization; removed biomes disappear and newly added biomes receive `!`. If every line has `!`, or no enabled biome has a legal position, vanilla overworld spawn selection is used.

Biomes used by another loaded dimension can also be selected. A player with no existing player data starts in that dimension; ordinary respawn fallback remains the vanilla overworld spawn.

---

# Bugs
- Any issues encountered should be raised as Github issues with as much supporting documentation as you can provide, ideally latest.log and screenshots at a minimum.
