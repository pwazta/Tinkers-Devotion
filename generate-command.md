# `/nomorevanillatools generate` Command

Replaces vanilla tool ingredients in crafting recipes with Tinker's Construct material-checked equivalents. Detects new mods/materials, updates configs, cleans stale recipes, and reloads datapacks — all in one command.

## Command Flow

```
/nomorevanillatools generate
|
+-- 1. Detect & merge materials
|     MaterialMappingConfig.refreshFromRegistry()
|       a. Reload material_mappings.json from disk (picks up user edits)
|       b. Scan TC MaterialRegistry for all materials with HeadMaterialStats
|       c. If config was empty/corrupt: generate fresh from registry
|       d. Otherwise: merge new materials, preserve user tier overrides
|       e. Save updated config, return counts for feedback
|
+-- 2. Reload tool exclusions
|     ToolExclusionConfig.reload()
|       - Re-reads tool_exclusions.json from disk
|
+-- 3. Collect existing generated files
|     DatapackHelper.listGeneratedRecipeFiles()
|       - Walks datapack/data/ for *_tinker_replacement.json files
|       - Used to detect stale recipes after regeneration
|
+-- 4. Generate replacement recipes
|     GenerateRecipesCommand.doGenerate()
|       a. Write pack.mcmeta
|       b. Disable vanilla tool crafting (FalseCondition overrides)
|       c. Scan all CraftingRecipes in RecipeManager
|       d. Find ingredients matching vanilla tools (25 items: 5 tiers x 5 types)
|       e. Build replacement JSON with TinkerMaterialIngredient
|       f. Save to world/datapacks/nomorevanillatools_generated/
|       g. Track all written file paths
|
+-- 5. Clean stale recipes
|     Delete *_tinker_replacement.json files that weren't regenerated
|     (from uninstalled mods / removed recipes)
|
+-- 6. Send feedback
|     Chat messages with material counts, recipe counts, warnings, errors
|
+-- 7. Reload datapacks (async)
      server.reloadResources() — recipes active immediately
```

## When to Run

| Scenario | Action |
|----------|--------|
| First world load | Runs automatically (if `autoGenerateRecipes=true`) |
| Installed new mod with TC materials | Run `/nomorevanillatools generate` |
| Uninstalled a mod | Run `/nomorevanillatools generate` (cleans stale recipes) |
| Edited material_mappings.json | Run `/nomorevanillatools generate` |
| Edited tool_exclusions.json | Run `/nomorevanillatools generate` |
| Changed forge config options | Run `/nomorevanillatools generate` |

No server restart needed. No new world needed.

## Material Detection

The command scans TC's `MaterialRegistry` for materials with `HeadMaterialStats` and maps their harvest tier to vanilla tiers:

| Forge Tier | Config Name | Example Materials |
|------------|-------------|-------------------|
| minecraft:wood | wooden | tconstruct:wood |
| minecraft:stone | stone | tconstruct:rock, tconstruct:flint |
| minecraft:iron | iron | tconstruct:iron, tconstruct:copper |
| minecraft:gold | golden | tconstruct:rose_gold |
| minecraft:diamond | diamond | tconstruct:cobalt, tconstruct:slimesteel |
| minecraft:netherite | netherite | tconstruct:hepatizon, tconstruct:manyullyn |

Materials with modded tiers (not in the table above) are skipped with a warning in chat.

### User Overrides

If you manually move a material to a different tier in `material_mappings.json`, the merge will preserve your choice. The command never removes materials from your config — it only adds new ones.

## Config Files

| File | Purpose |
|------|---------|
| `config/nomorevanillatools/material_mappings.json` | Tier -> material ID mappings (auto-generated, user-editable) |
| `config/nomorevanillatools/tool_exclusions.json` | Per-action tool blacklist (e.g., dagger excluded from sword recipes) |

## Forge Config Options

| Option | Default | Effect on Generate |
|--------|---------|--------------------|
| `autoGenerateRecipes` | true | Auto-run on first world load |
| `removeVanillaToolCrafting` | true | Disable vanilla tool crafting recipes |
| `forceRegenerateMaterialConfig` | false | Force material merge on next boot (not needed with generate command) |

## Output Example

```
=== Recipe Generation Complete ===
  Materials: 3 new materials detected and added
  Materials: 1 kept in user-assigned tiers
  Warning: 2 materials have unsupported modded tiers (skipped)
    - mythicmetals:adamantite (tier: mythicmetals:adamantite)
  Recipes: 47 replacement recipes generated
  Recipes: 25 vanilla tool recipes disabled
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
      iron_pickaxe.json               (... 25 total)
    <namespace>/recipes/
      <recipe>_tinker_replacement.json (replacement with TinkerMaterialIngredient)
```
