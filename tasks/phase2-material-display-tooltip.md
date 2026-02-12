# Phase 2: Tier-Accurate Material Display + Tooltip

## What This Task Does

Two features for `TinkerMaterialIngredient` JEI display:

1. **Show correct head material** — e.g., an iron sword recipe shows a sword with an iron-colored head instead of generic TC render materials
2. **Tooltip on hover** — tells the player what tier is required, and optionally the % of other parts needed if that config is enabled

## Current State (Phase 1 Complete)

**File:** `src/main/java/com/pwazta/nomorevanillatools/recipe/TinkerMaterialIngredient.java`

Phase 1 implemented dynamic tool discovery — `getItems()` returns ALL TC tools matching the required ToolAction (e.g., sword recipes show sword + cleaver + dagger cycling in JEI).

### How it works now

```java
// Static cache keyed by ToolAction — scan runs once per action, shared across all instances
private static final Map<ToolAction, ItemStack[]> DISPLAY_CACHE = new HashMap<>();

@Override
public ItemStack[] getItems() {
    ToolAction action = getRequiredToolAction();
    if (action == null) return new ItemStack[0];
    return DISPLAY_CACHE.computeIfAbsent(action, TinkerMaterialIngredient::buildDisplayItems);
}

// Scans ForgeRegistries.ITEMS for IModifiableDisplay tools supporting the ToolAction
private static ItemStack[] buildDisplayItems(ToolAction requiredAction) {
    List<ItemStack> displayItems = new ArrayList<>();
    for (Item item : ForgeRegistries.ITEMS) {
        if (!(item instanceof IModifiableDisplay display)) continue;
        ToolDefinition definition = display.getToolDefinition();
        if (!definition.isDataLoaded()) continue;

        ItemStack renderStack = display.getRenderTool();
        boolean supportsAction = definition.getData()
            .getHook(ToolHooks.TOOL_ACTION)
            .canPerformAction(ToolStack.from(renderStack), requiredAction);
        if (supportsAction) {
            displayItems.add(renderStack.copy());
        }
    }
    return displayItems.toArray(new ItemStack[0]);
}
```

Key facts:
- `getRenderTool()` returns TC's generic render tool (fake `tconstruct:ui_render` materials — blue/teal visuals, no real material info)
- `.copy()` is applied to `getRenderTool()` — Phase 2 replaces this entirely with `createSingleMaterial()` which returns a fresh stack (no `.copy()` needed)
- `requiredTier` and `toolType` are final fields on the class (e.g., `"iron"`, `"sword"`)
- `getRequiredToolAction()` maps toolType string → Forge `ToolAction` constant

### What needs to change for Phase 2

0. **Prerequisite: `MaterialMappingConfig` → `LinkedHashSet`** — change `new HashSet<>(...)` to `new LinkedHashSet<>(...)` at lines 96 and 114 so material iteration order matches JSON config order (iron before pig_iron).
1. **`DISPLAY_CACHE` key must include tier** — currently keyed by `ToolAction` only, but Phase 2 display items differ per tier (iron sword ≠ golden sword). Change to composite key.
2. **`buildDisplayItems` needs access to `requiredTier`** — currently `static` and only takes `ToolAction`. Needs tier to look up materials.
3. **Replace `getRenderTool()` with `createSingleMaterial()`** — builds a tool with the correct head material + default other parts. No `.copy()` needed — `createSingleMaterial` returns a fresh `ItemStack`. Guard against null/empty tier config and `ItemStack.EMPTY` returns.
4. **Tag each display stack with `nmvt_required_tier`** — so a tooltip event handler can read it.
5. **Add client-only tooltip event handler** — new `ClientEventHandlers.java`, registered via `FMLClientSetupEvent`. Uses translatable `Component` keys for i18n support.
6. **Add lang file entries** — `en_us.json` with tooltip translation keys.

---

## Implementation Details

### 1. Material-Accurate Display

**API:** `ToolBuildHandler.createSingleMaterial(IModifiable item, MaterialVariant material)`

This TC utility:
- Uses the given material for all compatible slots (head accepts iron → iron head)
- Falls back to `MaterialRegistry.firstWithStatType()` for incompatible slots (handle might not accept iron → picks a default)
- Calls `rebuildStats()` internally — **fixes the existing P0 bug where display tools have no durability/speed/damage stats**
- Returns `ItemStack.EMPTY` if the material can't be used anywhere

