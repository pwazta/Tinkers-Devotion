# Ranged Weapon Replacement Design

> Extend the existing loot and mob equipment replacement system to handle bows and crossbows.
> Two phases: Phase 1 (loot replacement) is a straightforward extension. Phase 2 (mob AI) requires custom goal classes.

## Revisions (auto-updated by /buildthis)
- 2026-02-16: Both TcBowAttackGoal and TcCrossbowAttackGoal must call `GeneralInteractionModifierHook.startDrawtime(toolStack, mob, 1.0f)` when the mob starts drawing/charging — mobs bypass `Item.use()`, so the `tconstruct:drawtime` persistent data is never initialized otherwise. `getToolCharge()` (bow) would divide by zero and the crossbow charge duration check would read 0. Discovered via bytecode analysis of TC's `startDrawtime()` call chain.
- 2026-02-16: `TcCrossbowAttackGoal.stop()` must capture `getUseItem()` BEFORE calling `stopUsingItem()` — otherwise the use item is cleared and `clearChargedState()` receives `ItemStack.EMPTY`, failing to clean up TC crossbow ammo data.

---

## Background

The existing system replaces vanilla tools/armor with randomized TC equivalents in two contexts:
- **Loot**: `VanillaLootReplacer` (GLM) intercepts all loot generation, calls `TinkerToolBuilder.tryReplace()`
- **Mob equipment**: `MobEquipmentReplacer` (EntityJoinLevelEvent) iterates equipment slots, calls `TinkerToolBuilder.tryReplace()`

Both share `TinkerToolBuilder` which identifies vanilla items via `VanillaItemMappings` (O(1) HashMap), selects a random eligible TC equivalent, and builds it with weighted material selection.

Currently handles: swords, pickaxes, axes, shovels, hoes (via ToolAction scanning) + helmets, chestplates, leggings, boots (via ArmorItem.Type scanning). Does NOT handle bows, crossbows, or any ranged weapons.

### Why Ranged is Different

1. **No ToolAction**: TC's longbow and crossbow declare NO ToolActions. Forge has no `BOW_DRAW` or `CROSSBOW_LOAD`. TC ranged weapons are identified by item class (`ModifiableBowItem`, `ModifiableCrossbowItem`), not by ToolAction hooks. Verified against `ToolDefinitionDataProvider.java` (no `ToolActionsModule` on longbow/crossbow definitions) and `ToolHooks.TOOL_ACTION` default (returns false when no module present).

2. **No vanilla tiers**: Unlike `iron_sword` / `diamond_pickaxe`, vanilla has just one `minecraft:bow` and one `minecraft:crossbow` with no tier variants. Material selection needs per-part tier defaults instead of a single weapon tier.

3. **Different stat types**: TC ranged weapons use Limb/Grip/Bowstring stat types instead of Head/Handle/Binding. The existing `getCompatibleMaterials(statType)` filtering handles this automatically, but primary material selection needs stat type compatibility verification.

4. **Mob AI incompatibility (Phase 2 only)**: Vanilla `RangedBowAttackGoal.canUse()` checks `instanceof BowItem`. TC's `ModifiableBowItem` does NOT extend `BowItem`. Melee doesn't have this problem — `MeleeAttackGoal` calls `mob.doHurtTarget()` which works with any item class.

### TC Ranged Weapons Available (1.20.1)

| TC Item | Registry Name | Class | Vanilla Equivalent | Parts |
|---------|--------------|-------|--------------------|-------|
| Longbow | `tconstruct:longbow` | `ModifiableBowItem` | `minecraft:bow` | 4 (limb, limb, grip, bowstring) |
| Crossbow | `tconstruct:crossbow` | `ModifiableCrossbowItem` | `minecraft:crossbow` | 3 (limb, grip, bowstring) |

Not in scope: fishing rod (different use case), javelin/shuriken/throwing axe (no vanilla equivalent).

---

## Phase 1: Loot Replacement (Chests, Fishing, Bartering) — COMPLETED

### 1.1 VanillaItemMappings Changes

**File**: `src/main/java/com/pwazta/nomorevanillatools/loot/VanillaItemMappings.java`

**New record type** alongside existing `ToolInfo` (line 24) and `ArmorInfo` (line 25):

```java
public record RangedInfo(String rangedType, List<String> partTiers) {}
```

No single `tier` field — replaced by `partTiers` list (one tier per part slot, immutable via `List.of()`). `rangedType` is `"bow"` or `"crossbow"`.

