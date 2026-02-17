# Mixin Arm Pose Fix — Design

## Problem
Mobs holding TC ranged weapons display wrong arm poses due to hardcoded vanilla checks:
- **Skeleton + TC bow**: `SkeletonModel.prepareMobModel()` checks `itemstack.is(Items.BOW)` — fails for TC bow → EMPTY pose → zombie arms activate in `setupAnim()`
- **Pillager + TC crossbow**: `Pillager.getArmPose()` checks `instanceof CrossbowItem` — TC's `ModifiableCrossbowItem` doesn't extend `CrossbowItem` → ATTACKING pose instead of CROSSBOW_HOLD/CROSSBOW_CHARGE

## Solution
MixinGradle setup + two client-only mixins + one goal fix.

## Changes

### 1. Build Setup
- **settings.gradle**: Add SpongePowered repo to pluginManagement
- **build.gradle**:
  - Add `buildscript { }` block with `org.spongepowered:mixingradle:0.7-SNAPSHOT`
  - `apply plugin: 'org.spongepowered.mixin'` after plugins block
  - `annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'`
  - `mixin { }` config block
  - `-mixin.config=nomorevanillatools.mixins.json` args in run configs
  - `mixin.env.remapRefMap` + `mixin.env.refMapRemappingFile` properties
  - `MixinConfigs` in jar manifest for production
- **NO `[[mixins]]` in mods.toml** — avoids JPMS split-package crash in dev

### 2. Mixin Config
- `src/main/resources/nomorevanillatools.mixins.json`
- Package: `com.pwazta.nomorevanillatools.mixin`
- Client array: `PillagerMixin`, `SkeletonModelMixin`

### 3. PillagerMixin (client-only)
- Target: `Pillager.getArmPose()`
- `@Inject` at HEAD, cancellable
- If holding TC crossbow: return CROSSBOW_CHARGE (if charging) or CROSSBOW_HOLD
- Uses `(Pillager)(Object)this` cast for inherited methods
- Non-TC weapons fall through to vanilla

### 4. SkeletonModelMixin (client-only)
- Target: `SkeletonModel`
- Extends `HumanoidModel<T>` for field access
- **Injection 1**: `@Inject RETURN` on `prepareMobModel()` — sets correct arm pose for TC bow/crossbow after vanilla resets to EMPTY
- **Injection 2**: `@Redirect` on `ItemStack.is(Item)` in `setupAnim()` — returns true for TC ranged weapons, preventing zombie-arms fallback

### 5. TcCrossbowAttackGoal Fix
- Add `start()` override with `setAggressive(true)` — matches TcBowAttackGoal pattern
- Required for SkeletonModel to detect aggressive state

## Verified Facts (CoVe)
- `SkeletonModel` is `@OnlyIn(Dist.CLIENT)` — confirmed
- `Pillager.getArmPose()` is NOT `@OnlyIn` — exists both sides, safe in client mixin array
- `rightArmPose`/`leftArmPose` are public fields in `HumanoidModel` — confirmed
- Exactly ONE `ItemStack.is(Item)` call in `SkeletonModel.setupAnim()` — confirmed
- `setupAnim()` calls `super.setupAnim()` BEFORE zombie arms check — confirmed
- `RenderLivingEvent.Pre` fires BEFORE `prepareMobModel()` — confirmed (events can't solve this)
- `TcCrossbowAttackGoal` calls `setChargingCrossbow(true)` during CHARGING — confirmed
- TC hierarchy: `ModifiableBowItem`/`ModifiableCrossbowItem` both extend `ModifiableLauncherItem` extends `ProjectileWeaponItem`

## Risks
- **Mixin AP may warn** about TSRG2 format — runtime remapper handles it regardless
- **JPMS split-package** could recur if MixinGradle triggers module creation — revert with `git checkout .`
- **@Redirect fragility** — if another mod also redirects the same `is()` call, conflict. Low risk for this target.
