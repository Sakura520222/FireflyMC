---
URL: https://docs.neoforged.net/docs/1.21.1/resources/server/loottables/lootfunctions
抓取时间: 2026-03-13 22:28:48
源站: NeoForge 1.21.1 官方文档
---






Loot Functions | NeoForged docs




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
- Loot FunctionsVersion: 1.21 - 1.21.1On this page

# Loot Functions



Loot functions can be used to modify the result of a [loot entry](), or the multiple results of a [loot pool]() or [loot table](). In both cases, a list of functions is defined, which is run in order. During datagen, loot functions can be applied to `LootPoolSingletonContainer.Builder<?>`s, `LootPool.Builder`s and `LootTable.Builder`s by calling `#apply`. This article will outline the available loot functions. To create your own loot functions, see [Custom Loot Functions]().
note

Loot functions cannot be applied to composite loot entries (subclasses of `CompositeEntryBase` and their associated builder classes). They must be added to each singleton entry manually.


All vanilla loot functions except `minecraft:sequence` can specify [loot conditions]() in a `conditions` block. If one of these conditions fails, the function will not be applied. On the code side, this is controlled by the `LootItemConditionalFunction`, which all loot functions except for `SequenceFunction` extend.


## `minecraft:set_item`[​]()



Sets a different item to use in the result item stack.


```
``
```




It is currently not possible to create this function during datagen.


## `minecraft:set_count`[​]()



Sets an item count to use in the result item stack. Uses a [number provider]().


```
``
```




During datagen, call `SetItemCountFunction#setCount` with the desired number provider and optionally an `add` boolean to construct a builder for this function.


## `minecraft:explosion_decay`[​]()



Applies an explosion decay. The item has a chance of 1 / `explosion_radius` to "survive". This is run multiple times depending on the count. Requires the `minecraft:explosion_radius` loot parameter, no modification is performed if that parameter is absent.


```
``
```




During datagen, call `ApplyExplosionDecay#explosionDecay` to construct a builder for this function.


## `minecraft:limit_count`[​]()



Clamps the count of the item stack between a given `IntRange`.


```
``
```




During datagen, call `LimitCount#limitCount` with the desired `IntRange` to construct a builder for this function.


## `minecraft:set_custom_data`[​]()



Sets custom NBT data on the item stack.


```
``
```




During datagen, call `SetCustomDataFunction#setCustomData` with the desired [`CompoundTag`]() to construct a builder for this function.
warning

This function should generally be considered deprecated. Use `minecraft:set_components` instead.


## `minecraft:copy_custom_data`[​]()



Copies custom NBT data from a block entity or entity source to the item stack. Use of this is discouraged for block entities, use `minecraft:copy_components` or `minecraft:set_contents` instead. For entities, this requires setting the [entity target](). Requires the loot parameter corresponding to the specified source (entity target or block entity), no modification is performed if that parameter is absent.


```
``
```




During datagen, call `CopyCustomDataFunction#copy` with the desired source and target values, as well as a merging strategy (optional, defaults to `replace`), to construct a builder for this function.


## `minecraft:set_components`[​]()



Sets [data component]() values on the item stack. Most vanilla use cases have specialized functions that are explained below.


```
``
```




During datagen, call `SetComponentsFunction#setComponent` with the desired data component and value to construct a builder for this function.


## `minecraft:copy_components`[​]()



Copies [data component]() values from a block entity to the item stack. Requires the `minecraft:block_entity` loot parameter, no modification is performed if that parameter is absent.


```
``
```




During datagen, call `CopyComponentsFunction#copyComponents` with the desired data source (usually `CopyComponentsFunction.Source.BLOCK_ENTITY`) to construct a builder for this function.


## `minecraft:copy_state`[​]()



Copies block state properties into the item stack's `block_state` [data component](), used when trying to place a block. The block state properties to copy must be explicitly specified. Requires the `minecraft:block_state` loot parameter, no modification is performed if that parameter is absent.


```
``
```




During datagen, call `CopyBlockState#copyState` with the block to construct a builder for this condition. The desired block state property values can then be set on the builder using `#copy`.


## `minecraft:set_contents`[​]()



Sets contents of the item stack.


```
``
```




During datagen, call `SetContainerContents#setContents` with the desired contents component to construct a builder for this function. Then, call `#withEntry` on the builder to add entries.


## `minecraft:modify_contents`[​]()



Applies a function to the contents of the item stack.


```
``
```




It is currently not possible to create this function during datagen.


## `minecraft:set_loot_table`[​]()



Sets a container loot table on the result item stack. Intended for chests and other loot containers that retain this property when placed down.


```
``
```




During datagen, call `SetContainerLootTable#withLootTable` with the desired block entity type, loot table resource key and optionally a seed to construct a builder for this function.


## `minecraft:set_name`[​]()



