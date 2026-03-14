---
URL: https://docs.neoforged.net/docs/1.21.1/worldgen/biomemodifier
抓取时间: 2026-03-13 22:28:10
源站: NeoForge 1.21.1 官方文档
---






Biome Modifiers | NeoForged docs




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
- [Inventories & Transfers]()
- [Data Storage]()
- [GUIs]()
- [Worldgen]()


- [Biome Modifiers]()
- [Networking]()
- [Advanced Topics]()
- [Miscellaneous]()This is documentation for NeoForged **1.21 - 1.21.1**, which is no longer actively maintained.For up-to-date documentation, see the **[latest version]()** (1.21.11).


- []()
- Worldgen
- Biome ModifiersVersion: 1.21 - 1.21.1On this page

# Biome Modifiers



Biome Modifiers are a data-driven system that allows for changing many aspects of a biome, including the ability to inject or remove PlacedFeatures, add or remove mob spawns, change the climate, and adjust foliage and water color. NeoForge provides several default biome modifiers that cover the majority of use cases for both players and modders.


### Recommended Section To Read:[​]()





- 


Players or pack developers:




- [Applying Biome Modifiers]()

- [Built-in Neoforge Biome Modifiers]()



- 


Modders doing simple additions or removal biome modifications:




- [Applying Biome Modifiers]()

- [Built-in Neoforge Biome Modifiers]()

- [Datagenning Biome Modifiers]()



- 


Modders who want to do custom or complex biome modifications:




- [Applying Biome Modifiers]()

- [Creating Custom Biome Modifiers]()

- [Datagenning Biome Modifiers]()





## Applying Biome Modifiers[​]()



To have NeoForge load a biome modifier JSON file into the game, the file will need to be under `data/<modid>/neoforge/biome_modifier/<path>.json` folder in the mod's resources, or in a [Datapack](). Then, once NeoForge loads the biome modifier, it will read its instructions and apply the described modifications to all target biomes when the world is loaded up. Pre-existing biome modifiers from mods can be overridden by datapacks having a new JSON file at the exact same location and name.


The JSON file can be created by hand following the examples in the '[Built-in NeoForge Biome Modifiers]()' section or be datagenned as shown in the '[Datagenning Biome Modifiers]()' section.


## Built-in Biome Modifiers[​]()



These biome modifiers are registered by NeoForge for anyone to use.


### None[​]()



This biome modifier has no operation and will do no modification. Pack makers and players can use this in a datapack to disable mods' biome modifiers by overriding their biome modifier JSONs with the JSON below.



- JSON
- Datagen

```
``
```



```
``
```




### Add Features[​]()



This biome modifier type adds `PlacedFeature`s (such as trees or ores) to biomes so that they can spawn during world generation. The modifier takes in the biome id or tag of the biomes the features are added to, a `PlacedFeature` id or tag to add to the selected biomes, and the [`GenerationStep.Decoration`]() the features will be generated within.



- JSON
- Datagen

```
``
```



```
``
```


warning

Care should be taken when adding vanilla `PlacedFeature`s to biomes, as doing so may cause what is known as a feature cycle violation (two biomes having the same two features in their feature lists, but in different orders within the same `GenerationStep`), leading to a crash. For similar reasons, you should not use the same `PlacedFeature` in more than one biome modifier.

Vanilla `PlacedFeature`s can be referenced in biome JSONs or added via biome modifiers, but should not be used in both. If you still need to add them this way, making a copy of the vanilla `PlacedFeature` under your own namespace is the easiest solution to avoid these problems.


### Remove Features[​]()



This biome modifier type removes features (such as trees or ores) from biomes so that they will no longer spawn during world generation. The modifier takes in the biome id or tag of the biomes the features are removed from, a `PlacedFeature` id or tag to remove from the selected biomes, and the [`GenerationStep.Decoration`]()s that the features will be removed from.



- JSON
- Datagen

```
``
```



```
``
```




### Add Spawns[​]()



This biome modifier type adds entity spawns to biomes. The modifier takes in the biome id or tag of the biomes the entity spawns are added to, and the `SpawnerData` of the entities to add. Each `SpawnerData` contains the entity id, the spawn weight, and the minimum/maximum number of entities to spawn at a given time.
note

If you are a modder adding a new entity, make sure the entity has a spawn restriction registered to `RegisterSpawnPlacementsEvent`. Spawn restrictions are used to make entities spawn on surfaces or in water safely. If you do not register a spawn restriction, your entity could spawn in mid-air, fall and die.



- JSON
- Datagen

```
``
```



```
``
```




### Remove Spawns[​]()



This biome modifier type removes entity spawns from biomes. The modifier takes in the biome id or tag of the biomes the entity spawns are removed from, and the `EntityType` id or tag of the entities to remove.



- JSON
- Datagen

```
``
```



```
``
```




### Add Spawn Costs[​]()



Allows for adding new spawn costs to biomes. Spawn costs are a newer way of making mobs spawn spread out in a biome to reduce clustering. It works by having the entities give off a `charge` that surrounds them and adds up with other entities' `charge`. When spawning a new entity, the spawning algorithm looks for a spot where the total `charge` field at the location multiplied by the spawning entity's `charge` value is less than the spawning entity's `energy_budget`. This is an advanced way of spawning mobs, so it is a good idea to reference the Soul Sand Valley biome (which is the most prominent user of this system) for existing values to borrow.