**New static map** parallel to `TOOLS_BY_ID` / `ARMOR_BY_ID`:

```java
private static final Map<String, RangedInfo> RANGED_BY_ID = new HashMap<>();

static {
    // ... existing tool/armor init ...

    // Longbow: 4 parts — limb, limb, grip, bowstring
    RANGED_BY_ID.put("minecraft:bow", new RangedInfo("bow",
        List.of("wooden", "wooden", "wooden", "wooden")));
    // Crossbow: 3 parts — limb, grip, bowstring
    RANGED_BY_ID.put("minecraft:crossbow", new RangedInfo("crossbow",
        List.of("wooden", "iron", "wooden")));
}
```

**New lazy item-keyed map** — same double-checked locking pattern as `toolsByItem` / `armorByItem`:

```java
private static volatile Map<Item, RangedInfo> rangedByItem;
```

Populated in `ensureItemMaps()` alongside existing maps.

**New public accessors**:
- `getRangedInfo(Item)` — for loot/mob replacement (O(1) item-keyed lookup)
- `getRangedInfoById(String)` — for recipe generation if needed later

**Ranged class constant** for eligible item scanning:

```java
// TODO: Consider ModifiableLauncherItem as catch-all if TC adds more ranged weapon types.
// Currently using specific subclasses for precision. ModifiableLauncherItem is the common
// parent of ModifiableBowItem and ModifiableCrossbowItem — could generalize in future.
```

### 1.2 TinkerToolBuilder Refactor + Ranged Builder

**File**: `src/main/java/com/pwazta/nomorevanillatools/loot/TinkerToolBuilder.java`

#### Refactor: Split `buildWithMaterials()` into Select + Build

Current `buildWithMaterials()` (lines 134-189) handles both material selection AND tool construction, with an `isArmor` boolean flag driving internal branching. Adding ranged would require a third flag — code smell.

**Extract material selection into per-type methods:**

```java
// Each type produces materials independently:
buildRandomTool()   -> selectToolMaterials(tier, statTypes, random)   -> buildFromMaterials()
buildRandomArmor()  -> selectArmorMaterials(tier, statTypes, random)  -> buildFromMaterials()
buildRandomRanged() -> selectRangedMaterials(partTiers, statTypes, random) -> buildFromMaterials()
```

**`buildFromMaterials()`** — shared core extracted from lines 170-188:

```java
private static @Nullable ItemStack buildFromMaterials(
        IModifiable modifiable, List<MaterialVariantId> materials, ItemStack original) {
    ToolDefinition definition = modifiable.getToolDefinition();
    MaterialNBT.Builder builder = MaterialNBT.builder();
    materials.forEach(builder::add);
    ToolStack toolStack = ToolStack.createTool((Item) modifiable, definition, builder.build());
    toolStack.rebuildStats();
    ItemStack result = toolStack.createStack();
    transferDamage(original, result);
    return result;
}
```

Takes `IModifiable`, pre-selected `List<MaterialVariantId>`, and original `ItemStack`. Does: createTool -> rebuildStats -> transferDamage. No material selection logic. No boolean flags.

**`selectToolMaterials()`** — extracted from existing lines 150-164 (tool branch).

**`selectArmorMaterials()`** — extracted from existing lines 150-164 (armor branch).

**`selectRangedMaterials()`** — new, see Section 1.3.

#### New `buildRandomRanged()` method

Parallel to `buildRandomTool()` (lines 104-115) and `buildRandomArmor()` (lines 119-130):

```java
private static @Nullable ItemStack buildRandomRanged(
        String rangedType, String[] partTiers, ItemStack original, RandomSource random) {
    List<Item> eligible = getEligibleRanged(rangedType);
    if (eligible.isEmpty()) return null;

    Item selected = eligible.get(random.nextInt(eligible.size()));
    if (!(selected instanceof IModifiable modifiable)) return null;

    ToolDefinition definition = modifiable.getToolDefinition();
    if (!definition.isDataLoaded()) return null;

    List<MaterialStatsId> statTypes = ToolMaterialHook.stats(definition);
    List<MaterialVariantId> materials = selectRangedMaterials(partTiers, statTypes, random);
    if (materials == null) return null;

    return buildFromMaterials(modifiable, materials, original);
}
```

#### New `getEligibleRanged()` method

Parallel to `getEligibleTools()` (lines 299-322). Uses `instanceof` class checking instead of ToolAction:

