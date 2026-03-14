---
URL: https://docs.neoforged.net/docs/1.21.1/resources/server/loottables/custom
抓取时间: 2026-03-13 22:28:41
源站: NeoForge 1.21.1 官方文档
---






Custom Loot Objects | NeoForged docs




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
- Custom Loot ObjectsVersion: 1.21 - 1.21.1On this page

# Custom Loot Objects



Due to the complexity of the loot table system, there are several [registries]() at work, all of which can be used by a modder to add more behavior.


All loot table related registries follow a similar pattern. To add a new registry entry, you generally extend some class or implement some interface that holds your functionality. Then, you define a [codec]() for serialization, and register that codec to the corresponding registry, using `DeferredRegister` like normal. This goes along with the "one base object, many instances" approach most registries (for example also blocks/blockstates and items/item stacks) use.


## Custom Loot Entry Types[​]()



To create a custom loot entry type, extend `LootPoolEntryContainer` or one of its two direct subclasses, `LootPoolSingletonContainer` or `CompositeEntryBase`. For the sake of example, we want to create a loot entry type that returns the drops of a entity - this is purely for example purposes, in practice it would be more ideal to directly reference the other loot table. Let's start by creating our loot entry type class:


```
``
```




Next up, we create a `MapCodec` for our loot entry:


```
``
```




We then use this codec in registration:


```
``
```




Finally, in our loot entry class, we must override `getType()`:


```
``
```




## Custom Number Providers[​]()



To create a custom number provider, implement the `NumberProvider` interface. For the sake of example, let's assume we want to create a number provider that changes the sign of the provided number:


```
``
```




Like with custom loot entry types, we then use this codec in registration:


```
``
```




And similarly, in our number provider class, we must override `getType()`:


```
``
```




## Custom Level-Based Values[​]()



Custom `LevelBasedValue`s can be created by implementing the `LevelBasedValue` interface in a record. Again, for the sake of example, let's assume that we want to invert the output of another `LevelBasedValue`:


```
``
```




And again, we then use the codec in registration, though this time directly:


```
``
```




## Custom Loot Conditions[​]()



To get started, we create our loot item condition class that implements `LootItemCondition`. For the sake of example, let's assume we only want the condition to pass if the player killing the mob has a certain xp level:


```
``
```




We can register the condition type to the registry using the condition's codec:


```
``
```




After we have done that, we need to override `#getType` in our condition and return the registered type:


```
``
```




## Custom Loot Functions[​]()



To get started, we create our own class extending `LootItemFunction`. `LootItemFunction` extends `BiFunction<ItemStack, LootContext, ItemStack>`, so what we want is to use the existing item stack and the loot context to return a new, modified item stack. However, almost all loot functions don't directly extend `LootItemFunction`, but extend `LootItemConditionalFunction` instead. This class has built-in functionality for applying loot conditions to the function - the function is only applied if the loot conditions apply. For the sake of example, let's apply a random enchantment with a specified level to the item:


```
``
```




We can then register the function type to the registry using the function's codec:


```
``
```




After we have done that, we need to override `#getType` in our condition and return the registered type:


```
``
```

[PreviousLoot Tables]()[NextGlobal Loot Modifiers]()


- [Custom Loot Entry Types]()
- [Custom Number Providers]()
- [Custom Level-Based Values]()
- [Custom Loot Conditions]()
- [Custom Loot Functions]()Docs


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
        

