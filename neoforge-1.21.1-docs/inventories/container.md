---
URL: https://docs.neoforged.net/docs/1.21.1/inventories/container
抓取时间: 2026-03-13 22:27:56
源站: NeoForge 1.21.1 官方文档
---






Containers | NeoForged docs




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


- [Containers]()
- [Capabilities]()
- [Data Storage]()
- [GUIs]()
- [Worldgen]()
- [Networking]()
- [Advanced Topics]()
- [Miscellaneous]()This is documentation for NeoForged **1.21 - 1.21.1**, which is no longer actively maintained.For up-to-date documentation, see the **[latest version]()** (1.21.11).


- []()
- Inventories & Transfers
- ContainersVersion: 1.21 - 1.21.1On this page

# Containers



Many systems in Minecraft, such as [block entities]() or entities, store items of some kind. To store items on something, Minecraft uses `Container` implementations.


The `Container` interface defines methods such as `#getItem`, `#setItem` and `#removeItem` that can be used to query and update the container. Since it is an interface, it does not actually contain a backing list or other data structure, that is up to the implementing system.


Due to this, `Container`s can not only be implemented on block entities, but any other class as well. Notable examples include entity inventories, as well as common modded [items]() such as backpacks.
warning

NeoForge provides the `ItemStackHandler` class as a replacement for `Container`s in many places. It should be used wherever possible in favor of `Container`, as it allows for cleaner interaction with other `Container`s/`ItemStackHandler`s.

The main reason this article exists is for reference in vanilla code, or if you are developing mods on multiple loaders. Always use `ItemStackHandler` in your own code if possible! Docs on that are a work in progress.


## Basic Container Implementation[​]()



Containers can be implemented in any way you like, so long as you satisfy the dictated methods (as with any other interface in Java). However, it is common to use a `NonNullList<ItemStack>` with a fixed length as a backing structure. Single-slot containers may also simply use an `ItemStack` field instead.


For example, a basic implementation of `Container` with a size of 27 slots (one chest) could look like this:


```
``
```




### `SimpleContainer`[​]()



The `SimpleContainer` class is a basic implementation of a container with some sprinkles on top, such as the ability to add `ContainerListener`s. It can be used if you need a container implementation that doesn't have any special requirements.


### `BaseContainerBlockEntity`[​]()



The `BaseContainerBlockEntity` class is the base class of many important block entities in Minecraft, such as chests and chest-like blocks, the various furnace types, hoppers, dispensers, droppers, brewing stands and a few others.


Aside from `Container`, it also implements the `MenuProvider` and `Nameable` interfaces:




- `Nameable` defines a few methods related to setting (custom) names and, aside from many block entities, is implemented by classes such as `Entity`. This uses the [`Component` system]().

- `MenuProvider`, on the other hand, defines the `#createMenu` method, which allows an [`AbstractContainerMenu`]() to be constructed from the container. This means that using this class is not desirable if you want a container without an associated GUI, for example in jukeboxes.



`BaseContainerBlockEntity` bundles all calls we would normally make to our `NonNullList<ItemStack>` through two methods `#getItems` and `#setItems`, drastically reducing the amount of boilerplate we need to write. An example implementation of a `BaseContainerBlockEntity` could look like this:


```
``
```




Keep in mind that this class is a `BlockEntity` and a `Container` at the same time. This means that you can use the class as a supertype for your block entity to get a functioning block entity with a pre-implemented container.


### `WorldlyContainer`[​]()



`WorldlyContainer` is a sub-interface of `Container` that allows accessing slots of the given `Container` by `Direction`. It is mainly intended for block entities that only expose parts of their container to a particular side. For example, this could be used by a machine that outputs to one side and takes inputs from all other sides, or vice-versa. A simple implementation of the interface could look like this:


```
``
```




## Using Containers[​]()



Now that we have created containers, let's use them!