```java
private static List<Item> getEligibleRanged(String rangedType) {
    return ELIGIBLE_ITEM_CACHE.computeIfAbsent(rangedType, k -> {
        Class<?> targetClass = switch (rangedType) {
            case "bow" -> ModifiableBowItem.class;
            case "crossbow" -> ModifiableCrossbowItem.class;
            // TODO: Consider ModifiableLauncherItem as catch-all if TC adds more ranged types
            default -> null;
        };
        if (targetClass == null) return List.of();

        List<Item> weapons = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS) {
            if (!targetClass.isInstance(item)) continue;
            if (!(item instanceof IModifiable)) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id != null && ToolExclusionConfig.isExcluded(rangedType, id.toString())) continue;
            weapons.add(item);
        }
        return weapons;
    });
}
```

Reuses `ELIGIBLE_ITEM_CACHE` (ConcurrentHashMap) — keyed by `"bow"` / `"crossbow"` alongside existing `"sword"`, `"pickaxe"`, etc. Cleared by existing `clearCaches()`.

#### `tryReplace()` third branch

After existing armor check (line 97), add:

```java
VanillaItemMappings.RangedInfo rangedInfo = VanillaItemMappings.getRangedInfo(item);
if (rangedInfo != null)
    return buildRandomRanged(rangedInfo.rangedType(), rangedInfo.partTiers(), original, random);
```

### 1.3 Material Selection for Ranged

**`selectRangedMaterials()`** — per-part tier selection with stat type compatibility:

```java
private static @Nullable List<MaterialVariantId> selectRangedMaterials(
        List<String> partTiers, List<MaterialStatsId> statTypes, RandomSource random) {
    List<MaterialVariantId> materials = new ArrayList<>();
    for (int i = 0; i < statTypes.size(); i++) {
        String tierName = i < partTiers.size() ? partTiers.get(i) : partTiers.get(partTiers.size() - 1);
        MaterialVariantId material = selectPartByTier(tierName, statTypes.get(i), random);
        if (material == null) return null;
        materials.add(material);
    }
    return materials;
}
```

Implementation note: Instead of a strict length mismatch error, uses a fallback to the last tier if `partTiers` is shorter than `statTypes`. This is more robust against TC tool definitions with unexpected part counts.

**`selectPartByTier()`** — stat-type-first selection, bypassing HeadMaterialStats tier pools:

Ranged parts (limb, grip, bowstring) use different stat types than tools (head, handle, binding). The tool material tier pools from `MaterialMappingConfig` are keyed by `HeadMaterialStats.tier()` — irrelevant for ranged stat types. Using them would produce frequent empty intersections and misleading "canonical" picks.

Instead, query materials that actually have the required stat type, then filter by TC internal tier:

1. Map tier name to TC tier int: `wooden=0, stone=1, iron=2, golden=1, diamond=3, netherite=4`
2. `getCompatibleMaterials(statType)` — all materials with this stat type (already cached in `MATERIAL_CACHE`, ConcurrentHashMap keyed by `MaterialStatsId` — registry scan happens once per stat type, O(1) lookup after)
3. Filter to `IMaterial.getTier() <= maxTcTier` → **tier-filtered pool**
4. Fallback: if tier filtering leaves nothing, tier-filtered pool = full compatible pool
5. 85/15 weighted selection (both from tier-filtered pool):
   - **85% canonical pick**:
     - Try `MaterialMappingConfig.getCanonicalToolMaterial(tierName)` — if it's in the tier-filtered pool, use it (e.g., iron grip → `tconstruct:iron`)
     - If canonical doesn't have this stat type, use the lowest-tier material from the tier-filtered pool (e.g., bowstring → string, since `tconstruct:wood` has no bowstring stats)
   - **15% uniform random from the tier-filtered pool**

Result for crossbow (`["wooden", "iron", "wooden"]`): 85% produces wood limb + iron grip + string bowstring. 15% picks randomly from tier-appropriate materials per slot.

### 1.4 Config, Exclusions, Integration

**Config toggles** — no new toggle needed. Existing `Config.replaceLootTableItems` already guards the GLM pipeline. Ranged flows through the same `VanillaLootReplacer.doApply()` -> `tryReplace()` path.

**ToolExclusionConfig** — add `"bow"` and `"crossbow"` keys in `createDefaults()` with `DEFAULT_EXCLUDED_RANGED` containing `tconstruct:war_pick`. The war_pick is a `ModifiableCrossbowItem` (crossbow/pickaxe hybrid) but an uncraftable ancient loot-only tool — it must be excluded from crossbow replacement. The exclusion also applies to bow defensively.