**Material lookup:**
```java
// Get first configured material ID for the required tier
Set<String> materials = MaterialMappingConfig.getMaterialsForTier(requiredTier);
if (materials == null || materials.isEmpty()) {
    // Unknown or empty tier — skip material-accurate display, fall back to render tool
    continue; // (inside the ForgeRegistries.ITEMS loop)
}
String firstMaterialId = materials.iterator().next(); // e.g., "tconstruct:iron"
// NOTE: iteration order is deterministic because getMaterialsForTier() returns LinkedHashSet
// (see prerequisite below). First entry = primary material for the tier.

// Parse to MaterialVariantId, then wrap in MaterialVariant (lazy-loads IMaterial from registry)
MaterialVariantId variantId = MaterialVariantId.parse(firstMaterialId);
MaterialVariant variant = MaterialVariant.of(variantId);
```

> **Prerequisite — `MaterialMappingConfig` must use `LinkedHashSet`:**
> Currently `getMaterialsForTier()` returns a `HashSet`, which has **non-deterministic iteration order**. This means `iterator().next()` could return `pig_iron` instead of `iron` on some JVM runs. Change both `new HashSet<>(...)` calls in `MaterialMappingConfig.java` (lines 96 and 114) to `new LinkedHashSet<>(...)` so insertion order from the JSON config is preserved. This is a one-line change in two places — no other code is affected since the return type is `Set<String>`.

**After creating the display stack, check for EMPTY:**
```java
ItemStack displayStack = ToolBuildHandler.createSingleMaterial(display, variant);
if (displayStack.isEmpty()) continue; // material incompatible with this tool — skip
displayStack.getOrCreateTag().putString("nmvt_required_tier", requiredTier);
displayItems.add(displayStack);
```

> **Note:** `createSingleMaterial()` returns a freshly-built `ItemStack` via `ToolStack.createTool().createStack()`, so `.copy()` is unnecessary (unlike the current `getRenderTool()` path which shares a cached instance).

**Imports needed:**
```java
import slimeknights.tconstruct.library.tools.helper.ToolBuildHandler;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.materials.definition.MaterialVariant;
```

### 2. Cache Key Change

Current: `Map<ToolAction, ItemStack[]>`
New: needs `(ToolAction, tier)` composite key

Options:
- **Simple:** `Map<String, ItemStack[]>` with key `action.name() + ":" + tier` (string concat, trivial)
- **Record:** `record DisplayCacheKey(ToolAction action, String tier)` then `Map<DisplayCacheKey, ItemStack[]>` (type-safe)
- **Nested:** `Map<ToolAction, Map<String, ItemStack[]>>` (more complex, no benefit)

Recommendation: string key is simplest and this is a 5-entry cache max.

### 3. Tooltip

**When to show what:**

| Config state | Tooltip text |
|---|---|
| `requireOtherPartsMatch = false` (default) | `"Requires iron-tier head"` |
| `requireOtherPartsMatch = true`, threshold 0.5 | `"Requires iron-tier head + 50% other parts"` |
| `requireOtherPartsMatch = true`, threshold 1.0 | `"Requires iron-tier head + all other parts"` |

**Implementation:** Tag display stacks with NBT, register a client-side tooltip event handler.

In `buildDisplayItems`, after creating the display stack:
```java
displayStack.getOrCreateTag().putString("nmvt_required_tier", requiredTier);
```

Tooltip handler (new file `ClientEventHandlers.java`, registered client-side only):
```java
@SubscribeEvent
public static void onItemTooltip(ItemTooltipEvent event) {
    CompoundTag tag = event.getItemStack().getTag();
    if (tag == null || !tag.contains("nmvt_required_tier")) return;

    String tier = tag.getString("nmvt_required_tier");
    MutableComponent text = Component.translatable(
        "tooltip.nomorevanillatools.required_tier", tier);

    if (Config.requireOtherPartsMatch) {
        int pct = (int) (Config.otherPartsThreshold * 100);
        if (pct >= 100) {
            text.append(Component.translatable(
                "tooltip.nomorevanillatools.all_other_parts"));
        } else {
            text.append(Component.translatable(
                "tooltip.nomorevanillatools.pct_other_parts", pct));
        }
    }

    event.getToolTip().add(text.withStyle(ChatFormatting.GOLD));
}
```

