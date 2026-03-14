---
URL: https://docs.neoforged.net/docs/1.21.1/resources/server/conditions
抓取时间: 2026-03-13 22:28:28
源站: NeoForge 1.21.1 官方文档
---






Data Load Conditions | NeoForged docs




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
- Data Load ConditionsVersion: 1.21 - 1.21.1On this page

# Data Load Conditions



Sometimes, it is desirable to disable or enable certain features if another mod is present, or if any mod adds another type of ore, etc. For these use cases, NeoForge adds data load conditions. These were originally called recipe conditions, since recipes were the original use case for this system, but it has since been extended to other systems. This is also why some of the built-in conditions are limited to items.


Most JSON files can optionally declare a `neoforge:conditions` block in the root, which will be evaluated before the data file is actually loaded. Loading will continue if and only if all conditions pass, otherwise the data file will be ignored. (The exception to this rule are [loot tables](), which will be replaced with an empty loot table instead.)


```
``
```




For example, if we want to only load our file if a mod with id `examplemod` is present, our file would look something like this:


```
``
```


note

Most vanilla files have been patched to use conditions using the `ConditionalCodec` wrapper. However, not all systems, especially those not using a [codec](), can use conditions. To find out whether a data file can use conditions, check the backing codec definition.


## Built-In Conditions[​]()



### `neoforge:true` and `neoforge:false`[​]()



These consist of no data and return the expected value.


```
``
```


tip

Using the `neoforge:false` condition very cleanly allows disabling any data file. Simply place a file with the following contents at the needed location:

```
``
```



Disabling files this way will **not** cause log spam.


### `neoforge:not`[​]()



This condition accepts another condition and inverts it.


```
``
```




### `neoforge:and` and `neoforge:or`[​]()



These conditions accept the condition(s) being operated upon and apply the expected logic. There is no limit to the amount of accepted conditions.


```
``
```




### `neoforge:mod_loaded`[​]()



This condition returns true if a mod with the given mod id is loaded, and false otherwise.


```
``
```




### `neoforge:item_exists`[​]()



This condition returns true if an item with the given registry name has been registered, and false otherwise.


```
``
```




### `neoforge:tag_empty`[​]()



This condition returns true if the given item [tag]() is empty, and false otherwise.


```
``
```




## Creating Custom Conditions[​]()



Custom conditions can be created by implementing `ICondition` and its `#test(IContext)` method, as well as creating a [map codec]() for it. The `IContext` parameter in `#test` has access to some parts of the game state. Currently, this only allows you to query tags from registries. Some objects with conditions may be loaded earlier than tags, in which case the context will be `IContext.EMPTY` and not contain any tag information at all.


For example, let's assume we want to reimplement the `tag_empty` condition, but for entity type tags instead of item tags, then our condition would look something like this:


```
``
```




Conditions are a registry of codecs. As such, we need to [register]() our codec, like so:


```
``
```




And then, we can use our condition in some data file (assuming we registered the condition under the `examplemod` namespace):


```
``
```




## Datagen[​]()



While any datapack JSON file can use load conditions, only a few [data providers]() have been modified to be able to generate them. These include:




- [`RecipeProvider`]() (via `RecipeOutput#withConditions`), including recipe advancements

- `JsonCodecProvider` and its subclass `SpriteSourceProvider`

- [`DataMapProvider`]()

- [`GlobalLootModifierProvider`]()



For the conditions themselves, the `IConditionBuilder` interface provides static helpers for each of the built-in condition types that return the corresponding `ICondition`s.[PreviousAdvancements]()[NextDamage Types & Damage Sources]()


- [Built-In Conditions]()


- [`neoforge:true` and `neoforge:false`]()
- [`neoforge:not`]()
- [`neoforge:and` and `neoforge:or`]()
- [`neoforge:mod_loaded`]()
- [`neoforge:item_exists`]()
- [`neoforge:tag_empty`]()
- [Creating Custom Conditions]()
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
        

