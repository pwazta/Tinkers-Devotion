# Tinkers' Devotion

> Tinkers' Devotion: true tinkers integration and immersion! Vanilla tools, weapons and armor all replaced by their TC counterparts in recipes, loot, mob equipment, and villager trades. There are no bounds to your tinkering, and the world should reflect that!

This mod is designed to be extensible, so the replacements system should work automatically with most mods! Most replacement systems can be toggled, scoped per item type, or blacklisted per mod. See [Configuration](#configuration) further down.

---

## Features

### 1. Mob Equipment

Mobs spawning with vanilla tools or armor spawn with the TC equivalent instead, rolled fresh per mob with materials biased toward the same tier. This affects armour, melee and ranged weapons.

### 2. Loot Tables

Vanilla tools, weapons, and armor in chest loot, fishing pools, and mob drops get replaced with TC same-tier equivalents at the same tier. Pre-enchanted items have their enchantments converted to weighted-random TC modifiers as part of the swap.

### 3. Crafting Recipes

Crafting vanilla tools, armor, and ranged weapons as outputs is disabled (via Mantle). Recipes that take vanilla tools as inputs (e.g: a `dispenser` requiring a bow) are rewritten to accept a TC equivalent at the same tier. This is designed to work for ALL modded recipes which require vanilla items as recipe inputs.

I highly suggest you use JEI, as it shows candidate TC tools per slot with tier hints in the tooltip.

### 4. Villager Trades

All villager trades involving such items (e.g: armorer, toolsmith, fisherman) have these trades replaced with a deterministic trade on spawn (e.g: iron-sword from a toolsmith will be replaced with a tier-2 TC sword equivalent). Wandering traders are also handled in the same way.

---

## Nerdy Stuff

> Skip to [Configuration](#configuration) if you just want to play.

**The general flow**

The core of the mod is a shared `tryReplace()` function: vanilla item in, TC equivalent out (matched category, weighted materials, enchantments converted to modifiers). Everything else is just hooks that pass items into it.

Four entry points feed `tryReplace()`:

- **First world load:** scan all loaded recipes, write a generated datapack with TC-aware replacements, auto-reload.
- **Mob spawn:** an `EntityJoinLevelEvent` handler swaps any vanilla item in the mob's slots.
- **Loot generation:** a Forge Global Loot Modifier runs the same swap on result lists.
- **Villager trade roll:** trade listings are wrapped at registration time, so trade results pass through the same swap.

Because every hook converges on the same `tryReplace()`, a mob's diamond axe and a chest's diamond axe roll the same way.

**Picking materials: the 80 / 10 / 10 split** *(configurable)*

We pick a TC tool matching the vanilla item's category (sword / pickaxe type, etc.), then rolls materials per part using a weighted split:

- **80% canonical:** the hand-picked TC material for that tier, chosen so the result feels natural.
- **10% same-tier random:** variety within the tier.
- **10% any-tier random:** cross-tier variance, capped at a configurable max tier (default diamond) so you don't get random hepatizon zombies in early game.

The full canonical mappings:


| Vanilla tier     | Tool canonical         | Armor canonical        |
| ---------------- | ---------------------- | ---------------------- |
| wooden           | `tconstruct:wood`      | —                      |
| stone            | `tconstruct:rock`      | —                      |
| leather          | —                      | `tconstruct:copper`    |
| iron / chainmail | `tconstruct:iron`      | `tconstruct:iron`      |
| golden           | `tconstruct:rose_gold` | `tconstruct:gold`      |
| diamond          | `tconstruct:cobalt`    | `tconstruct:cobalt`    |
| netherite        | `tconstruct:hepatizon` | `tconstruct:hepatizon` |


**Enchantments → TC modifiers**

Pre-enchanted vanilla items get their enchantments converted to TC modifiers.

1. **Budget:** sum vanilla enchantment levels, take `ceil(sqrt(totalLevels × 4))`, cap at 7. Sharpness V Unbreaking III Mending I gets a budget of about 6.
2. **Modifier pool:** built from your installed TC recipes, so addon modifiers are auto-included. Modifiers with unmet prerequisites are filtered.
3. **Specialty weighting:** specialist modifiers (apply specifically to one "type" of tool, e.g: sharp) are ~8× more likely than universals. Tunable.
4. **Spending the budget:** at each step, either level up an existing modifier (default 65% chance) or pick a new one. Bonus-slot modifiers added if budget overflows.

A skip list in the config keeps unwanted modifiers out (defaults include `netherite`, `creative_slot`, `draconic`, etc.).

**Compatibility & extensibility**

- **TC addons:** picked up automatically via standard tags. Tested with Tinkers' Things, TCIntegrations, Tinkers Levelling Addon, and Tinkers' Thinking.
- **Addon materials:** read live from `MaterialRegistry`. New material drops in, it's eligible. Custom-tier materials work for armor and ranged; tools need a vanilla-tier mapping.
- **Modded recipes:** caught by the same scan as vanilla. Datapack recipes should be automatically handled.
- **Modded loot, mob equipment, villager professions:** all hit the same hooks as vanilla. No allowlist.
- **JEI:** integrated. Replacement ingredient cycles through valid TC tools per slot. Recipe transfer works on the vanilla 3×3, player inventory grid, TC crafting station, and any container declared in the user-extensible config.
  - NOTE: Other crafting interfaces may need special handling which I haven't specifically accounted for! Please let me know if there are any issues.

---

## Configuration

Drop the jar in your mods folder and load a world.

The defaults are quite sensible but if you want to tune, three files appear under `config/` after first launch:


| File                                      | Format | What it controls                                                                                                                                                                |
| ----------------------------------------- | ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `tinkersdevotion-common.toml`             | TOML   | Main toggles. Disable mob, loot, or villager replacement independently. Tune the parts-match threshold, loot variance, modifier specialization weight.                          |
| `tinkersdevotion/tool_exclusions.json`    | JSON   | Blacklist specific TC tools per slot or action. Stops dagger from substituting for cleaver in recipes, stops slimesuit from rolling as armor loot, etc. Defaults pre-populated. |
| `tinkersdevotion/modifier_skip_list.json` | JSON   | TC modifier IDs that should never appear in the random enchantment pool. Defaults pre-populated.                                                                                |


### Commands

- `/tinkersdevotion generate` re-runs the recipe scan and rewrites the generated datapack. Useful after installing or removing mods. OP level 2.
- `/tinkersdevotion generate reset` resets the two JSON config files to defaults and regenerates. The TOML config is untouched.

The recipe datapack auto-generates on first world load, so the command is only needed if you change your mod list.

---

## Issues & Feedback

Any bug reports, feature requests / suggestions (e.g: "this addon mod isn't getting picked up correctly"), please leave a comment or go on the [GitHub issue tracker](https://github.com/pwazta/Tinkers-Devotion/issues).

- I am just one guy!! 

- FAQ COMING SOON.

---

## Requirements & Compatibility

- Minecraft **1.20.1**, Forge **47.4+**
- [Tinkers' Construct](https://www.curseforge.com/minecraft/mc-mods/tinkers-construct) **3.11+**
- [Mantle](https://www.curseforge.com/minecraft/mc-mods/mantle) **1.11+** (Tinkers' dependency, you'll have it)
- [JEI](https://www.curseforge.com/minecraft/mc-mods/jei) **15.20+** *(optional, used for recipe display and shift-click transfer)*

Tested with Tinkers' Things, TCIntegrations, Tinkers Levelling Addon, and Tinkers' Thinking. Should be friendly to any other TC addon that uses standard tags and registers its modifiers via TC's recipe system.

---

## Credits & License

**Tinkers' Construct** and **Mantle** by [SlimeKnights](https://github.com/SlimeKnights). Without them this mod would have nothing to integrate.

**License: All Rights Reserved.** Short version:

- Free to include in any public or private modpack. Credit would be nice but not required.
- Free for personal use.
- Not allowed: modification, forks, re-uploads, or porting without written permission.

Full terms in [LICENSE.md](LICENSE.md). For permission requests, open a [GitHub issue](https://github.com/pwazta/Tinkers-Devotion/issues).