**Cache invalidation** — no changes. `ELIGIBLE_ITEM_CACHE` (renamed from `TOOL_CACHE`) already cleared by all existing reload paths. Ranged entries keyed by `"bow"` / `"crossbow"` cleared alongside `"sword"` / `"pickaxe"` etc.

**VanillaLootReplacer.java** — zero changes. Calls `tryReplace()` which now has the third branch. Transparent.

**MobEquipmentReplacer.java** — zero changes for Phase 1. The equipment swap works immediately. Phase 2 adds AI goal handling.

**Recipe generation** — out of scope. Vanilla bow/crossbow crafting recipes are not being replaced with TC ingredient recipes.

### Phase 1 Summary

| File | Change |
|------|--------|
| `VanillaItemMappings.java` | New `RangedInfo` record, `RANGED_BY_ID` map, `rangedByItem` lazy map, accessors |
| `TinkerToolBuilder.java` | Refactor `buildWithMaterials()` into select/build split. New `buildRandomRanged()`, `getEligibleRanged()`, `selectRangedMaterials()`, `selectPartByTier()`. Third `tryReplace()` branch. |
| `ToolExclusionConfig.java` | Add `"bow"` and `"crossbow"` default keys |
| `CONTEXT.md` | Update codebase structure (RangedInfo in VanillaItemMappings), app flow (tryReplace third branch, selectPartByTier), and notes (ranged weapon scope) |

No new files. All changes to existing files. Zero changes to VanillaLootReplacer or MobEquipmentReplacer.

### Post-Implementation Refinements (applied during review)

| Change | Rationale |
|--------|-----------|
| `RangedInfo.partTiers`: `String[]` → `List<String>` | Records + arrays is a Java pitfall (broken equals/hashCode). `List.of()` is immutable and equals-safe. |
| `TOOL_CACHE` → `ELIGIBLE_ITEM_CACHE` | Cache now stores both tools and ranged weapons. Name reflects actual scope. |
| `HEAD_CANONICAL_WEIGHT` + `RANGED_CANONICAL_WEIGHT` → `CANONICAL_WEIGHT` | Both were 0.85f with identical semantics. Single shared constant. |
| Removed inner try/catch from `buildFromMaterials()` | Outer try/catch in each `buildRandom*` method provides better category-specific logging. Inner catch was dead weight. |
| `DEFAULT_EXCLUDED_RANGED` with `tconstruct:war_pick` | War_pick is a `ModifiableCrossbowItem` (crossbow/pickaxe hybrid) — passed the instanceof filter and appeared as ~50% of crossbow replacements. Now excluded. |
| Stale javadocs across 4 files updated | All class/method javadocs now mention ranged weapons alongside tools and armor. |

---

## Phase 2: Mob Equipment Replacement with AI Goals

### 2.1 The AI Goal Problem

After Phase 1, `MobEquipmentReplacer` already swaps a skeleton's vanilla bow for a TC longbow. The item swap works. But the skeleton can't USE it.

**Root cause**: Vanilla `RangedBowAttackGoal.canUse()` (line 44-46) calls `isHoldingBow()` (line 48) which checks `this.mob.isHolding(is -> is.getItem() instanceof BowItem)`. TC's `ModifiableBowItem` does NOT extend `BowItem`. The goal's `canUse()` returns false. The skeleton stands idle.

Same problem for crossbow: `RangedCrossbowAttackGoal.isHoldingCrossbow()` (line 40) checks `instanceof CrossbowItem`. TC's `ModifiableCrossbowItem` does NOT extend `CrossbowItem`.

Melee does NOT have this problem. `MeleeAttackGoal` calls `mob.doHurtTarget(target)` which works with any held item. No class check. This is confirmed by TC-Emergence which does zero AI work for melee weapon assignment.

### 2.2 Why Not Subclass the Vanilla Goals

**Bow goal** — `RangedBowAttackGoal`:
- `isHoldingBow()` is `protected` (line 48) — could override
- BUT `tick()` has hardcoded `instanceof BowItem` at line 152 (`ProjectileUtil.getWeaponHoldingHand` predicate) which references private fields (`seeTime`, `strafingTime`, `attackTime`, etc.)
- Cannot override `tick()` in a subclass without access to private fields

