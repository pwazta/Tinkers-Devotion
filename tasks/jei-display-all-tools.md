# JEI Display: Show All Matching TC Tools Per Recipe Slot

## Problem

`TinkerMaterialIngredient.buildDisplayItems()` returns only ONE TC tool per vanilla type (e.g. "sword" only shows `tconstruct:sword`). But `test()` accepts ANY TC tool with the matching ToolAction — so sword recipes also accept cleaver and dagger. Players have no way to know this from JEI.

## Goal

`getItems()` should return ALL TC tools that satisfy this ingredient, so JEI cycles through them as alternatives (like vanilla does with tag ingredients).

## Current Code (what to change)

**File:** `src/main/java/com/pwazta/nomorevanillatools/recipe/TinkerMaterialIngredient.java`

### Current `buildDisplayItems()` (lines ~148-170)
```java
private ItemStack[] buildDisplayItems() {
    String tcToolName = mapToolTypeToTC(toolType);  // returns ONE tool name
    // ... looks up that one tool, calls getRenderTool()
    return new ItemStack[]{ display.getRenderTool() };
}
```

### Current `mapToolTypeToTC()` (lines ~180-200)
```java
private String mapToolTypeToTC(String vanillaType) {
    return switch (vanillaType.toLowerCase()) {
        case "pickaxe" -> "pickaxe";
        case "axe" -> "hand_axe";
        case "sword" -> "sword";
        case "shovel" -> "mattock";
        case "hoe" -> "kama";
        default -> null;
    };
}
```

## Implementation

### Approach: Iterate ForgeRegistries.ITEMS, filter by IModifiable + ToolAction

Replace `buildDisplayItems()` to find ALL matching tools dynamically:

```java
private ItemStack[] buildDisplayItems() {
    ToolAction requiredAction = getRequiredToolAction();
    if (requiredAction == null) {
        return new ItemStack[0];
    }

    List<ItemStack> displayItems = new ArrayList<>();
    for (Item item : ForgeRegistries.ITEMS) {
        if (item instanceof IModifiableDisplay display) {
            // Check if this tool supports the required action
            if (item instanceof IModifiable modifiable) {
                ToolDefinition definition = modifiable.getToolDefinition();
                if (definition.isDataLoaded()) {
                    // Need a dummy ToolStack to query the hook — getRenderTool() gives us one
                    ItemStack renderStack = display.getRenderTool();
                    boolean matches = definition.getData().getHook(ToolHooks.TOOL_ACTION)
                        .canPerformAction(ToolStack.from(renderStack), requiredAction);
                    if (matches) {
                        displayItems.add(renderStack.copy());
                    }
                }
            }
        }
    }
    return displayItems.toArray(new ItemStack[0]);
}
```

### Why this works
- `getRequiredToolAction()` already exists and maps toolType → ToolAction (same logic as `matchesToolType`)
- `IModifiableDisplay.getRenderTool()` returns a pre-built tool with default render materials (always available, no config dependency)
- The ToolAction check on the render stack mirrors exactly what `matchesToolType()` does at runtime
- JEI automatically cycles through multiple items in `getItems()` — no JEI-side changes needed

### Why `.copy()` the render stacks
- `getRenderTool()` returns a **cached singleton** inside TC's `ModifiableItem` — shared across all callers
- Without `.copy()`, our `cachedDisplayItems` holds references to TC's internal cache
- Phase 2 (tier-accurate materials, tooltip NBT tags) needs to mutate these stacks — mutating a shared instance would corrupt TC's render tools globally
- `.copy()` makes each display item independently safe to modify now or later

### What to remove
- `mapToolTypeToTC()` becomes unused — delete it entirely

### Imports to add
```java
import java.util.ArrayList;
import java.util.List;
```

### Edge case: `toolType` is null/empty or unknown
- If `getRequiredToolAction()` returns null, return empty array (same as current behavior for unknown types)

## Performance

- `buildDisplayItems()` is called once per ingredient instance, result is cached in `cachedDisplayItems`
- Registry iteration is ~700 items, filtered to ~15-20 IModifiable items — trivial
- Only runs during recipe load/JEI init, never during crafting hot path

## Follow-up: Phase 2 — Tier-Accurate Materials + Tooltip (separate task)

**Not in scope for this task**, but the implementation above is designed to make Phase 2 a minimal diff.

### What Phase 2 would do
1. **Show correct head material** — e.g., iron-headed sword for an "iron sword" recipe instead of generic render materials
2. **Add tooltip** — hover text like `"Requires iron-tier head material"` so the tier requirement is explicit

### Why Phase 2 is easy after this task
- Display stacks are already `.copy()`'d — safe to mutate with new materials or NBT tags
- `requiredTier` is already a field on the class, accessible in `buildDisplayItems()`
- `MaterialMappingConfig.getMaterialsForTier(requiredTier)` already exists for looking up tier → material IDs
- Tool discovery loop (ToolAction scan) stays identical — only the display stack creation changes

### Phase 2 implementation sketch

**Material-accurate display** — swap `renderStack.copy()` for `createSingleMaterial()`:
```java
// Inside the matches block, replace renderStack.copy() with:
MaterialVariantId headMaterial = MaterialVariantId.parse(
    MaterialMappingConfig.getMaterialsForTier(requiredTier).iterator().next());
ItemStack displayStack = ToolBuildHandler.createSingleMaterial(modifiable, headMaterial);
if (!displayStack.isEmpty()) {
    displayItems.add(displayStack);
}
```
- `createSingleMaterial` uses the given material for head, auto-fills other parts with `MaterialRegistry.firstWithStatType()` defaults
- Picks the first configured material per tier (e.g., `tconstruct:iron` for iron tier)
- Non-head parts show as defaults — visually honest since default config only enforces head matching
- Import: `slimeknights.tconstruct.library.tools.helper.ToolBuildHandler`, `slimeknights.tconstruct.library.materials.definition.MaterialVariantId`

**Tooltip** — tag display stacks + add event handler:
```java
// In buildDisplayItems(), after creating displayStack:
displayStack.getOrCreateTag().putString("nmvt_required_tier", requiredTier);

// Separate event handler (e.g., in ForgeEventHandlers or a new client event class):
@SubscribeEvent
public static void onItemTooltip(ItemTooltipEvent event) {
    CompoundTag tag = event.getItemStack().getTag();
    if (tag != null && tag.contains("nmvt_required_tier")) {
        String tier = tag.getString("nmvt_required_tier");
        event.getToolTip().add(Component.literal("Requires " + tier + "-tier head material")
            .withStyle(ChatFormatting.GOLD));
    }
}
```

### Design decisions for Phase 2
- **Don't render multiple material combos** — combinatorial explosion (iron+pig_iron × N parts = too many stacks)
- **Don't show wood for non-head parts** — implies "you need wood" when any material works; the `firstWithStatType()` default is more neutral
- **Don't try to visualize `requireOtherPartsMatch`** — tooltip is the right medium for config-dependent info, not material colors
- **When `requireOtherPartsMatch` is ON**: consider updating tooltip text to `"Requires iron-tier head + 50% other parts"` instead of changing materials

## Verification
1. Build succeeds (`gradlew build`)
2. In-game: open JEI, look at an iron sword replacement recipe — should show sword, cleaver, and dagger cycling
3. Check pickaxe recipe shows: pickaxe, sledge_hammer, vein_hammer, pickadze
4. Check shovel recipe shows: mattock, excavator, pickadze
5. Check hoe recipe shows: kama, scythe, dagger
6. Check axe recipe shows: hand_axe, broad_axe, mattock
