---
URL: https://docs.neoforged.net/docs/1.21.1/resources/server/tags
抓取时间: 2026-03-13 22:28:31
源站: NeoForge 1.21.1 官方文档
---






Tags | NeoForged docs




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
- TagsVersion: 1.21 - 1.21.1On this page

# Tags



A tag is, simply put, a list of registered objects of the same type. They are loaded from data files and can be used for membership checks. For example, crafting sticks will accept any combination of wooden planks (items tagged with `minecraft:planks`). Tags are often distinguished from "regular" objects by prefixing them with a `#` (for example `#minecraft:planks`, but `minecraft:oak_planks`).


Any [registry]() can have tag files - while blocks and items are the most common use cases, other registries such as fluids, entity types or damage types often utilize tags as well. You can also create your own tags if you need them.


Tags are located at `data/<tag_namespace>/tags/<registry_path>/<tag_path>.json` for Minecraft registries, and `data/<tag_namespace>/tags/<registry_namespace>/<registry_path>/<tag_path>.json` for non-Minecraft registries. For example, to modify the `minecraft:planks` item tag, you would place your tag file at `data/minecraft/tags/item/planks.json`.
info

Unlike most other NeoForge data files, NeoForge-added tags do generally not use the `neoforge` namespace. Instead, they use the `c` namespace (e.g. `c:ingots/gold`). This is because the tags are unified between NeoForge and the Fabric mod loader, at the request of many modders developing on multiple loaders.

There are a few exceptions to this rule for some tags that tie closely into NeoForge systems. This includes many [damage type]() tags, for example.


Overriding tag files is generally additive instead of replacing. This means that if two datapacks specify tag files with the same id, the contents of both files will be merged (unless otherwise specified). This behavior sets tags apart from most other data files, which instead replace any and all existing values.


## Tag File Format[​]()



Tag files have the following syntax:


```
``
```




## Finding and Naming Tags[​]()



When you try to find an existing tag, it is generally recommended to follow these steps:




- Have a look at Minecraft's tags and see if the tag you're looking for is there. Minecraft's tags can be found in `BlockTags`, `ItemTags`, `EntityTypeTags` etc.

- If not, have a look at NeoForge's tags and see if the tag you're looking for is there. NeoForge's tags can be found in `Tags.Blocks`, `Tags.Items`, `Tags.EntityTypes`, etc.

- Otherwise, assume the tag is not specified in Minecraft or NeoForge, and thus you need to create your own tag.



When creating your own tag, you should ask yourself the following questions:




- Does this modify my mod's behavior? If yes, the tag should be in your mod's namespace. (This is common e.g. for my-thing-can-spawn-on-this-block kind of tags.)

- Would other mods want to use this tag as well? If yes, the tag should be in the `c` namespace. (This is common e.g. for new metals or gems.)

- Otherwise, use your mod's namespace.



Naming the tag itself also has some conventions to follow:




- Use the plural form. E.g.: `minecraft:planks`, `c:ingots`.

- Use folders for multiple objects of the same type, and an overall tag for each folder. E.g.: `c:ingots/iron`, `c:ingots/gold`, and `c:ingots` containing both. (Note: This is a NeoForge convention, Minecraft does not follow this convention for most tags.)



## Using Tags[​]()



To reference tags in code, you must create a `TagKey<T>`, where `T` is the type of tag (`Block`, `Item`, `EntityType<?>`, etc.), using a [registry key]() and a [resource location]():


```
``
```


warning

Since `TagKey` is a record, its constructor is public. However, the constructor should not be used directly, as doing so can lead to various issues, for example when looking up tag entries.


We can then use our tag to perform various operations on it. Let's start with the most obvious one: check whether an object is in the tag. The following examples will assume block tags, but the functionality is the exact same for every type of tag (unless otherwise specified):


```
``
```




Since this is a very verbose statement, especially when used often, `BlockState` and `ItemStack` - the two most common users of the tag system - each define a `#is` helper method, used like so:


```
``
```




If needed, we can also get ourselves a stream of tag entries, like so:


```
``
```




For performance reasons, it is recommended to cache these tag entries in a field, invalidating them when tags are reloaded (which can be listened for using `TagsUpdatedEvent`). This can be done like so:


```
``
```




## Datagen[​]()



Like many other JSON files, tags can be [datagenned](). Each kind of tag has its own datagen base class - one class for block tags, one for item tags, etc. -, and as such, we need one class for each kind of tag as well. All of these classes extend from the `TagsProvider<T>` base class, with `T` again being the type of the tag (`Block`, `Item`, etc.) The following table shows a list of tag providers for different objects:
TypeTag Provider Class`BannerPattern``BannerPatternTagsProvider``Biome``BiomeTagsProvider``Block``BlockTagsProvider``CatVariant``CatVariantTagsProvider``DamageType``DamageTypeTagsProvider``Enchantment``EnchantmentTagsProvider``EntityType``EntityTypeTagsProvider``FlatLevelGeneratorPreset``FlatLevelGeneratorPresetTagsProvider``Fluid``FluidTagsProvider``GameEvent``GameEventTagsProvider``Instrument``InstrumentTagsProvider``Item``ItemTagsProvider``PaintingVariant``PaintingVariantTagsProvider``PoiType``PoiTypeTagsProvider``Structure``StructureTagsProvider``WorldPreset``WorldPresetTagsProvider`


Of note is the `IntrinsicHolderTagsProvider<T>` class, which is a subclass of `TagsProvider<T>` and a common superclass for `BlockTagsProvider`, `ItemTagsProvider`, `FluidTagsProvider`, `EntityTypeTagsProvider`, and `GameEventTagsProvider`. These classes (from now on called intrinsic providers for simplicity) have some additional functionality for generation that will be outlined in a moment.


For the sake of example, let's assume that we want to generate block tags. (All other classes work the same with their respective tag types.)


```
``
```




This example results in the following tag JSON:


```
``
```




Like all data providers, add each tag provider to the `GatherDataEvent`:


```
``
```




`ItemTagsProvider` has an additional helper method called `#copy`. It is intended for the common use case of item tags mirroring block tags:


```
``
```




### Custom Tag Providers[​]()



To create a custom tag provider for a custom [registry](), or for a vanilla or NeoForge registry that doesn't have a tag provider by default, you can also create custom tag providers like so (using recipe type tags as an example):


```
``
```




If desirable and applicable, you can also extend `IntrinsicHolderTagsProvider<T>` instead of `TagsProvider<T>`, allowing you to pass in objects directly rather than just their resource keys. This additionally requires a function parameter that returns a resource key for a given object. Using attribute tags as an example:


```
``
```


info

`TagsProvider` also exposes the `#getOrCreateRawBuilder` method, returning a `TagBuilder`. A `TagBuilder` allows adding raw `ResourceLocation`s to a tag, which can be useful in some scenarios. The `TagsProvider.TagAppender<T>` class, which is returned by `TagsProvider#tag`, is simply a wrapper around `TagBuilder`.[PreviousIngredients]()[NextContainers]()


- [Tag File Format]()
- [Finding and Naming Tags]()
- [Using Tags]()
- [Datagen]()


- [Custom Tag Providers]()Docs


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
        

