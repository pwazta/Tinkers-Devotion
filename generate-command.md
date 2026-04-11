# `/nomorevanillatools generate` Command

Replaces vanilla tool/armor/ranged ingredients in crafting recipes with Tinker's Construct material-checked equivalents. Reloads exclusion configs, scans loaded crafting recipes, writes replacements, cleans stale files, and triggers a datapack reload — all in one command.

## Command Flow

```
/nomorevanillatools generate
|
+-- 1. Capture unmapped tool materials (diagnostic only)
|     TiersToTcMaterials.getUnmappedToolMaterials()
|       - Reads the list of materials dropped from the last rebuildToolCaches() pass
|         because their vanilla Tier isn't in TIER_NAME_MAP. NOT user exclusions.
|       - Tool tier state itself was built on MaterialsLoadedEvent, not here
|
+-- 2. Reload disk-backed configs
|     ToolExclusionConfig.reload()
|       - Re-reads tool_exclusions.json from disk
|     ModifierSkipListConfig.reload()
|       - Re-reads modifier_skip_list.json from disk
|
+-- 3. Collect existing generated files
|     DatapackHelper.listGeneratedRecipeFiles()
|       - Walks datapack/data/ for *_tinker_replacement.json files
|       - Used to detect stale recipes after regeneration
|
+-- 4. Generate replacement recipes
|     GenerateRecipesCommand.doGenerate()
|       a. Write pack.mcmeta
|       b. Disable vanilla tool/armor/ranged crafting (FalseCondition overrides)
|       c. Scan all CraftingRecipes in RecipeManager
|       d. Find ingredients matching vanilla tools/armor/ranged via VanillaItemMappings
|       e. Build replacement JSON with TinkerMaterialIngredient (ToolMode / ArmorMode / RangedMode)
|       f. Save to world/datapacks/nomorevanillatools_generated/
|       g. Track all written file paths
|
+-- 5. Clean stale recipes
|     Delete *_tinker_replacement.json files that weren't regenerated
|     (from uninstalled mods / removed recipes)
|
+-- 6. Send feedback
|     Chat messages with unmapped-material warnings, recipe counts, cleanup counts, errors
|
+-- 7. Reload datapacks (async)
      server.reloadResources() — recipes active immediately
```

## When to Run

| Scenario | Action |
|----------|--------|
| First world load | Runs automatically (`ServerAboutToStartEvent`) |
| Installed new mod with TC materials | Run `/nomorevanillatools generate` |
| Uninstalled a mod | Run `/nomorevanillatools generate` (cleans stale recipes) |
| Edited `tool_exclusions.json` | Run `/nomorevanillatools generate` |
| Edited `modifier_skip_list.json` | Run `/nomorevanillatools generate` |
| Changed forge config options | Run `/nomorevanillatools generate` |

No server restart needed. New TC materials are picked up automatically on the next `MaterialsLoadedEvent` (world load / `/reload`).

## Material Detection

Tool materials are resolved live via `TiersToTcMaterials`, which scans TC's `MaterialRegistry` for materials with `HeadMaterialStats` and maps their harvest tier to vanilla tiers on every `MaterialsLoadedEvent`:

| Vanilla Tier | Config Name | Example Materials |
|--------------|-------------|-------------------|
| minecraft:wood | wooden | tconstruct:wood |
| minecraft:stone | stone | tconstruct:rock, tconstruct:flint |
| minecraft:iron | iron | tconstruct:iron, tconstruct:copper |
| minecraft:gold | golden | tconstruct:rose_gold |
| minecraft:diamond | diamond | tconstruct:cobalt, tconstruct:slimesteel |
| minecraft:netherite | netherite | tconstruct:hepatizon, tconstruct:manyullyn |

Materials with modded tiers (not in the table above) are dropped from the tool tier pool and surfaced as warnings in the command output. To add support for a modded tier, expand `TIER_NAME_MAP` in `TiersToTcMaterials.java`.

Armor plating and ranged weapon tiers are resolved live via `IMaterial.getTier()` — no tier-name mapping required.

## Config Files

| File | Purpose |
|------|---------|
| `config/nomorevanillatools/tool_exclusions.json` | Per-action/slot/type tool blacklist (e.g., dagger excluded from sword recipes) |
| `config/nomorevanillatools/modifier_skip_list.json` | Modifiers excluded from enchantment→modifier conversion pool |

There is no `material_mappings.json` — tool tier state is derived live from the TC registry.

## Forge Config Options

| Option | Default | Effect on Generate |
|--------|---------|--------------------|
| `removeVanillaToolCrafting` | true | Disable vanilla tool crafting recipes |
| `removeVanillaArmorCrafting` | true | Disable vanilla armor crafting recipes |
| `removeVanillaRangedCrafting` | true | Disable vanilla bow/crossbow crafting recipes |

## Output Example

```
=== Recipe Generation Complete ===
  Warning: 2 tool materials have unmapped modded tiers (dropped from pool)
    - mythicmetals:adamantite (tier: mythicmetals:adamantite)
    - mythicmetals:carmot (tier: mythicmetals:carmot)
  Recipes: 47 replacement recipes generated
  Recipes: 25 vanilla tool recipes disabled
  Recipes: 16 vanilla armor recipes disabled
  Recipes: 2 vanilla ranged recipes disabled
  Cleanup: 2 stale recipes removed (from uninstalled mods)
Reloading datapacks...
Datapack reload complete! Recipes are now active.
```

## Generated Datapack Structure

```
world/datapacks/nomorevanillatools_generated/
  pack.mcmeta
  .generated                          (flag file)
  data/
    minecraft/recipes/
      wooden_sword.json               (FalseCondition — disables vanilla crafting)
      iron_pickaxe.json               (... 25 tool + 16 armor + 2 ranged total)
    <namespace>/recipes/
      <recipe>_tinker_replacement.json (replacement with TinkerMaterialIngredient)
```
