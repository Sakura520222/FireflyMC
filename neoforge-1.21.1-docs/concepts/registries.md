---
URL: https://docs.neoforged.net/docs/1.21.1/concepts/registries
抓取时间: 2026-03-13 22:27:45
源站: NeoForge 1.21.1 官方文档
---






Registries | NeoForged docs




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


- [Registries]()
- [Sides]()
- [Events]()
- [Blocks]()
- [Items]()
- [Block Entities]()
- [Resources]()
- [Inventories & Transfers]()
- [Data Storage]()
- [GUIs]()
- [Worldgen]()
- [Networking]()
- [Advanced Topics]()
- [Miscellaneous]()This is documentation for NeoForged **1.21 - 1.21.1**, which is no longer actively maintained.For up-to-date documentation, see the **[latest version]()** (1.21.11).


- []()
- Concepts
- RegistriesVersion: 1.21 - 1.21.1On this page

# Registries



Registration is the process of taking the objects of a mod (such as [items](), [blocks](), entities, etc.) and making them known to the game. Registering things is important, as without registration the game will simply not know about these objects, which will cause unexplainable behaviors and crashes.


A registry is, simply put, a wrapper around a map that maps registry names (read on) to registered objects, often called registry entries. Registry names must be unique within the same registry, but the same registry name may be present in multiple registries. The most common example for this are blocks (in the `BLOCKS` registry) that have an item form with the same registry name (in the `ITEMS` registry).


Every registered object has a unique name, called its registry name. The name is represented as a [`ResourceLocation`](). For example, the registry name of the dirt block is `minecraft:dirt`, and the registry name of the zombie is `minecraft:zombie`. Modded objects will of course not use the `minecraft` namespace; their mod id will be used instead.


## Vanilla vs. Modded[​]()



To understand some of the design decisions that were made in NeoForge's registry system, we will first look at how Minecraft does this. We will use the block registry as an example, as most other registries work the same way.


Registries generally register [singletons](). This means that all registry entries exist exactly once. For example, all stone blocks you see throughout the game are actually the same stone block, displayed many times. If you need the stone block, you can get it by referencing the registered block instance.


Minecraft registers all blocks in the `Blocks` class. Through the `register` method, `Registry#register()` is called, with the block registry at `BuiltInRegistries.BLOCK` being the first parameter. After all blocks are registered, Minecraft performs various checks based on the list of blocks, for example the self check that verifies that all blocks have a model loaded.


The main reason all of this works is that `Blocks` is classloaded early enough by Minecraft. Mods are not automatically classloaded by Minecraft, and thus workarounds are needed.


## Methods for Registering[​]()



NeoForge offers two ways to register objects: the `DeferredRegister` class, and the `RegisterEvent`. Note that the former is a wrapper around the latter, and is recommended in order to prevent mistakes.


### `DeferredRegister`[​]()



We begin by creating our `DeferredRegister`:


```
``
```




We can then add our registry entries as static final fields (see [the article on Blocks]() for what parameters to add in `new Block()`):


```
``
```




The class `DeferredHolder<R, T extends R>` holds our object. The type parameter `R` is the type of the registry we are registering to (in our case `Block`). The type parameter `T` is the type of our supplier. Since we directly register a `Block` in this example, we provide `Block` as the second parameter. If we were to register an object of a subclass of `Block`, for example `SlabBlock`, we would provide `SlabBlock` here instead.


`DeferredHolder<R, T extends R>` is a subclass of `Supplier<T>`. To get our registered object when we need it, we can call `DeferredHolder#get()`. The fact that `DeferredHolder` extends `Supplier` also allows us to use `Supplier` as the type of our field. That way, the above code block becomes the following:


```
``
```




Be aware that a few places explicitly require a `Holder` or `DeferredHolder` and will not just accept any `Supplier`. If you need either of those two, it is best to change the type of your `Supplier` back to `Holder` or `DeferredHolder` as necessary.


Finally, since the entire system is a wrapper around registry events, we need to tell the `DeferredRegister` to attach itself to the registry events as needed:


```
``
```


info

There are specialized variants of `DeferredRegister`s for blocks and items that provide helper methods, called [`DeferredRegister.Blocks`]() and [`DeferredRegister.Items`](), respectively.


### `RegisterEvent`[​]()



