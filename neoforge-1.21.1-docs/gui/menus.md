---
URL: https://docs.neoforged.net/docs/1.21.1/gui/menus
抓取时间: 2026-03-13 22:27:52
源站: NeoForge 1.21.1 官方文档
---






Menus | NeoForged docs




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


- [Menus]()
- [Screens]()
- [Worldgen]()
- [Networking]()
- [Advanced Topics]()
- [Miscellaneous]()This is documentation for NeoForged **1.21 - 1.21.1**, which is no longer actively maintained.For up-to-date documentation, see the **[latest version]()** (1.21.11).


- []()
- GUIs
- MenusVersion: 1.21 - 1.21.1On this page

# Menus



Menus are one type of backend for Graphical User Interfaces, or GUIs; they handle the logic involved in interacting with some represented data holder. Menus themselves are not data holders. They are views which allow to user to indirectly modify the internal data holder state. As such, a data holder should not be directly coupled to any menu, instead passing in the data references to invoke and modify.


## `MenuType`[​]()



Menus are created and removed dynamically and as such are not registry objects. As such, another factory object is registered instead to easily create and refer to the *type* of the menu. For a menu, these are `MenuType`s.


`MenuType`s must be [registered]().


### `MenuSupplier`[​]()



A `MenuType` is created by passing in a `MenuSupplier` and a `FeatureFlagSet` to its constructor. A `MenuSupplier` represents a function which takes in the id of the container and the inventory of the player viewing the menu, and returns a newly created [`AbstractContainerMenu`]().


```
``
```


note

The container identifier is unique for an individual player. This means that the same container id on two different players will represent two different menus, even if they are viewing the same data holder.


The `MenuSupplier` is usually responsible for creating a menu on the client with dummy data references used to store and interact with the synced information from the server data holder.


### `IContainerFactory`[​]()



If additional information is needed on the client (e.g. the position of the data holder in the world), then the subclass `IContainerFactory` can be used instead. In addition to the container id and the player inventory, this also provides a `RegistryFriendlyByteBuf` which can store additional information that was sent from the server. A `MenuType` can be created using an `IContainerFactory` via `IMenuTypeExtension#create`.


```
``
```




## `AbstractContainerMenu`[​]()



All menus are extended from `AbstractContainerMenu`. A menu takes in two parameters, the [`MenuType`](), which represents the type of the menu itself, and the container id, which represents the unique identifier of the menu for the current accessor.
caution

The player can only have 100 unique menus open at once.


Each menu should contain two constructors: one used to initialize the menu on the server and one used to initialize the menu on the client. The constructor used to initialize the menu on the client is the one supplied to the `MenuType`. Any fields that the server menu constructor contains should have some default for the client menu constructor.


```
``
```




Each menu implementation must implement two methods: `#stillValid` and [`#quickMoveStack`]().


### `#stillValid` and `ContainerLevelAccess`[​]()



`#stillValid` determines whether the menu should remain open for a given player. This is typically directed to the static `#stillValid` which takes in a `ContainerLevelAccess`, the player, and the `Block` this menu is attached to. The client menu must always return `true` for this method, which the static `#stillValid` does default to. This implementation checks whether the player is within eight blocks of where the data storage object is located.


A `ContainerLevelAccess` supplies the current level and block position within an enclosed scope. When constructing the menu on the server, a new access can be created by calling `ContainerLevelAccess#create`. The client menu constructor can pass in `ContainerLevelAccess#NULL`, which will do nothing.


```
``
```




### Data Synchronization[​]()



Some data needs to be present on both the server and the client to display to the player. To do this, the menu implements a basic layer of data synchronization such that whenever the current data does not match the data last synced to the client. For players, this is checked every tick.


Minecraft supports two forms of data synchronization by default: `ItemStack`s via `Slot`s and integers via `DataSlot`s. `Slot`s and `DataSlot`s are views which hold references to data storages that can be be modified by the player in a screen, assuming the action is valid. These can be added to a menu within the constructor through `#addSlot` and `#addDataSlot`.
note

Since `Container`s used by `Slot`s are deprecated by NeoForge in favor of using the [`IItemHandler` capability](), the rest of the explanation will revolve around using the capability variant: `SlotItemHandler`.


A `SlotItemHandler` contains four parameters: the `IItemHandler` representing the inventory the stacks are within, the index of the stack this slot is specifically representing, and the x and y position of where the top-left position of the slot will render on the screen relative to `AbstractContainerScreen#leftPos` and `#topPos`. The client menu constructor should always supply an empty instance of an inventory of the same size.