**Crossbow goal** — `RangedCrossbowAttackGoal`:
- `isHoldingCrossbow()` is `private` (line 40) — cannot override at all
- `tick()` calls vanilla static methods (`CrossbowItem.getChargeDuration()`, `CrossbowItem.setCharged()`) that are incompatible with TC crossbows (see Section 2.4)

**Decision**: Copy both goals with targeted modifications. ~80-100 lines each. This is the same approach TC-Emergence takes (SmartBowAttackGoal / SmartCrossbowAttackGoal are complete reimplementations).

### 2.3 Bow Goal: TcBowAttackGoal — Double-Fire Prevention

**Critical finding**: Vanilla `BowItem.releaseUsing()` checks `instanceof Player` (line 25) — it is a no-op for mobs. Vanilla designed mob bow firing as: `stopUsingItem()` (no-op for mobs) -> `performRangedAttack()` (mob fires its own arrow). One arrow total.

TC's `ModifiableBowItem.releaseUsing()` has NO player check — it fires arrows for ALL `LivingEntity` types including mobs. If we kept the vanilla goal flow unchanged: `stopUsingItem()` fires a TC arrow via `releaseUsing()` -> `performRangedAttack()` fires a vanilla arrow -> **two arrows per attack**.

**Fix**: In `TcBowAttackGoal`, remove the `performRangedAttack()` call. Let TC's `releaseUsing()` handle everything — it creates arrows with TC damage stats, modifier hooks, proper ammo consumption. This is actually better than vanilla's approach because arrows get TC properties.

**New file**: `src/main/java/com/pwazta/nomorevanillatools/loot/ai/TcBowAttackGoal.java`