**Lang file entries** (`src/main/resources/assets/nomorevanillatools/lang/en_us.json`):
```json
{
    "tooltip.nomorevanillatools.required_tier": "Requires %s-tier head",
    "tooltip.nomorevanillatools.all_other_parts": " + all other parts",
    "tooltip.nomorevanillatools.pct_other_parts": " + %s%% other parts"
}
```

**Where to put the handler:** Create a new `src/.../event/ClientEventHandlers.java`. Register it during `FMLClientSetupEvent` in `ExampleMod`:
```java
// In ExampleMod, inside the constructor or a client setup method:
MinecraftForge.EVENT_BUS.register(ClientEventHandlers.class);
// Wrap in DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...) or @Mod.EventBusSubscriber(Dist.CLIENT)
```
This is the idiomatic Forge pattern — keeps client-only handlers separate from server-side event handling. Do NOT put the tooltip handler in `ForgeEventHandlers.java` (which runs on both sides).

---

## Key Files

| File | Role |
|---|---|
| `src/.../recipe/TinkerMaterialIngredient.java` | Main file to change — `buildDisplayItems`, cache key, NBT tagging |
| `src/.../event/ClientEventHandlers.java` | **New file** — client-only tooltip event handler |
| `src/.../Config.java` | Read `requireOtherPartsMatch` (boolean) and `otherPartsThreshold` (double 0-1) |
| `src/.../config/MaterialMappingConfig.java` | `getMaterialsForTier(String tier)` → `Set<String>` — **change `HashSet` to `LinkedHashSet`** (2 lines) |
| `src/.../ExampleMod.java` | Register `ClientEventHandlers` on client dist |
| `src/main/resources/assets/nomorevanillatools/lang/en_us.json` | Translatable tooltip strings |

## Reference APIs (in `../refs/TinkersConstruct/`)

| Class | Method | What it does |
|---|---|---|
| `ToolBuildHandler` | `createSingleMaterial(IModifiable, MaterialVariant)` | Builds tool with given material for head, defaults for rest |
| `MaterialVariantId` | `parse(String)` | Parses `"tconstruct:iron"` → `MaterialVariantId` (throws `JsonSyntaxException` if invalid) |
| `MaterialVariant` | `of(MaterialVariantId)` | Wraps a `MaterialVariantId` in a lazy-loading `MaterialVariant` (required by `createSingleMaterial`) |
| `MaterialRegistry` | `firstWithStatType(MaterialStatsId)` | Gets a default material for a stat type (used internally by `createSingleMaterial`) |

## Design Decisions (already agreed)

- **Don't render multiple material combos** — pick first material from config per tier (e.g., `tconstruct:iron` not `tconstruct:pig_iron`)
- **Don't show wood for non-head parts** — `createSingleMaterial` picks neutral defaults via `firstWithStatType()`
- **Don't try to visualize `requireOtherPartsMatch` via materials** — tooltip is the right medium
- **Tooltip reflects live config** — reads `Config.requireOtherPartsMatch` and `Config.otherPartsThreshold` at display time, so it's always current

## Side Effect: Fixes P0 Bug

CONTEXT.md lists a P0: "Display tools missing `rebuildStats()` — no durability/speed/damage in JEI tooltips." Switching from `getRenderTool()` to `ToolBuildHandler.createSingleMaterial()` fixes this.

**Verified call chain:** `createSingleMaterial()` → `buildItemFromMaterials()` → `ToolStack.createTool()` → `setMaterials()` → `rebuildStats()`. The `setMaterials()` method in `ToolStack.java` always calls `rebuildStats()` after writing the material NBT, so any tool created through `createSingleMaterial` will have fully computed stats (durability, mining speed, attack damage, harvest tier).

## Verification

1. Build succeeds (`gradlew build`)
2. In-game: iron sword recipe in JEI shows sword with iron-colored head (not generic blue)
3. Hover tooltip shows `"Requires iron-tier head"` (golden text)
4. Enable `requireOtherPartsMatch` in config, set threshold to 0.5 — tooltip shows `"Requires iron-tier head + 50% other parts"`
5. Golden pickaxe recipe shows gold-colored pickaxe head
6. All tool types still cycle correctly (sword/cleaver/dagger etc.)
7. Display tools now show durability/mining speed/attack damage in tooltips (P0 fix)
8. Iron tier consistently shows iron-colored head (not pig_iron) — verify `LinkedHashSet` ordering
9. No blank JEI slots — `ItemStack.EMPTY` from `createSingleMaterial` is filtered out
10. Dedicated server starts without errors (client handler not loaded server-side)
