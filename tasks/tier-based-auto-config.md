# Tier-Based Material Auto-Generation Refactor

## Goal
Replace hardcoded material-to-tier mappings with auto-generation from TC's `HeadMaterialStats.tier()`. Unified config used for both tool AND armor recipe replacement. Keep the % total-parts threshold feature.

## Current System
- `MaterialMappingConfig.java` — reads/writes `config/nomorevanillatools/material_mappings.json`
- Hardcoded defaults in `createDefaultConfig()` (lines 61-93): wooden→[wood,bamboo], stone→[rock,flint,basalt], iron→[iron,pig_iron], golden→[gold,rose_gold], diamond→[diamond]
- **Current defaults have errors**: pig_iron is DIAMOND tier (not iron), bamboo has NO HeadMaterialStats (not a tool material), copper is missing (IRON tier), gold has no HeadMaterialStats (armor-only)
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
IMaterialRegistry registry = MaterialRegistry.getInstance();

// getVisibleMaterials() excludes:
//   - Redirected materials (bloodbone→venombone, chain→roseGold, etc.) — never enter collection
//   - Hidden materials (e.g. ancient) — filtered by !isHidden()
// This is the correct method to use.
for (IMaterial material : registry.getVisibleMaterials()) {
    MaterialId materialId = material.getIdentifier();
    Optional<HeadMaterialStats> stats = registry.getMaterialStats(materialId, HeadMaterialStats.ID);
    if (stats.isPresent()) {
        Tier tier = stats.get().tier();
        // Map tier to config name and add materialId to that tier's set
    }
}
```

## Tier Name Mapping

Use `TierSortingRegistry.getName(tier)` to get the `ResourceLocation`, then map to config names.
Modded tiers (not in the table below) are **skipped with a warning log**.

```java
private static final Map<ResourceLocation, String> TIER_NAME_MAP = Map.of(
    new ResourceLocation("wood"),      "wooden",
    new ResourceLocation("stone"),     "stone",
    new ResourceLocation("iron"),      "iron",
    new ResourceLocation("gold"),      "golden",
    new ResourceLocation("diamond"),   "diamond",
    new ResourceLocation("netherite"), "netherite"
);