Sets a name for the result item stack. The name can be a [`Component`]() instead of a literal string. It can also be resolved from an [entity target](). Requires the corresponding entity loot parameter if applicable, no modification is performed if that parameter is absent.


```
``
```




During datagen, call `SetNameFunction#setName` with the desired name component, the desired name target and optionally an entity target to construct a builder for this function.


## `minecraft:copy_name`[​]()



Copies an [entity target]()'s or block entity's name into the result item stack. Requires the loot parameter corresponding to the specified source (entity target or block entity), no modification is performed if that parameter is absent.


```
``
```




During datagen, call `CopyNameFunction#copyName` with the desired entity source to construct a builder for this function.


## `minecraft:set_lore`[​]()



Sets lore (tooltip lines) for the result item stack. The lines can be [`Component`]()s instead of literal strings. It can also be resolved from an [entity target](). Requires the corresponding entity loot parameter if applicable, no modification is performed if that parameter is absent.


```
``
```




During datagen, call `SetLoreFunction#setLore` to construct a builder for this function. Then, call `#addLine`, `#setMode` and `#setResolutionContext` as needed on the builder.


## `minecraft:toggle_tooltips`[​]()



Enables or disables certain component tooltips.


```
``
```




It is currently not possible to create this function during datagen.


## `minecraft:enchant_with_levels`[​]()



Randomly enchants the item stack with a given amount of levels. Uses a [number provider]().


```
``
```




During datagen, call `EnchantWithLevelsFunction#enchantWithLevels` with the desired number provider to construct a builder for this function. Then, if desired, set a list of enchantments on the builder using `#fromOptions`.


## `minecraft:enchant_randomly`[​]()



Enchants the item with one random enchantment.


```
``
```




During datagen, call `EnchantRandomlyFunction#randomEnchantment` or `EnchantRandomlyFunction#randomApplicableEnchantment` to construct a builder for this function. Then, if desired, call `#withEnchantment` or `#withOneOf` on the builder.


## `minecraft:set_enchantments`[​]()



Sets enchantments on the result item stack.


```
``
```




During datagen, call `new SetEnchantmentsFunction.Builder` with the `add` boolean value (optionally) to construct a builder for this function. Then, call `#withEnchantment` to add an enchantment to set.


## `minecraft:enchanted_count_increase`[​]()



Increases the item stack count based on the enchantment value. Uses a [number provider](). Requires the `minecraft:attacking_entity` loot parameter, no modification is performed if that parameter is absent.


```
``
```




During datagen, call `EnchantedCountIncreaseFunction#lootingMultiplier` with the desired number provider to construct a builder for this function. Optionally, call `#setLimit` on the builder afterwards.


## `minecraft:apply_bonus`[​]()



Applies an increase to the item stack count based on the enchantment value and various formulas. Requires the `minecraft:tool` loot parameter, no modification is performed if that parameter is absent.


```
``
```




During datagen, call `ApplyBonusCount#addBonusBinomialDistributionCount`, `ApplyBonusCount#addOreBonusCount` or `ApplyBonusCount#addUniformBonusCount` with the enchantment and other required parameters (depending on the formula) to construct a builder for this function.


## `minecraft:furnace_smelt`[​]()



Attempts to smelt the item as if it were in a furnace, returning the unmodified item stack if it could not be smelted.


```
``
```




During datagen, call `SmeltItemFunction#smelted` to construct a builder for this function.


## `minecraft:set_damage`[​]()



Sets a durability damage value on the result item stack. Uses a [number provider]().


```
``
```




During datagen, call `SetItemDamageFunction#setDamage` with the desired number provider and optionally an `add` boolean to construct a builder for this function.


## `minecraft:set_attributes`[​]()



Adds a list of attribute modifiers to the result item stack.


```
``
```




During datagen, call `SetAttributesFunction#setAttributes` to construct a builder for this function. Then, add modifiers using `#withModifier` on the builder. Use `SetAttributesFunction#modifier` to get a modifier.


## `minecraft:set_potion`[​]()



Sets a potion on the result item stack.


```
``
```




During datagen, call `SetPotionFunction#setPotion` with the desired potion to construct a builder for this function.


## `minecraft:set_stew_effect`[​]()



Sets a list of stew effects on the result item stack.


```
``
```




During datagen, call `SetStewEffectFunction#stewEffect` to construct a builder for this function. Then, call `#withModifier` on the builder.


## `minecraft:set_ominous_bottle_amplifier`[​]()



Sets an ominous bottle amplifier on the result item stack. Uses a [number provider]().


```
``
```




During datagen, call `SetOminousBottleAmplifierFunction#amplifier` with the desired number provider to construct a builder for this function.


## `minecraft:exploration_map`[​]()



Transforms the result item stack into an exploration map if and only if it is a map. Requires the `minecraft:origin` loot parameter, no modification is performed if that parameter is absent.


```
``
```




