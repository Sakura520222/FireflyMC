---
URL: https://docs.neoforged.net/docs/1.21.1/resources/server/damagetypes
抓取时间: 2026-03-13 22:28:30
源站: NeoForge 1.21.1 官方文档
---






Damage Types & Damage Sources | NeoForged docs




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
- Damage Types & Damage SourcesVersion: 1.21 - 1.21.1On this page

# Damage Types & Damage Sources



A damage type denotes what kind of damage is being applied to an entity - physical damage, fire damage, drowning damage, magic damage, void damage, etc. The distinction into damage types is used for various immunities (e.g. blazes won't take fire damage), enchantments (e.g. blast protection will only protect against explosion damage), and many more use cases.


A damage type is a template for a damage source, so to speak. Or in other words, a damage source can be viewed as a damage type instance. Damage types exist as [`ResourceKey`s]() in code, but have all of their properties defined in data packs. Damage sources, on the other hand, are created as needed by the game, based off the values in the data pack files. They can hold additional context, for example the attacking entity.


## Creating Damage Types[​]()



To get started, you want to create your own `DamageType`. `DamageType`s are a [datapack registry](), and as such, new `DamageType`s are not registered in code, but are registered automatically when the corresponding files are added. However, we still need to provide some point for the code to get the damage sources from. We do so by specifying a [resource key]():


```
``
```




Now that we can reference it from code, let's specify some properties in the data file. Our data file is located at `data/examplemod/damage_type/example.json` (swap out `examplemod` and `example` for the mod id and the name of the resource location) and contains the following:


```
``
```


tip

The `scaling`, `effects` and `death_message_type` fields are internally controlled by the enums `DamageScaling`, `DamageEffects` and `DeathMessageType`, respectively. These enums can be [extended]() to add custom values if needed.


The same format is also used for vanilla's damage types, and pack developers can change these values if needed.


## Creating and Using Damage Sources[​]()



`DamageSource`s are usually created on the fly when `Entity#hurt` is called. Be aware that since damage types are a [datapack registry](), you will need a `RegistryAccess` to query them, which can be obtained via `Level#registryAccess`. To create a `DamageSource`, call the `DamageSource` constructor with up to four parameters:


```
``
```


warning

`DamageSources#source`, which is a wrapper around `new DamageSource`, flips the second and third parameters (direct entity and causing entity). Make sure you are supplying the correct values to the correct parameters.


If `DamageSource`s have no entity or position context whatsoever, it makes sense to cache them in a field. For `DamageSource`s that do have entity or position context, it is common to add helper methods, like so:


```
``
```


tip

Vanilla's `DamageSource` factories can be found in `DamageSources`, and vanilla's `DamageType` resource keys can be found in `DamageTypes`.


The first and foremost use case for damage sources is `Entity#hurt`. This method is called whenever an entity is receiving damage. To hurt an entity with our own damage type, we simply call `Entity#hurt` ourselves:


```
``
```




Other damage type-specific behavior, such as invulnerability checks, is often run through damage type [tags](). These are both added by Minecraft and NeoForge and can be found under `DamageTypeTags` and `Tags.DamageTypes`, respectively.


## Datagen[​]()



*For more info, see [Data Generation for Datapack Registries]().*


Damage type JSON files can be [datagenned](). Since damage types are a datapack registry, we add a `DatapackBuiltinEntriesProvider` to the `GatherDataEvent` and put our damage types in the `RegistrySetBuilder`:


```
``
```

[PreviousData Load Conditions]()[NextData Maps]()


- [Creating Damage Types]()
- [Creating and Using Damage Sources]()
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
        

