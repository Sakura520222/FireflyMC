---
URL: https://docs.neoforged.net/docs/1.21.1/resources/server/loottables/lootconditions
抓取时间: 2026-03-13 22:28:43
源站: NeoForge 1.21.1 官方文档
---






Loot Conditions | NeoForged docs




!function(){function t(t){document.documentElement.setAttribute("data-theme",t)}var e=function(){try{return new URLSearchParams(window.location.search).get("docusaurus-theme")}catch(t){}}()||function(){try{return window.localStorage.getItem("theme")}catch(t){}}();null!==e?t(e):window.matchMedia("(prefers-color-scheme: dark)").matches?t("dark"):(window.matchMedia("(prefers-color-scheme: light)").matches,t("light"))}(),function(){try{const c=new URLSearchParams(window.location.search).entries();for(var[t,e]of c)if(t.startsWith("docusaurus-data-")){var a=t.replace("docusaurus-data-","data-");document.documentElement.setAttribute(a,e)}}catch(t){}}()[Skip to main content]()[**Homepage**]()[NeoForge Documentation]()[Toolchain Features]()[Primers]()[User Guide]()[Modpack Development]()[1.21 - 1.21.1]()


- [1.21.11]()
- [1.21.9 - 1.21.10]()
- [1.21.6 - 1.21.8]()
- [1.21.5]()
- [1.21.4]()
- [1.21.2 - 1.21.3]()
- [1.21 - 1.21.1]()
- [1.20.5 - 1.20.6]()
- [1.20.3 - 1.20.4]()[Contributing]()[GitHub]()Search


- [Getting Started]()
- [Concepts]()
- [Blocks]()
- [Items]()
- [Block Entities]()
- [Resources]()


- [Client]()
- [Server]()


- [Advancements]()
- [Data Load Conditions]()
- [Damage Types & Damage Sources]()
- [Data Maps]()
- [Enchantments]()
- [Loot Tables]()


- [Custom Loot Objects]()
- [Global Loot Modifiers]()
- [Loot Conditions]()
- [Loot Functions]()
- [Recipes]()
- [Tags]()
- [Inventories & Transfers]()
- [Data Storage]()
- [GUIs]()
- [Worldgen]()
- [Networking]()
- [Advanced Topics]()
- [Miscellaneous]()This is documentation for NeoForged **1.21 - 1.21.1**, which is no longer actively maintained.For up-to-date documentation, see the **[latest version]()** (1.21.11).


- []()
- [Resources]()
- Server
- [Loot Tables]()
- Loot ConditionsVersion: 1.21 - 1.21.1On this page

# Loot Conditions



Loot conditions can be used to check whether a [loot entry]() or [loot pool]() should be used in the current context. In both cases, a list of conditions is defined; the entry or pool is only used if all conditions pass. During datagen, they are added to a `LootPoolEntryContainer.Builder<?>` or `LootPool.Builder` by calling `#when` with an instance of the desired condition. This article will outline the available loot conditions. To create your own loot conditions, see [Custom Loot Conditions]().


## `minecraft:inverted`[​]()



This condition accepts another condition and inverts its result. Requires whatever loot parameters the other condition requires.


```
``
```




During datagen, call `InvertedLootItemCondition#invert` with the condition to invert to construct a builder for this condition.


## `minecraft:all_of`[​]()



This condition accepts any number of other conditions and returns true if all sub conditions return true. If the list is empty, it returns false. Requires whatever loot parameters the other conditions require.


```
``
```




During datagen, call `AllOfCondition#allOf` with the desired condition(s) to construct a builder for this condition.


## `minecraft:any_of`[​]()



This condition accepts any number of other conditions and returns true if at least one sub condition returns true. If the list is empty, it returns false. Requires whatever loot parameters the other conditions require.


```
``
```




During datagen, call `AnyOfCondition#anyOf` with the desired condition(s) to construct a builder for this condition.


## `minecraft:random_chance`[​]()



This condition accepts a [number provider]() representing a chance between 0 and 1, and randomly returns true or false depending on that chance. The number provider should generally not return values outside the `[0, 1]` interval.


```
``
```




During datagen, call `RandomChance#randomChance` with the number provider or a (constant) float value to construct a builder for this condition.