Since there is a considerable overlap between `Container`s and `BlockEntity`s, containers are best retrieved by casting the block entity to `Container` if possible:


```
``
```




The container can then use the methods we mentioned before, for example:


```
``
```


warning

A container may throw an exception if trying to access a slot that is beyond its container size. Alternatively, they may return `ItemStack.EMPTY`, as is the case with (for example) `SimpleContainer`.


## `Container`s on `ItemStack`s[​]()



Until now, we mainly discussed `Container`s on `BlockEntity`s. However, they can also be applied to [`ItemStack`s]() using the `minecraft:container` [data component]():


```
``
```




And voilà, you have created an item-backed container! Call `new MyBackpackContainer(stack)` to create a container for a menu or other use case.
warning

Be aware that `Menu`s that directly interface with `Container`s must `#copy()` their `ItemStack`s when modifying them, as otherwise the immutability contract on data components is broken. To do this, NeoForge provides the `StackCopySlot` class for you.


## `Container`s on `Entity`s[​]()



`Container`s on `Entity`s are finicky: whether an entity has a container or not cannot be universally determined. It all depends on what entity you are handling, and as such can require a lot of special-casing.


If you are creating an entity yourself, there is nothing stopping you from implementing `Container` on it directly, though be aware that you will not be able to use superclasses such as `SimpleContainer` (since `Entity` is the superclass).


### `Container`s on `Mob`s[​]()



`Mob`s do not implement `Container`, but they implement the `EquipmentUser` interface (among others). This interface defines the methods `#setItemSlot(EquipmentSlot, ItemStack)`, `#getItemBySlot(EquipmentSlot)` and `#setDropChance(EquipmentSlot, float)`. While not related to `Container` code-wise, the functionality is quite similar: we associate slots, in this case equipment slots, with `ItemStack`s.


The most notable difference to `Container` is that there is no list-like order (though `Mob` uses `NonNullList<ItemStack>`s in the background). Access does not work through slot indices, but rather through the seven `EquipmentSlot` enum values: `MAINHAND`, `OFFHAND`, `FEET`, `LEGS`, `CHEST`, `HEAD`, and `BODY` (where `BODY` is used for horse and dog armor).


An example of interaction with the mob's "slots" would look something like this:


```
``
```




### `InventoryCarrier`[​]()



`InventoryCarrier` is an interface implemented by some living entities, such as villagers. It declares a method `#getInventory`, which returns a `SimpleContainer`. This interface is used by non-player entities that need an actual inventory instead of just the equipment slots provided by `EquipmentUser`.


### `Container`s on `Player`s (Player Inventory)[​]()



The player's inventory is implemented through the `Inventory` class, a class implementing `Container` as well as the `Nameable` interface mentioned earlier. An instance of that `Inventory` is then stored as a field named `inventory` on the `Player`, accessible via `Player#getInventory`. The inventory can be interacted with like any other container.


The inventory contents are stored in three `public final NonNullList<ItemStack>`s:




- The `items` list covers the 36 main inventory slots, including the nine hotbar slots (indices 0-8).

- The `armor` list is a list of length 4, containing armor for the `FEET`, `LEGS`, `CHEST`, and `HEAD`, in that order. This list uses `EquipmentSlot` accessors, similar to `Mob`s (see above).

- The `offhand` list contains only the offhand slot, i.e. has a length of 1.



When iterating over the inventory contents, it is recommended to iterate over `items`, then over `armor` and then over `offhand`, to be consistent with vanilla behavior.[PreviousTags]()[NextCapabilities]()


- [Basic Container Implementation]()


- [`SimpleContainer`]()
- [`BaseContainerBlockEntity`]()
- [`WorldlyContainer`]()
- [Using Containers]()
- [`Container`s on `ItemStack`s]()
- [`Container`s on `Entity`s]()


- [`Container`s on `Mob`s]()
- [`InventoryCarrier`]()
- [`Container`s on `Player`s (Player Inventory)]()Docs


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
        