The modifier takes in the biome id or tag of the biomes the spawn costs are added to, the `EntityType` id or tag of the entity types to add spawn costs for, and the `MobSpawnSettings.MobSpawnCost` of the entity. The `MobSpawnCost` contains the energy budget, which indicates the maximum number of entities that can spawn in a location based on the charge provided for each entity spawned.
note

If you are a modder adding a new entity, make sure the entity has a spawn restriction registered to `RegisterSpawnPlacementsEvent`.



- JSON
- Datagen

```
``
```



```
``
```




### Remove Spawn Costs[​]()



Allows for removing a spawn cost from a biome. Spawn costs are a newer way of making mobs spawn spread out in a biome to reduce clustering. The modifier takes in the biome id or tag of the biomes the spawn costs are removed from, and the `EntityType` id or tag of the entities to remove the spawn cost for.



- JSON
- Datagen

```
``
```



```
``
```




### Add Legacy Carvers[​]()



This biome modifier type allows adding carver caves and ravines to biomes. These are what was used for cave generation before the Caves and Cliffs update. It CANNOT add noise caves to biomes, because noise caves are a part of certain noise-based chunk generator systems and not actually tied to biomes.



- JSON
- Datagen

```
``
```



```
``
```




### Removing Legacy Carvers[​]()



This biome modifier type allows removing carver caves and ravines from biomes. These are what was used for cave generation before the Caves and Cliffs update. It CANNOT remove noise caves from biomes, because noise caves are baked into the dimension's noise settings system and not actually tied to biomes.



- JSON
- Datagen

```
``
```



```
``
```




### Available Values for Decoration Steps[​]()



The `step` or `steps` fields in many of the aforementioned JSONs are referring to the `GenerationStep.Decoration` enum. This enum has the steps listed out in the following order, which is the same order that the game uses for generating during worldgen. Try to put features in the step that makes the most sense for them.
StepDescription`raw_generation`First to run. This is used for special terrain-like features such as Small End Islands.`lakes`Dedicated to spawning pond-like feature such as Lava Lakes.`local_modifications`For modifications to terrain such as Geodes, Icebergs, Boulders, or Dripstone.`underground_structures`Used for small underground structure-like features such as Dungeons or Fossils.`surface_structures`For small surface only structure-like features such as Desert Wells.`strongholds`Dedicated for Stronghold structures. No feature is added here in unmodified Minecraft.`underground_ores`The step for all Ores and Veins to be added to. This includes Gold, Dirt, Granite, etc.`underground_decoration`Used typically for decorating caves. Dripstone Cluster and Sculk Vein are here.`fluid_springs`The small Lavafalls and Waterfalls come from features in this stage.`vegetal_decoration`Nearly all plants (flowers, trees, vines, and more) are added to this stage.`top_layer_modification`Last to run. Used for placing Snow and Ice on the surface of cold biomes.


## Creating Custom Biome Modifiers[​]()



### The `BiomeModifier` Implementation[​]()



Under the hood, Biome Modifiers are made up of three parts:




- The [datapack registered]() `BiomeModifier` used to modify the biome builder.

- The [statically registered]() `MapCodec` that encodes and decodes the modifiers.

- The JSON that constructs the `BiomeModifier`, using the registered id of the `MapCodec` as the indexable type.



A `BiomeModifier` contains two methods: `#modify` and `#codec`. `modify` takes in a `Holder` of the current `Biome`, the current `BiomeModifier.Phase`, and the builder of the biome to modify. Every `BiomeModifier` is called once per `Phase` to organize when certain modifications to the biome should occur:
PhaseDescription`BEFORE_EVERYTHING`A catch-all for everything that needs to run before the standard phases.`ADD`Adding features, mob spawns, etc.`REMOVE`Removing features, mob spawns, etc.`MODIFY`Modifying single values (e.g., climate, colors).`AFTER_EVERYTHING`A catch-all for everything that needs to run after the standard phases.


All `BiomeModifier`s contain a `type` key that references the id of the `MapCodec` used for the `BiomeModifier`. The `codec` takes in the `MapCodec` that encodes and decodes the modifiers. This `MapCodec` is [statically registered](), with its id used as the `type` of the `BiomeModifier`.


```
``
```




## Datagenning Biome Modifiers[​]()



A `BiomeModifier` JSON can be created through [data generation]() by passing a `RegistrySetBuilder` to `DatapackBuiltinEntriesProvider`. The JSON will be placed at `data/<modid>/neoforge/biome_modifier/<path>.json`.


For more information on how `RegistrySetBuilder` and `DatapackBuiltinEntriesProvider` work, please see the article on [Data Generation for Datapack Registries]().


```
``
```




This will then result in the following JSON being created:


```
``
```

[PreviousScreens]()[NextNetworking]()


- [Recommended Section To Read:]()
- [Applying Biome Modifiers]()
- [Built-in Biome Modifiers]()


- [None]()
- [Add Features]()
- [Remove Features]()
- [Add Spawns]()
- [Remove Spawns]()
- [Add Spawn Costs]()
- [Remove Spawn Costs]()
- [Add Legacy Carvers]()
- [Removing Legacy Carvers]()
- [Available Values for Decoration Steps]()
- [Creating Custom Biome Modifiers]()


- [The `BiomeModifier` Implementation]()
- [Datagenning Biome Modifiers]()Docs


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
        