## `minecraft:random_chance_with_enchanted_bonus`[​]()



This condition accepts an enchantment id, a [`LevelBasedValue`]() and a constant fallback float value. If the specified enchantment is present, the `LevelBasedValue` is queried for a value. If the specified enchantment is absent, or no value could be retrieved from the `LevelBasedValue`, the constant fallback value is used. The condition then randomly returns true or false, with the previously determined value denoting the chance that true is returned. Requires the `minecraft:attacking_entity` parameter, falling back to level 0 if absent.


```
``
```




During datagen, call `LootItemRandomChanceWithEnchantedBonusCondition#randomChanceAndLootingBoost` with the registry lookup (`HolderLookup.Provider`), the base value and the increase per level to construct a builder for this condition. Alternatively, call `new LootItemRandomChanceWithEnchantedBonusCondition` to further specify the values.


## `minecraft:value_check`[​]()



This condition accepts a [number provider]() and an `IntRange`, returning true if the result of the number provided is within the range.


```
``
```




During datagen, call `ValueCheckCondition#hasValue` with the number provider and the range to construct a builder for this condition.


## `minecraft:time_check`[​]()



This condition checks if the world time is within an `IntRange`. Optionally, a `period` parameter can be provided to modulo the time with; this can be used to e.g. check the time of day if `period` is 24000 (one in-game day/night cycle has 24000 ticks).


```
``
```




During datagen, call `TimeCheck#time` with the desired range to construct a builder for this condition. The `period` value can then be set on the builder using `#setPeriod`.


## `minecraft:weather_check`[​]()



This condition checks the current weather for raining and thundering.


```
``
```




During datagen, call `WeatherCheck#weather` to construct a builder for this condition. The `raining` and `thundering` values can then be set on the builder using `#setRaining` and `#setThundering`, respectively.


## `minecraft:location_check`[​]()



This condition accepts a `LocationPredicate` and an optional offset value for each axis direction. `LocationPredicate`s allow checking conditions such as the position itself, the block or fluid state at that position, the dimension, biome or structure at that position, the light level, whether the sky is visible, etc. All possible values can be viewed in the `LocationPredicate` class definition. Requires the `minecraft:origin` loot parameter, always failing if that parameter is absent.


```
``
```




During datagen, call `LocationCheck#checkLocation` with the `LocationPredicate` and optionally a `BlockPos` to construct a builder for this condition.


## `minecraft:block_state_property`[​]()



This condition checks for the specified block state properties to have the specified value in the broken block state. Requires the `minecraft:block_state` loot parameter, always failing if that parameter is absent.


```
``
```




During datagen, call `LootItemBlockStatePropertyCondition#hasBlockStateProperties` with the block to construct a builder for this condition. The desired block state property values can then be set on the builder using `#setProperties`.


## `minecraft:survives_explosion`[​]()



This condition randomly destroys the drops. The chance for drops to survive is 1 / `explosion_radius` loot parameter. This function is used by all block drops, with very few exceptions such as the beacon or the dragon egg. Requires the `minecraft:explosion_radius` loot parameter, always succeeding if that parameter is absent.


```
``
```




During datagen, call `ExplosionCondition#survivesExplosion` to construct a builder for this condition.


## `minecraft:match_tool`[​]()



This condition accepts an `ItemPredicate` that is checked against the `tool` loot parameter. An `ItemPredicate` can specify a list of valid item ids (`items`), a min/max range for the item count (`count`), a `DataComponentPredicate` (`components`) and an `ItemSubPredicate` (`predicates`); all fields are optional. Requires the `minecraft:tool` loot parameter, always failing if that parameter is absent.


```
``
```




During datagen, call `MatchTool#toolMatches` with an `ItemPredicate.Builder` to invert to construct a builder for this condition.


## `minecraft:enchantment_active`[​]()



This condition returns whether an enchantment is active or not. Requires the `minecraft:enchantment_active` loot parameter, always failing if that parameter is absent.


```
``
```




During datagen, call `EnchantmentActiveCheck#enchantmentActiveCheck` or `#enchantmentInactiveCheck` to construct a builder for this condition.


## `minecraft:table_bonus`[​]()



