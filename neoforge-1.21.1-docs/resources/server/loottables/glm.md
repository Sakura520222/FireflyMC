---
URL: https://docs.neoforged.net/docs/1.21.1/resources/server/loottables/glm
抓取时间: 2026-03-13 22:28:39
源站: NeoForge 1.21.1 官方文档
---






Global Loot Modifiers | NeoForged docs




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
- Global Loot ModifiersVersion: 1.21 - 1.21.1On this page

# Global Loot Modifiers



Global Loot Modifiers, or GLMs for short, are a data-driven way to modify drops without the need to overwrite dozens or hundreds of vanilla loot tables, or to handle effects that would require interactions with another mod's loot tables without knowing what mods are loaded.


GLMs work by first rolling the associated [loot table]() and then applying the GLM to the result of rolling the table. GLMs are also stacking, rather than last-load-wins, to allow for multiple mods to modify the same loot table, this is similar to [tags]().


To register a GLM, you will need four things:




- A `global_loot_modifiers.json` file, located at `data/neoforge/loot_modifiers/global_loot_modifiers.json` (**not in your mod's namespace**). This file tells NeoForge what modifiers to apply, and in what order.

- A JSON file representing your loot modifier. This file contains all the data for your modification, allowing data packs to tweak your effect. It is located at `data/<namespace>/loot_modifiers/<path>.json`.

- A class that implements `IGlobalLootModifier` or extends `LootModifier` (which in turn implements `IGlobalLootModifier`). This class contains the code that makes the modifier work.

- A map [codec]() to encode and decode your loot modifier class. Usually, this is implemented as a `public static final` field in the loot modifier class.



## `global_loot_modifiers.json`[​]()



The `global_loot_modifiers.json` file tells NeoForge what modifiers to apply to loot tables. The file may contain two keys:




- `entries` is a list of modifiers that should be loaded. The [`ResourceLocation`]()s specified points to their associated entry within `data/<namespace>/loot_modifiers/<path>.json`. This list is ordered, meaning that modifiers will apply in the specified order, which is sometimes relevant when mod compatibility issues occur.

- `replace` denotes whether the modifiers should replace old ones (`true`) or simply add to the existing list (`false`). This works similar to the `replace` key in [tags](), however unlike tags, the key is required here. Generally, modders should always use `false` here; the ability to use `true` is directed at modpack or data pack developers.



Example usage:


```
``
```




## The Loot Modifier JSON[​]()



This file contains all values related to your modifier, for example chances to apply, what items to add, etc. It is recommended to avoid hard-coded values wherever possible so that data pack makers can adjust balance if they wish to. A loot modifier must contain at least two fields and may contain more, depending on the circumstances:




- The `type` field contains the registry name of the loot modifier.

- The `conditions` field is a list of loot table conditions for this modifier to activate.

- Additional properties may be required or optional, depending on the used codec.

tip

A common use case for GLMs is to add extra loot to one specific loot table. To achieve this, the [`neoforge:loot_table_id` condition]() can be used.


An example usage may look something like this:


```
``
```




## `IGlobalLootModifier` and `LootModifier`[​]()



To actually apply the loot modifier to the loot table, a `IGlobalLootModifier` implementation must be specified. In most cases, you will want to use the `LootModifier` subclass, which handles things like conditions for you. To get started, we extend `LootModifier` in our loot modifier class:


```
``
```


info

The returned list of drops from a modifier is fed into other modifiers in the order they are registered. As such, modified loot can and should be expected to be modified by another loot modifier.


## The Loot Modifier Codec[​]()



To tell the game about the existence of our loot modifier, we must define and [register]() a [codec]() for it. Reiterating on our previous example with the three fields, this would look something like this:


```
``
```




Then, we register the codec to the registry:


```
``
```




## Builtin Loot Modifiers[​]()



NeoForge provides a loot modifier out of the box for you to use:


### `neoforge:add_table`[​]()



This loot modifier rolls a second loot table and adds the results to the loot table the modifier is applied to.


```
``
```




## Datagen[​]()



GLMs can be [datagenned](). This is done by subclassing `GlobalLootModifierProvider`:


```
``
```




And like all data providers, you must register the provider to `GatherDataEvent`:


```
``
```

[PreviousCustom Loot Objects]()[NextLoot Conditions]()


- [`global_loot_modifiers.json`]()
- [The Loot Modifier JSON]()
- [`IGlobalLootModifier` and `LootModifier`]()
- [The Loot Modifier Codec]()
- [Builtin Loot Modifiers]()


- [`neoforge:add_table`]()
- [Datagen]()Docs


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
        

