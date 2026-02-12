# Tier-Based Material Auto-Generation Refactor

## Goal
Replace hardcoded material-to-tier mappings with auto-generation from TC's `HeadMaterialStats.tier()`. Unified config used for both tool AND armor recipe replacement. Keep the % total-parts threshold feature.

## Current System
- `MaterialMappingConfig.java` — reads/writes `config/nomorevanillatools/material_mappings.json`
- Hardcoded defaults in `createDefaultConfig()` (lines 61-93): wooden→[wood,bamboo], stone→[rock,flint,basalt], iron→[iron,pig_iron], golden→[gold,rose_gold], diamond→[diamond]
- **Current defaults have errors**: pig_iron is DIAMOND tier in TC (not iron), rose_gold is GOLD tier
- Config stores `Map<String, Set<String>>` (tier name → material IDs as LinkedHashSet)
- `getMaterialsForTier(tier)` returns the set, used by `TinkerMaterialIngredient.test()` and `buildDisplayItems()`

## TC API for Tier Lookup

```java
import slimeknights.tconstruct.library.materials.MaterialRegistry;
import slimeknights.tconstruct.library.materials.definition.MaterialId;
import slimeknights.tconstruct.tools.stats.HeadMaterialStats;
import net.minecraft.world.item.Tiers; // WOOD, STONE, IRON, GOLD, DIAMOND, NETHERITE

MaterialId materialId = MaterialId.tryParse("tconstruct:iron");
Optional<HeadMaterialStats> stats = MaterialRegistry.getInstance()
    .getMaterialStats(materialId, HeadMaterialStats.ID);
Tier harvestTier = stats.get().tier(); // → Tiers.IRON
```

To scan ALL materials:
```java
// MaterialRegistry.getInstance() has methods to iterate all materials
// For each material, check if it has HeadMaterialStats
// If yes, group by .tier() into the config map
```

## Tier Name Mapping

| TC Tier Constant | Config Tier Name |
|---|---|
| `Tiers.WOOD` | `"wooden"` |
| `Tiers.STONE` | `"stone"` |
| `Tiers.IRON` | `"iron"` |
| `Tiers.GOLD` | `"golden"` |
| `Tiers.DIAMOND` | `"diamond"` |
| `Tiers.NETHERITE` | `"netherite"` |

## Key Constraint: Timing
- `MaterialRegistry` data loads from datapacks during world/server load
- Auto-generate **CANNOT** happen in `commonSetup()` — registry is empty at that point
- Must happen on server start (e.g., `ServerAboutToStartEvent` or `ServerStartingEvent`)
- Need fallback for JEI display items requested before config exists

## Key Constraint: Config Persistence
- First boot with no config → auto-generate from registry → write config
- Subsequent boots with existing config → load config, do NOT overwrite
- Need a regenerate mechanism (command or config flag) for when mods change

## Files to Modify
- **`MaterialMappingConfig.java`** — replace `createDefaultConfig()` with `generateFromRegistry()`, add timing-aware initialization
- **`TinkerMaterialIngredient.java`** — `test()` and `buildDisplayItems()` mostly unchanged (still read from config), but may need lazy-init fallback
- **`ForgeEventHandlers.java`** — trigger auto-generate on server start
- **`ExampleMod.java`** — adjust initialization timing
- **`Config.java`** — add `forceRegenerateConfig` option, keep `requireOtherPartsMatch` + `otherPartsThreshold`

## What Stays the Same
- `TinkerMaterialIngredient.test()` — still checks head material against config set, still does % total parts check
- `buildDisplayItems()` — still reads materials from config to build JEI display stacks
- `ClientEventHandlers.java` — tooltip system unchanged
- Config file format — same JSON structure (tier → material ID list)
- `requireOtherPartsMatch` + `otherPartsThreshold` config options

## What Changes
- No more hardcoded defaults in Java code
- Config auto-populated from `HeadMaterialStats.tier()` on first boot
- Correct tier assignments automatically (pig_iron→diamond, seared_stone→iron, etc.)
- New "netherite" tier auto-detected
- Works with addon mod materials automatically
- Same config usable for future armor recipe replacement

## Armor Note
- TC armor uses `PlatingMaterialStats` — NO tier field
- But plating materials almost always also have `HeadMaterialStats`
- So auto-generate from `HeadMaterialStats` covers armor materials too
- Armor-only materials (maille, suit) are statless/tier-agnostic — don't need tiering

## Open Item
- Verify that `MaterialRegistry` has a method to iterate all registered materials (like `getAllMaterials()` or similar) — confirmed via source but double-check the exact API when implementing