During datagen, call `ExplorationMapFunction#makeExplorationMap` to construct a builder for this function. Then, call the various setters on the builder if desired.


## `minecraft:fill_player_head`[​]()



Sets the player head owner on the result item stack based on the given [entity target](). Requires the corresponding loot parameter, no modification is performed if that parameter is absent.


```
``
```




During datagen, call `FillPlayerHead#fillPlayerHead` with the desired entity target to construct a builder for this function.


## `minecraft:set_banner_pattern`[​]()



Sets banner patterns on the result item stack. This is for banners, not banner pattern items.


```
``
```




During datagen, call `SetBannerPatternFunction#setBannerPattern` with the `append` boolean to construct a builder for this function. Then, call `#addPattern` to add patterns to the function.


## `minecraft:set_instrument`[​]()



Sets the instrument tag on the result item stack.


```
``
```




During datagen, call `SetInstrumentFunction#setInstrumentOptions` with the desired instrument tag to construct a builder for this function.


## `minecraft:set_fireworks`[​]()



```
``
```




It is currently not possible to create this function during datagen.


## `minecraft:set_firework_explosion`[​]()



Sets a firework explosion on the result item stack.


```
``
```




During datagen, call `SetItemCountFunction#setCount` with the desired number provider and optionally an `add` boolean to construct a builder for this function.


## `minecraft:set_book_cover`[​]()



Sets a written book's non-page-specific content.


```
``
```




During datagen, call `new SetBookCoverFunction` with the desired parameters to construct a builder for this function.


## `minecraft:set_written_book_pages`[​]()



Sets the pages of a written book.


```
``
```




It is currently not possible to create this function during datagen.


## `minecraft:set_writable_book_pages`[​]()



Sets the pages of a writable book (book and quill).


```
``
```




It is currently not possible to create this function during datagen.


## `minecraft:set_custom_model_data`[​]()



Sets the custom model data of the result item stack.


```
``
```




It is currently not possible to create this function during datagen.


## `minecraft:filtered`[​]()



This function accepts an `ItemPredicate` that is checked against the `tool` loot parameter; if the check succeeds, the other function is run. An `ItemPredicate` can specify a list of valid item ids (`items`), a min/max range for the item count (`count`), a `DataComponentPredicate` (`components`) and an `ItemSubPredicate` (`predicates`); all fields are optional. Requires the `minecraft:tool` loot parameter, always failing if that parameter is absent.


```
``
```




It is currently not possible to create this function during datagen.
warning

This function should generally be considered deprecated. Use the passed function with a `minecraft:match_tool` condition instead.


## `minecraft:reference`[​]()



This function references an item modifier and applies it to the result item stack. See [Item Modifiers]() for more information.


```
``
```




During datagen, call `FunctionReference#functionReference` with the id of the referenced predicate file to construct a builder for this function.


## `minecraft:sequence`[​]()



This function runs other loot functions one after another.


```
``
```




During datagen, call `SequenceFunction#of` with the other functions to construct a builder for this condition.


## See Also[​]()





- [Item Modifiers]() on the [Minecraft Wiki]()
[PreviousLoot Conditions]()[NextRecipes]()


- [`minecraft:set_item`]()
- [`minecraft:set_count`]()
- [`minecraft:explosion_decay`]()
- [`minecraft:limit_count`]()
- [`minecraft:set_custom_data`]()
- [`minecraft:copy_custom_data`]()
- [`minecraft:set_components`]()
- [`minecraft:copy_components`]()
- [`minecraft:copy_state`]()
- [`minecraft:set_contents`]()
- [`minecraft:modify_contents`]()
- [`minecraft:set_loot_table`]()
- [`minecraft:set_name`]()
- [`minecraft:copy_name`]()
- [`minecraft:set_lore`]()
- [`minecraft:toggle_tooltips`]()
- [`minecraft:enchant_with_levels`]()
- [`minecraft:enchant_randomly`]()
- [`minecraft:set_enchantments`]()
- [`minecraft:enchanted_count_increase`]()
- [`minecraft:apply_bonus`]()
- [`minecraft:furnace_smelt`]()
- [`minecraft:set_damage`]()
- [`minecraft:set_attributes`]()
- [`minecraft:set_potion`]()
- [`minecraft:set_stew_effect`]()
- [`minecraft:set_ominous_bottle_amplifier`]()
- [`minecraft:exploration_map`]()
- [`minecraft:fill_player_head`]()
- [`minecraft:set_banner_pattern`]()
- [`minecraft:set_instrument`]()
- [`minecraft:set_fireworks`]()
- [`minecraft:set_firework_explosion`]()
- [`minecraft:set_book_cover`]()
- [`minecraft:set_written_book_pages`]()
- [`minecraft:set_writable_book_pages`]()
- [`minecraft:set_custom_model_data`]()
- [`minecraft:filtered`]()
- [`minecraft:reference`]()
- [`minecraft:sequence`]()
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
        