In most cases, any slots the menu contains is first added, followed by the player's inventory, and finally concluded with the player's hotbar. To access any individual `Slot` from the menu, the index must be calculated based upon the order of which slots were added.


A `DataSlot` is an abstract class which should implement a getter and setter to reference the data stored in the data storage object. The client menu constructor should always supply a new instance via `DataSlot#standalone`.


These, along with slots, should be recreated every time a new menu is initialized.
note

Although a `DataSlot` stores an integer, it is effectively limited to a **short** (-32768 to 32767) because of how it sends the value across the network. The 16 high-order bits of the integer are ignored.

NeoForge patches the packet to provide the full integer to the client.


```
``
```




#### `ContainerData`[​]()



If multiple integers need to be synced to the client, a `ContainerData` can be used to reference the integers instead. This interface functions as an index lookup such that each index represents a different integer. `ContainerData`s can also be constructed in the data object itself if the `ContainerData` is added to the menu through `#addDataSlots`. The method creates a new `DataSlot` for the amount of data specified by the interface. The client menu constructor should always supply a new instance via `SimpleContainerData`.


```
``
```




#### `#quickMoveStack`[​]()



`#quickMoveStack` is the second method that must be implemented by any menu. This method is called whenever a stack has been shift-clicked, or quick moved, out of its current slot until the stack has been fully moved out of its previous slot or there is no other place for the stack to go. The method returns a copy of the stack in the slot being quick moved.


Stacks are typically moved between slots using `#moveItemStackTo`, which moves the stack into the first available slot. It takes in the stack to be moved, the first slot index (inclusive) to try and move the stack to, the last slot index (exclusive), and whether to check the slots from first to last (when `false`) or from last to first (when `true`).


Across Minecraft implementations, this method is fairly consistent in its logic:


```
``
```




## Opening a Menu[​]()



Once a menu type has been registered, the menu itself has been finished, and a [screen]() has been attached, a menu can then be opened by the player. Menus can be opened by calling `IPlayerExtension#openMenu` on the logical server. The method takes in the `MenuProvider` of the server side menu and optionally a `Consumer<RegistryFriendlyByteBuf>` if extra data needs to be synced to the client.
note

`IPlayerExtension#openMenu` with the `Consumer<RegistryFriendlyByteBuf>` parameter should only be used if a menu type was created using an [`IContainerFactory`]().


#### `MenuProvider`[​]()



A `MenuProvider` is an interface that contains two methods: `#createMenu`, which creates the server instance of the menu, and `#getDisplayName`, which returns a component containing the title of the menu to pass to the [screen](). The `#createMenu` method contains three parameter: the container id of the menu, the inventory of the player who opened the menu, and the player who opened the menu.


A `MenuProvider` can easily be created using `SimpleMenuProvider`, which takes in a method reference to create the server menu and the title of the menu.


```
``
```




### Common Implementations[​]()



Menus are typically opened on a player interaction of some kind (e.g. when a block or entity is right-clicked).


#### Block Implementation[​]()



Blocks typically implement a menu by overriding `BlockBehaviour#useWithoutItem`. If on the [logical client](), the [interaction]() returns `InteractionResult#SUCCESS`. Otherwise, it opens the menu and returns `InteractionResult#CONSUME`.


The `MenuProvider` should be implemented by overriding `BlockBehaviour#getMenuProvider`. Vanilla methods use this to view the menu in spectator mode.


```
``
```


note

This is the simplest way to implement the logic, not the only way. If you want the block to only open the menu under certain conditions, then some data will need to be synced to the client beforehand to return `InteractionResult#PASS` or `#FAIL` if the conditions are not met.


#### Mob Implementation[​]()



Mobs typically implement a menu by overriding `Mob#mobInteract`. This is done similarly to the block implementation with the only difference being that the `Mob` itself should implement `MenuProvider` to support spectator mode viewing.


```
``
```


note

Once again, this is the simplest way to implement the logic, not the only way.[PreviousSaved Data]()[NextScreens]()


- [`MenuType`]()


- [`MenuSupplier`]()
- [`IContainerFactory`]()
- [`AbstractContainerMenu`]()


- [`#stillValid` and `ContainerLevelAccess`]()
- [Data Synchronization]()
- [Opening a Menu]()


- [Common Implementations]()Docs


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
        