`RegisterEvent` is the second way to register objects. This [event]() is fired for each registry, after the mod constructors (since those are where `DeferredRegister`s register their internal event handlers) and before the loading of configs. `RegisterEvent` is fired on the mod event bus.


```
``
```




## Querying Registries[​]()



Sometimes, you will find yourself in situations where you want to get a registered object by a given id. Or, you want to get the id of a certain registered object. Since registries are basically maps of ids (`ResourceLocation`s) to distinct objects, i.e. a reversible map, both of these operations work:


```
``
```




If you just want to check for the presence of an object, this is also possible, though only with keys:


```
``
```




As the last example shows, this is possible with any mod id, and thus a perfect way to check if a certain item from another mod exists.


Finally, we can also iterate over all entries in a registry, either over the keys or over the entries (entries use the Java `Map.Entry` type):


```
``
```


note

Query operations always use vanilla `Registry`s, not `DeferredRegister`s. This is because `DeferredRegister`s are merely registration utilities.
danger

Query operations are only safe to use after registration has finished. **DO NOT QUERY REGISTRIES WHILE REGISTRATION IS STILL ONGOING!**


## Custom Registries[​]()



Custom registries allow you to specify additional systems that addon mods for your mod may want to plug into. For example, if your mod were to add spells, you could make the spells a registry and thus allow other mods to add spells to your mod, without you having to do anything else. It also allows you to do some things, such as syncing the entries, automatically.


Let's start by creating the [registry key]() and the registry itself:


```
``
```




Then, tell the game that the registry exists by registering them to the root registry in `NewRegistryEvent`:


```
``
```




You can now register new registry contents like with any other registry, through both `DeferredRegister` and `RegisterEvent`:


```
``
```




## Datapack Registries[​]()



A datapack registry (also known as a dynamic registry or, after its main use case, worldgen registry) is a special kind of registry that loads data from [datapack]() JSONs (hence the name) at world load, instead of loading them when the game starts. Default datapack registries most notably include most worldgen registries, among a few others.


Datapack registries allow their contents to be specified in JSON files. This means that no code (other than [datagen]() if you don't want to write the JSON files yourself) is necessary. Every datapack registry has a [`Codec`]() associated with it, which is used for serialization, and each registry's id determines its datapack path:




- Minecraft's datapack registries use the format `data/yourmodid/registrypath` (for example `data/yourmodid/worldgen/biome`, where `worldgen/biome` is the registry path).

- All other datapack registries (NeoForge or modded) use the format `data/yourmodid/registrynamespace/registrypath` (for example `data/yourmodid/neoforge/biome_modifier`, where `neoforge` is the registry namespace and `biome_modifier` is the registry path).



Datapack registries can be obtained from a `RegistryAccess`. This `RegistryAccess` can be retrieved by calling `ServerLevel#registryAccess()` if on the server, or `Minecraft.getInstance().getConnection()#registryAccess()` if on the client (the latter only works if you are actually connected to a world, as otherwise the connection will be null). The result of these calls can then be used like any other registry to get specific elements, or to iterate over the contents.


### Custom Datapack Registries[​]()



Custom datapack registries do not require a `Registry` to be constructed. Instead, they just need a registry key and at least one [`Codec`]() to (de-)serialize its contents. Reiterating on the spells example from before, registering our spell registry as a datapack registry looks something like this:


```
``
```




### Data Generation for Datapack Registries[​]()



Since writing all the JSON files by hand is both tedious and error-prone, NeoForge provides a [data provider]() to generate the JSON files for you. This works for both built-in and your own datapack registries.


First, we create a `RegistrySetBuilder` and add our entries to it (one `RegistrySetBuilder` can hold entries for multiple registries):


```
``
```




The `bootstrap` lambda parameter is what we actually use to register our objects. It has the type `BootstrapContext`. To register an object, we call `#register` on it, like so:


```
``
```




The `BootstrapContext` can also be used to lookup entries from another registry if needed:


```
``
```




Finally, we use our `RegistrySetBuilder` in an actual data provider, and register that data provider to the event:


```
``
```

[PreviousVersioning]()[NextSides]()


- [Vanilla vs. Modded]()
- [Methods for Registering]()


- [`DeferredRegister`]()
- [`RegisterEvent`]()
- [Querying Registries]()
- [Custom Registries]()
- [Datapack Registries]()


- [Custom Datapack Registries]()
- [Data Generation for Datapack Registries]()Docs


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
        

