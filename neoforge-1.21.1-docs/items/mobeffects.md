---
URL: https://docs.neoforged.net/docs/1.21.1/items/mobeffects
抓取时间: 2026-03-13 22:28:47
源站: NeoForge 1.21.1 官方文档
---






Mob Effects & Potions | NeoForged docs




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


- [The Interaction Pipeline]()
- [Data Components]()
- [Tools & Armor]()
- [Mob Effects & Potions]()
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
- [Items]()
- Mob Effects & PotionsVersion: 1.21 - 1.21.1On this page

# Mob Effects & Potions



Status effects, sometimes known as potion effects and referred to in-code as `MobEffect`s, are effects that influence an entity every tick. This article explains how to use them, what the difference between an effect and a potion is, and how to add your own.


## Terminology[​]()





- A `MobEffect` affects an entity every tick. Like [blocks]() or [items](), `MobEffect`s are registry objects, meaning they must be [registered]() and are singletons.




- An **instant mob effect** is a special kind of mob effect that is designed to be applied for one tick. Vanilla has two instant effects, Instant Health and Instant Harming.



- A `MobEffectInstance` is an instance of a `MobEffect`, with a duration, amplifier and some other properties set (see below). `MobEffectInstance`s are to `MobEffect`s what [`ItemStack`s]() are to `Item`s.

- A `Potion` is a collection of `MobEffectInstance`s. Vanilla mainly uses potions for the four potion items (read on), however, they can be applied to any item at will. It is up to the item if and how the item then uses the potion set on it.

- A **potion item** is an item that is meant to have a potion set on it. This is an informal term, the vanilla `PotionItem` class has nothing to do with this (it refers to the "normal" potion item). Minecraft currently has four potion items: potions, splash potions, lingering potions, and tipped arrows; however more may be added by mods.



## `MobEffect`s[​]()



To create your own `MobEffect`, extend the `MobEffect` class:


```
``
```




Like all registry objects, `MobEffect`s must be registered, like so:


```
``
```




The `MobEffect` class also provides default functionality for adding attribute modifiers to affected entities. For example, the speed effect adds an attribute modifier for movement speed. Effect attribute modifiers are added like so:


```
``
```




### `InstantenousMobEffect`[​]()



If you want to create an instant effect, you can use the helper class `InstantenousMobEffect` instead of the regular `MobEffect` class, like so:


```
``
```




Then, register your effect like normal.


### Events[​]()



Many effects have their logic applied in other places. For example, the levitation effect is applied in the living entity movement handler. For modded `MobEffect`s, it often makes sense to apply them in an [event handler](). NeoForge also provides a few events related to effects:




- `MobEffectEvent.Applicable` is fired when the game checks whether a `MobEffectInstance` can be applied to an entity. This event can be used to deny or force adding the effect instance to the target.

- `MobEffectEvent.Added` is fired when the `MobEffectInstance` is added to the target. This event contains information about a previous `MobEffectInstance` that may have been present on the target.

- `MobEffectEvent.Expired` is fired when the `MobEffectInstance` expires, i.e. the timer goes to zero.

- `MobEffectEvent.Remove` is fired when the effect is removed from the entity through means other than expiring, e.g. through drinking milk or via commands.



## `MobEffectInstance`s[​]()



A `MobEffectInstance` is, simply put, an effect applied to an entity. Creating a `MobEffectInstance` is done by calling the constructor:


```
``
```




Several constructor overloads are available, omitting the last 1-5 parameters, respectively.
info

`MobEffectInstance`s are mutable. If you need a copy, call `new MobEffectInstance(oldInstance)`.


### Using `MobEffectInstance`s[​]()



A `MobEffectInstance` can be added to an entity like so:


```
``
```




Similarly, `MobEffectInstance` can also be removed from an entity. Since a `MobEffectInstance` overwrites pre-existing `MobEffectInstance`s of the same `MobEffect` on the entity, there can only ever be one `MobEffectInstance` per `MobEffect` and entity. As such, specifying the `MobEffect` suffices when removing:


```
``
```


info

`MobEffect`s can only be applied to `LivingEntity` or its subclasses, i.e. players and mobs. Things like items or thrown snowballs cannot be affected by `MobEffect`s.


## `Potion`s[​]()



`Potion`s are created by calling the constructor of `Potion` with the `MobEffectInstance`s you want the potion to have. For example:


```
``
```




Note that the parameter of `new Potion` is a vararg. This means that you can add as many effects as you want to the potion. This also means that it is possible to create empty potions, i.e. potions that don't have any effects. Simply call `new Potion()` and you're done! (This is how vanilla adds the `awkward` potion, by the way.)


The name of the potion can be passed as the first constructor argument. It is used for translating; for example, the long and strong potion variants in vanilla use this to have the same names as their base variant. The name is not required; if it is omitted, the name will be queried from the registry.


The `PotionContents` class offers various helper methods related to potion items. Potion item store their `PotionContents` via `DataComponent#POTION_CONTENTS`.


### Brewing[​]()



Now that your potion is added, potion items are available for your potion. However, there is no way to obtain your potion in survival, so let's change that!


Potions are traditionally made in the Brewing Stand. Unfortunately, Mojang does not provide [datapack]() support for brewing recipes, so we have to be a little old-fashioned and add our recipes through code via the `RegisterBrewingRecipesEvent` event. This is done like so:


```
``
```

[PreviousTools & Armor]()[NextBlock Entities]()


- [Terminology]()
- [`MobEffect`s]()


- [`InstantenousMobEffect`]()
- [Events]()
- [`MobEffectInstance`s]()


- [Using `MobEffectInstance`s]()
- [`Potion`s]()


- [Brewing]()Docs


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
        