This condition is similar to `minecraft:random_chance_with_enchanted_bonus`, but with fixed values instead of randomized values. Requires the `minecraft:tool` loot parameter, always failing if that parameter is absent.


```
``
```




During datagen, call `BonusLevelTableCondition#bonusLevelFlatChance` with the enchantment id and the chances to construct a builder for this condition.


## `minecraft:entity_properties`[​]()



This condition checks a given `EntityPredicate` against an [entity target](). The `EntityPredicate` can check the entity type, mob effects, nbt values, equipment, location etc.


```
``
```




During datagen, call `LootItemEntityPropertyCondition#entityPresent` with the entity target, or `LootItemEntityPropertyCondition#hasProperties` with the entity target and the `EntityPredicate`, to construct a builder for this condition.


## `minecraft:damage_source_properties`[​]()



This condition checks a given `DamageSourcePredicate` against the damage source loot parameter. Requires the `minecraft:origin` and `minecraft:damage_source` loot parameters, always failing if those parameter are absent.


```
``
```




During datagen, call `DamageSourceCondition#hasDamageSource` with a `DamageSourcePredicate.Builder` to construct a builder for this condition.


## `minecraft:killed_by_player`[​]()



This condition determines whether the kill was a player kill. Used by some entity drops, for example blaze rods dropped by blazes. Requires the `minecraft:last_player_damage` loot parameter, always failing if that parameter is absent.


```
``
```




During datagen, call `LootItemKilledByPlayerCondition#killedByPlayer` to construct a builder for this condition.


## `minecraft:entity_scores`[​]()



This condition checks the [entity target]()'s scoreboard. Requires the loot parameter corresponding to the specified entity target, always failing if that parameter is absent.


```
``
```




During datagen, call `EntityHasScoreCondition#hasScores` with an entity target to construct a builder for this condition. Then, add required scores to the builder using `#withScore`.


## `minecraft:reference`[​]()



This condition references a predicate file and returns its result. See [Item Predicates]() for more information.


```
``
```




During datagen, call `ConditionReference#conditionReference` with the id of the referenced predicate file to construct a builder for this condition.


## `neoforge:loot_table_id`[​]()



This condition only returns true if the surrounding loot table id matches. This is typically used within [global loot modifiers]().


```
``
```




During datagen, call `LootTableIdCondition#builder` with the desired loot table id to construct a builder for this condition.


## `neoforge:can_item_perform_ability`[​]()



This condition only returns true if the item in the `tool` loot context parameter (`LootContextParams.TOOL`), usually the item used to break the block or kill the entity, can perform the specified [`ItemAbility`](). Requires the `minecraft:tool` loot parameter, always failing if that parameter is absent.


```
``
```




During datagen, call `CanItemPerformAbility#canItemPerformAbility` with the id of the desired item ability to construct a builder for this condition.


## See Also[​]()





- [Item Predicates]() on the [Minecraft Wiki]()
[PreviousGlobal Loot Modifiers]()[NextLoot Functions]()


- [`minecraft:inverted`]()
- [`minecraft:all_of`]()
- [`minecraft:any_of`]()
- [`minecraft:random_chance`]()
- [`minecraft:random_chance_with_enchanted_bonus`]()
- [`minecraft:value_check`]()
- [`minecraft:time_check`]()
- [`minecraft:weather_check`]()
- [`minecraft:location_check`]()
- [`minecraft:block_state_property`]()
- [`minecraft:survives_explosion`]()
- [`minecraft:match_tool`]()
- [`minecraft:enchantment_active`]()
- [`minecraft:table_bonus`]()
- [`minecraft:entity_properties`]()
- [`minecraft:damage_source_properties`]()
- [`minecraft:killed_by_player`]()
- [`minecraft:entity_scores`]()
- [`minecraft:reference`]()
- [`neoforge:loot_table_id`]()
- [`neoforge:can_item_perform_ability`]()
- [See Also]()Docs


- [NeoForge Documentation]()
- [Toolchain Features]()
- [Primers]()
- [User Guide]()
- [Modpack Development]()
- [Contributing to the Documentation]()Links


- [Discord]()
- [Main Website]()
- [GitHub]()
        

NOT AN OFFICIAL MINECRAFT WEBSITE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
        

Copyright © 2026, under the MIT license. Built with Docusaurus.
        