Copy of `RangedBowAttackGoal` (~80 lines) with these changes:
1. Store own `mob` reference (super's is private)
2. `isHoldingBow()` (line 48): `instanceof BowItem` -> `instanceof BowItem || instanceof ModifiableBowItem`
3. `tick()` line 152 `getWeaponHoldingHand` predicate: same instanceof expansion
4. `tick()` lines 146-149: **remove `performRangedAttack()` call entirely** — prevents double-fire, TC's `releaseUsing()` handles arrow creation
5. Remove `BowItem.getPowerForTime(i)` reference (no longer needed)

### 2.4 Crossbow Goal: TcCrossbowAttackGoal — TC Charge API Compatibility

TC crossbow uses a completely different charge/ammo mechanism from vanilla:

| Aspect | Vanilla `CrossbowItem` | TC `ModifiableCrossbowItem` |
|--------|----------------------|---------------------------|
| Charge duration | `CrossbowItem.getChargeDuration()` — 25 ticks base, Quick Charge reduces | `ToolStats.DRAW_SPEED` — stored as int in persistent data key `tconstruct:drawtime` |
| Charged state | NBT tag `"Charged"` (boolean) | Presence of ammo in `tconstruct:crossbow_ammo` CompoundTag |
| Ammo storage | `"ChargedProjectiles"` ListTag | `tconstruct:crossbow_ammo` CompoundTag in persistent data |
| Loading ammo | `CrossbowItem.tryLoadProjectiles()` | `BowAmmoModifierHook.consumeAmmo()` via TC modifier system |

Vanilla static methods called on a TC crossbow would:
- `CrossbowItem.getChargeDuration(stack)` -> returns wrong value (ignores TC draw speed)
- `CrossbowItem.setCharged(stack, false)` -> sets a tag TC ignores, ammo not cleared
- Ammo never loaded into TC's storage -> mob can't fire

**No double-fire issue** for crossbow — the flow is two-phase: `releaseUsing()` **loads** ammo into crossbow storage, then later `performRangedAttack()` **fires** the loaded ammo. Different steps, not duplicated.

**New file**: `src/main/java/com/pwazta/nomorevanillatools/loot/ai/TcCrossbowAttackGoal.java`

Copy of `RangedCrossbowAttackGoal` (~100 lines) with these changes:
1. Store own `mob` reference (super's is private)
2. `isHoldingCrossbow()` (line 40): `instanceof CrossbowItem` -> also accept `ModifiableCrossbowItem`
3. `tick()` line 109 and line 133 `getWeaponHoldingHand` predicates: same instanceof expansion
4. `tick()` line 120 `CrossbowItem.getChargeDuration(itemstack)`: branch on item type. For TC crossbow: `ToolStack.from(itemstack).getPersistentData().getInt("tconstruct:drawtime")`. For vanilla crossbow (fallback): keep `CrossbowItem.getChargeDuration()`.
5. `tick()` line 134 `CrossbowItem.setCharged(itemstack1, false)`: for TC crossbow: `ToolStack.from(itemstack1).getPersistentData().remove("tconstruct:crossbow_ammo")`. For vanilla: keep original call.
6. `stop()` line 66: same `setCharged` replacement as change 5.
7. `stop()`: also clear `tconstruct:drawback_ammo` from persistent data (tracks ammo during draw animation, needs cleanup on goal interruption).

### 2.5 AbstractSkeleton.reassessWeaponGoal() Interaction

**Critical interaction**: `AbstractSkeleton.reassessWeaponGoal()` is called whenever `setItemSlot()` is triggered (which `MobEquipmentReplacer` does). It checks `itemstack.is(Items.BOW)` — NOT `instanceof BowItem`. When the bow is replaced with a TC longbow, this check **fails**, so the skeleton removes its bow goal and adds a **melee goal at priority 4**.

This happens BEFORE our `ensureGoalsForRangedWeapons()` runs (it's triggered inside `setItemSlot`, during the equipment loop). By the time our helper executes, the skeleton has: no bow goal, melee goal at priority 4.

**Fix**: Add `TcBowAttackGoal` at **priority 3** (not 4) so it takes precedence over the melee goal that `reassessWeaponGoal` added. The leftover melee goal at priority 4 acts as a **natural fallback** — if the TC bow breaks or the mob can't fire, it falls back to melee. This mirrors TC-Emergence's approach (adds ranged goal at `melee_priority - 1`).

Same flow on chunk reload:
1. Mob loads, `reassessWeaponGoal()` runs during deserialization — adds melee goal at 4 (TC bow not recognized as `Items.BOW`)
2. `EntityJoinLevelEvent` fires — equipment loop does nothing (TC bow is not vanilla) — `ensureGoalsForRangedWeapons()` adds `TcBowAttackGoal` at priority 3

**Crossbow is unaffected** — `Pillager` does not have `reassessWeaponGoal()`. It registers `RangedCrossbowAttackGoal` at priority 3 in `registerGoals()`. Our helper removes it and adds `TcCrossbowAttackGoal` at priority 3.

### 2.6 RangedGoalHelper — Encapsulated Goal Management

**New file**: `src/main/java/com/pwazta/nomorevanillatools/loot/ai/RangedGoalHelper.java`

Single public method — fully encapsulated, idempotent, handles both initial replacement and chunk reload:

```java
public static void ensureGoalsForRangedWeapons(Mob mob) {
    for (InteractionHand hand : InteractionHand.values()) {
        ItemStack held = mob.getItemInHand(hand);
        if (held.isEmpty()) continue;

        if (held.getItem() instanceof ModifiableBowItem) {
            if (!hasGoalOfType(mob, TcBowAttackGoal.class)) {
                removeGoalOfType(mob, RangedBowAttackGoal.class);
                // Priority 3: one above the melee goal (priority 4) that
                // AbstractSkeleton.reassessWeaponGoal() adds when it doesn't
                // recognize the TC bow as Items.BOW. Melee serves as fallback.
                mob.goalSelector.addGoal(3, new TcBowAttackGoal<>(mob, 1.0D, 20, 15.0F));
            }
            return;
        }
        if (held.getItem() instanceof ModifiableCrossbowItem) {
            if (!hasGoalOfType(mob, TcCrossbowAttackGoal.class)) {
                removeGoalOfType(mob, RangedCrossbowAttackGoal.class);
                // Priority 3: matches Pillager's vanilla registration priority
                mob.goalSelector.addGoal(3, new TcCrossbowAttackGoal<>(mob, 1.0D, 8.0F));
            }
            return;
        }
    }
}

// TODO: Consider ModifiableLauncherItem as catch-all if TC adds more ranged types

private static boolean hasGoalOfType(Mob mob, Class<?> goalClass) {
    return mob.goalSelector.getAvailableGoals().stream()
        .anyMatch(wrapped -> goalClass.isInstance(wrapped.getGoal()));
}

private static void removeGoalOfType(Mob mob, Class<?> goalClass) {
    mob.goalSelector.getAvailableGoals()
        .removeIf(wrapped -> goalClass.isInstance(wrapped.getGoal()));
}
```

**Design properties**:
- **Idempotent**: checks for existing TC goal before adding. Safe to call on every EntityJoinLevelEvent.
- **Handles chunk reload**: goals lost on unload, but mob still holds TC weapon. Re-attaches automatically.
- **No entity tags needed**: unlike TC-Emergence's `_applied` tag approach, checks held items + goal state each time.
- **Performant**: for 99% of mobs (no TC ranged weapon), cost is 2 `getItemInHand()` calls + 4 `instanceof` checks. Returns immediately. Negligible compared to the existing equipment loop which does 6 slot iterations with HashMap lookups.
- **Priority 3 (bow)**: one above the melee fallback that `AbstractSkeleton.reassessWeaponGoal()` adds at priority 4. See Section 2.5.
- **Priority 3 (crossbow)**: matches Pillager's vanilla registration priority.
- **Constructor args**: verified against decompiled source — `AbstractSkeleton` line 50 (bow: speed=1.0, interval=20, radius=15.0), `Pillager` line 69 (crossbow: speed=1.0, radius=8.0).

### 2.7 MobEquipmentReplacer Integration

**File**: `src/main/java/com/pwazta/nomorevanillatools/loot/MobEquipmentReplacer.java`

Minimal change — one import, one line:

```java
// After existing equipment loop (line 42):
if (Config.replaceMobRangedAI)
    RangedGoalHelper.ensureGoalsForRangedWeapons(mob);
```

All ranged AI logic encapsulated in `RangedGoalHelper`. `MobEquipmentReplacer` remains a pure equipment swapper + one delegation call.

### 2.8 Config

**New toggle in `Config.java`**:

```java
public static boolean replaceMobRangedAI = true;
```

Guards the `ensureGoalsForRangedWeapons()` call independently from `replaceMobEquipment`. Users can disable mob ranged AI if it causes issues while keeping equipment replacement active for debugging.

### Phase 2 Summary

| File | Change |
|------|--------|
| `MobEquipmentReplacer.java` | +1 import, +2 lines (config guard + helper call) |
| `Config.java` | +1 toggle (`replaceMobRangedAI`) |

| New File | Lines (est.) | Purpose |
|----------|-------------|---------|
| `loot/ai/RangedGoalHelper.java` | ~40 | Goal detection, removal, attachment. Single public entry point. |
| `loot/ai/TcBowAttackGoal.java` | ~80 | Vanilla copy. 4 changes: instanceof expansion, remove performRangedAttack (double-fire fix). |
| `loot/ai/TcCrossbowAttackGoal.java` | ~100 | Vanilla copy. 7 changes: instanceof expansion, TC charge/ammo API compatibility, drawback_ammo cleanup. |

**No changes to**: VanillaLootReplacer, TinkerToolBuilder, ToolExclusionConfig, MaterialMappingConfig, VanillaItemMappings (Phase 1 already added ranged support).

---

## Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Eligible item scanning | `instanceof` per class (A), not ToolAction | TC bows/crossbows declare no ToolActions. Verified against ToolDefinitionDataProvider.java and ToolHooks default. |
| Scanning classes | `ModifiableBowItem` + `ModifiableCrossbowItem` specifically | Precise, no false positives. TODO flag for `ModifiableLauncherItem` generalization. |
| `tryReplace` dispatch | Third branch with `RangedInfo` record | Parallel to existing ToolInfo/ArmorInfo pattern. |
| `buildWithMaterials` refactor | Split into select + build | Eliminates `isArmor` boolean flag. Each type selects materials independently, shared build core. Single Responsibility. |
| Material selection | Stat-type-first, 85/15 canonical/random, tier-capped | Ranged stat types (limb/grip/bowstring) differ from HeadMaterialStats tier pools. Query `getCompatibleMaterials(statType)` (cached in `MATERIAL_CACHE`), filter by `IMaterial.getTier() <= maxTcTier`. Both 85% canonical and 15% random draw from the tier-filtered pool. Fallback to unfiltered if tier filtering empties the pool. |
| Per-part tier defaults | Hardcoded constant in VanillaItemMappings | Only 2 weapons, 7 entries total. Not worth a config file. Material pools per tier already configurable via material_mappings.json. |
| Mob AI for ranged | Custom goal classes (copy + modify vanilla) | Subclassing insufficient — private fields, hardcoded instanceof checks in tick(), vanilla static method calls incompatible with TC. |
| Bow double-fire fix | Remove `performRangedAttack()` from goal | TC's `releaseUsing()` fires arrows for all LivingEntity (unlike vanilla which gates on `instanceof Player`). Removing mob's own firing prevents duplicate arrows. TC arrows get proper stats/modifiers. |
| Crossbow charge API | Branch on item type, use TC persistent data | TC uses `tconstruct:drawtime` and `tconstruct:crossbow_ammo` instead of vanilla's `"Charged"` tag and `getChargeDuration()`. Vanilla static methods are no-ops on TC crossbows. |
| Goal priority | Priority 3 for both bow and crossbow | Bow: one above the melee fallback (priority 4) that `AbstractSkeleton.reassessWeaponGoal()` adds when it doesn't recognize TC bow. Crossbow: matches Pillager's vanilla registration. See Section 2.5. |
| Goal attachment | `RangedGoalHelper.ensureGoalsForRangedWeapons()` | Idempotent, handles chunk reload, fully encapsulated. MobEquipmentReplacer adds one line. No entity tags needed. |
| External dependencies | None (no MobWeaponAPI) | MobWeaponAPI is a general framework by TC-Emergence's author. Overkill for 2 weapon types. Our goal copies are ~180 lines total, fully self-contained. |

---

## Documentation Updates

### CONTEXT.md

Add to Codebase Structure:
```
  loot/
    ai/
      RangedGoalHelper.java             - Encapsulated TC ranged goal attachment for mobs
      TcBowAttackGoal.java              - TC-aware bow attack goal (vanilla copy, double-fire fix)
      TcCrossbowAttackGoal.java         - TC-aware crossbow attack goal (vanilla copy, TC charge API)
```

Add to App Flow after Section 9 (Mob Equipment):
- Document `RangedGoalHelper.ensureGoalsForRangedWeapons()` flow
- Document the double-fire prevention (bow) and TC charge API compatibility (crossbow)

Add to Notes:
```
- TODO: Consider ModifiableLauncherItem as catch-all for eligible ranged weapon scanning
  if TC adds more ranged weapon types beyond longbow/crossbow. Currently using specific
  subclass checks (ModifiableBowItem, ModifiableCrossbowItem) for precision.
```

### Config Options Table

Add:
```
| replaceMobRangedAI | true | Replace mob ranged AI goals when TC ranged weapons are equipped |
```

### VanillaItemMappings Section

Add RangedInfo record and per-part tier defaults documentation.

---

## Verification Checklist

### Phase 1
- [ ] `VanillaItemMappings.getRangedInfo(Items.BOW)` returns correct `RangedInfo`
- [ ] `VanillaItemMappings.getRangedInfo(Items.CROSSBOW)` returns correct `RangedInfo`
- [ ] `getEligibleRanged("bow")` finds `tconstruct:longbow`
- [ ] `getEligibleRanged("crossbow")` finds `tconstruct:crossbow`
- [ ] `selectRangedMaterials()` produces valid materials for all part slots (verify stat type compatibility)
- [ ] Canonical material for wooden tier has LimbMaterialStats (if not, fallback works)
- [ ] Bows in chest loot replaced with TC longbows
- [ ] Crossbows in chest loot replaced with TC crossbows
- [ ] Bows in fishing loot replaced (if applicable)
- [ ] Damage transfer works (damaged vanilla bow -> proportionally damaged TC longbow)
- [ ] Existing tool/armor replacement unaffected by refactor
- [ ] All caches clear correctly on reload

### Phase 2
- [ ] Skeleton with TC longbow fires TC arrows (not vanilla arrows, not double arrows)
- [ ] Pillager with TC crossbow charges, loads, and fires correctly
- [ ] TC crossbow charge duration uses `ToolStats.DRAW_SPEED` (not vanilla 25 ticks)
- [ ] TC crossbow ammo cleared correctly after firing (`tconstruct:crossbow_ammo` removed)
- [ ] Chunk reload: skeleton retains TC bow AND re-acquires TC bow goal
- [ ] Chunk reload: pillager retains TC crossbow AND re-acquires TC crossbow goal
- [ ] Mob with no ranged weapon: `ensureGoalsForRangedWeapons` returns immediately (no overhead)
- [ ] Mob with vanilla bow (not replaced): vanilla goal untouched
- [ ] `Config.replaceMobRangedAI = false`: goals not replaced, equipment still swapped
- [ ] Bow goal at priority 3 takes precedence over melee fallback at priority 4 (reassessWeaponGoal interaction)
- [ ] Crossbow goal at priority 3 matches Pillager vanilla registration
- [ ] `tconstruct:drawback_ammo` cleared in crossbow goal `stop()` method