// Usage:
ResourceLocation tierId = TierSortingRegistry.getName(tier);
String configName = TIER_NAME_MAP.get(tierId);
if (configName == null) {
    LOGGER.warn("Unknown tier '{}' for material '{}', skipping", tierId, materialId);
    continue;
}
```

| TierSortingRegistry Name | Config Tier Name |
|---|---|
| `minecraft:wood` | `"wooden"` |
| `minecraft:stone` | `"stone"` |
| `minecraft:iron` | `"iron"` |
| `minecraft:gold` | `"golden"` |
| `minecraft:diamond` | `"diamond"` |
| `minecraft:netherite` | `"netherite"` |

## Expected Material Counts (base TC, no addons)

| Tier | ~Count | Key Materials |
|---|---|---|
| wooden | ~2 | wood (bamboo has NO HeadMaterialStats — ranged/shield only) |
| stone | ~5 | rock, flint, bone, chorus, treatedWood |
| iron | ~14 | iron, copper, searedStone, venombone, slimewood, scorchedStone, necroticBone, whitestone, blazingBone, electrum, osmium, silver, lead, ironwood |
| golden | ~1 | rose_gold (only tool-capable GOLD-tier material; gold itself is armor-only) |
| diamond | ~14 | slimesteel, amethystBronze, nahuatl, pigIron, cobalt, steel, bronze, constantan, invar, pewter, necronium, platedSlimewood, steeleaf... |
| netherite | ~9 | queensSlime, cinderslime, hepatizon, manyullyn, knightmetal, knightslime, fiery, nicrosil, ancient... |

Total: ~45 materials with HeadMaterialStats. Addon mods increase this.

## Key Constraint: Timing
- `MaterialRegistry` data loads from datapacks during world/server load (synchronous reload listeners)
- Auto-generate **CANNOT** happen in `commonSetup()` — registry is empty at that point
- Use `ServerAboutToStartEvent` (datapacks are loaded by then) with `MaterialRegistry.isFullyLoaded()` guard
- TC also fires `MaterialsLoadedEvent` when all material data is ready — could subscribe to this as an alternative
- Need fallback for JEI display items requested before config exists

```java
// Defensive guard in generateFromRegistry():
if (!MaterialRegistry.isFullyLoaded()) {
    LOGGER.warn("MaterialRegistry not loaded yet, falling back to existing config or hardcoded defaults");
    return;
}
```

## Key Constraint: Config Persistence
- First boot with no config → auto-generate from registry → write config
- Subsequent boots with existing config → load config, do NOT overwrite (manual edits preserved)
- `forceRegenerateConfig`: uses **merge strategy** — auto-detected materials are unioned with existing entries, user additions are never removed

### Merge Strategy for Force-Regenerate
```
1. Scan registry → build fresh tier map (auto-detected)
2. Load existing config (user-edited)
3. For each tier: merged = union(auto-detected, existing user entries)
4. Write merged result back to config
5. Log: "Added X new materials, preserved Y user entries"
```
This ensures addon mod materials get picked up on regenerate without destroying manual additions.

## Display Items: Future Consideration

**Note:** With auto-gen, tiers like iron/diamond could have ~14 materials × ~5 tool types = ~70 display stacks cycling in JEI. Leave `buildDisplayItems()` as-is for now — evaluate after implementation whether display capping or a separate `displayMaterials` config is needed.

## Files to Modify
- **`MaterialMappingConfig.java`** — replace `createDefaultConfig()` with `generateFromRegistry()`, add `isFullyLoaded()` guard, add merge logic for regeneration, add `TIER_NAME_MAP`
- **`TinkerMaterialIngredient.java`** — `test()` unchanged; `buildDisplayItems()` unchanged (may revisit display cap later)
- **`ForgeEventHandlers.java`** — trigger auto-generate on server start (existing event, add registry guard)
- **`ExampleMod.java`** — adjust initialization timing if needed
- **`Config.java`** — add `forceRegenerateConfig` option, keep `requireOtherPartsMatch` + `otherPartsThreshold`

## What Stays the Same
- `TinkerMaterialIngredient.test()` — still checks head material against config set, still does % total parts check
- `ClientEventHandlers.java` — tooltip system unchanged
- Config file format — same JSON structure (tier → material ID list)
- `requireOtherPartsMatch` + `otherPartsThreshold` config options

## What Changes
- No more hardcoded defaults in Java code
- Config auto-populated from `HeadMaterialStats.tier()` on first boot
- Correct tier assignments automatically (pig_iron→diamond, copper→iron, etc.)
- Bamboo correctly excluded (no HeadMaterialStats)
- New "netherite" tier auto-detected
- Works with addon mod materials automatically
- Force-regenerate merges rather than overwrites
- Same config usable for future armor recipe replacement

## Armor Note
- TC armor uses `PlatingMaterialStats` — NO tier field
- Most plating materials also have `HeadMaterialStats`, so auto-generation covers them
- **Known gap:** `gold` and `obsidian` have PlatingMaterialStats but NO HeadMaterialStats — they are armor-only materials that cannot be used as tool heads. Auto-generation from HeadMaterialStats will not include them.
- For future armor recipe support: these two materials would need manual addition to the config (or a supplemental scan of PlatingMaterialStats with a separate tier inference)
- Maille/cuirass materials (skyslimeVine, slimeskin, etc.) are statless bindings — don't need tiering

## Verified Assumptions
- [x] `MaterialRegistry.getInstance().getVisibleMaterials()` — iterates all non-hidden, non-redirect materials
- [x] Redirected materials (bloodbone→venombone, chain→roseGold, etc.) never enter the materials collection — safe
- [x] Hidden materials (e.g. ancient) filtered by `!isHidden()` in `getVisibleMaterials()` — safe
- [x] `MaterialRegistry.isFullyLoaded()` — exists, checks all 3 managers loaded (materials, stats, traits)
- [x] `HeadMaterialStats.tier()` returns `net.minecraft.world.item.Tier` interface
- [x] `TierSortingRegistry.getName(tier)` returns `ResourceLocation` for tier name mapping
- [x] gold, obsidian — confirmed NO HeadMaterialStats (armor-only plating)
- [x] bamboo — confirmed NO HeadMaterialStats (ranged/shield only)
- [x] rose_gold — confirmed HeadMaterialStats with GOLD harvest tier (only gold-tier tool material)
- [x] pig_iron — confirmed HeadMaterialStats with DIAMOND harvest tier (not iron)
