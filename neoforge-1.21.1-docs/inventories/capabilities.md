---
URL: https://docs.neoforged.net/docs/1.21.1/inventories/capabilities
抓取时间: 2026-03-13 22:27:57
源站: NeoForge 1.21.1 官方文档
---






Capabilities | NeoForged docs




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
- CapabilitiesVersion: 1.21 - 1.21.1On this page

# Capabilities



Capabilities allow exposing features in a dynamic and flexible way without having to resort to directly implementing many interfaces.


In general terms, each capability provides a feature in the form of an interface.


NeoForge adds capability support to blocks, entities, and item stacks. This will be explained in more detail in the following sections.


## Why Use Capabilities?[​]()



Capabilities are designed to separate **what** a block, entity or item stack can do from **how** it does it. If you are wondering whether capabilities are the right tool for a job, ask yourself the following questions:




- Do I only care about **what** a block, entity or item stack can do, but not about **how** it does it?

- Is the **what**, the behavior, only available for some blocks, entities, or item stacks, but not all of them?

- Is the **how**, the implementation of that behavior, dependent on the specific block, entity or item stack?



Here are a few examples of good capability usage:




- *"I want my fluid container to be compatible with fluid containers from other mods, but I don't know the specifics of each fluid container."* - Yes, use the `IFluidHandler` capability.

- *"I want to count how many items are in some entity, but I do not know how the entity might store them."* - Yes, use the `IItemHandler` capability.

- *"I want to fill some item stack with power, but I do not know how the item stack might store it."* - Yes, use the `IEnergyStorage` capability.

- *"I want to apply some color to whatever block a player is currently targeting, but I do not know how the block will be transformed."* - Yes. NeoForge does not provide a capability to color blocks, but you can implement one yourself.



Here is an example of discouraged capability usage:




- *"I want to check if an entity is within the range of my machine."* - No, use a helper method instead.



## NeoForge-provided capabilities[​]()



NeoForge provides capabilities for the following three interfaces: `IItemHandler`, `IFluidHandler` and `IEnergyStorage`.


`IItemHandler` exposes an interface for handling inventory slots. The capabilities of type `IItemHandler` are:




- `Capabilities.ItemHandler.BLOCK`: automation-accessible inventory of a block (for chests, machines, etc).

- `Capabilities.ItemHandler.ENTITY`: inventory contents of an entity (extra player slots, mob/creature inventories/bags).

- `Capabilities.ItemHandler.ENTITY_AUTOMATION`: automation-accessible inventory of an entity (boats, minecarts, etc).

- `Capabilities.ItemHandler.ITEM`: contents of an item stack (portable backpacks and such).



`IFluidHandler` exposes an interface for handling fluid inventories. The capabilities of type `IFluidHandler` are:




- `Capabilities.FluidHandler.BLOCK`: automation-accessible fluid inventory of a block.

- `Capabilities.FluidHandler.ENTITY`: fluid inventory of an entity.

- `Capabilities.FluidHandler.ITEM`: fluid inventory of an item stack.
This capability is of the special `IFluidHandlerItem` type due to the way buckets hold fluids.



`IEnergyStorage` exposes an interface for handling energy containers. It is based on the RedstoneFlux API by TeamCoFH. The capabilities of type `IEnergyStorage` are:




- `Capabilities.EnergyStorage.BLOCK`: energy contained inside a block.

- `Capabilities.EnergyStorage.ENTITY`: energy containing inside an entity.

- `Capabilities.EnergyStorage.ITEM`: energy contained inside an item stack.



## Creating a capability[​]()



NeoForge supports capabilities for blocks, entities, and item stacks.


Capabilities allow looking up implementations of some APIs with some dispatching logic. The following kinds of capabilities are implemented in NeoForge:




- `BlockCapability`: capabilities for blocks and block entities; behavior depends on the specific `Block`.

- `EntityCapability`: capabilities for entities: behavior dependends on the specific `EntityType`.

- `ItemCapability`: capabilities for item stacks: behavior depends on the specific `Item`.

tip

For compatibility with other mods, we recommend using the capabilities provided by NeoForge in the `Capabilities` class if possible. Otherwise, you can create your own as described in this section.


Creating a capability is a single function call, and the resulting object should be stored in a `static final` field. The following parameters must be provided:




- The name of the capability.




- Creating a capability with the same name multiple times will always return the same object.

- Capabilities with different names are **completely independent**, and can be used for different purposes.



- The behavior type that is being queried. This is the `T` type parameter.

- The type for additional context in the query. This is the `C` type parameter.



For example, here is how a capability for side-aware block `IItemHandler`s might be declared:


```
``
```




A `@Nullable Direction` is so common for blocks that there is a dedicated helper:


```
``
```




If no context is required, `Void` should be used. There is also a dedicated helper for context-less capabilities:


```
``
```




For entities and item stacks, similar methods exist in `EntityCapability` and `ItemCapability` respectively.


## Querying capabilities[​]()



Once we have our `BlockCapability`, `EntityCapability`, or `ItemCapability` object in a static field, we can query a capability.


For entities and item stacks, we can try to find implementations of a capability with `getCapability`. If the result is `null`, there no implementation is available.


For example:


```
``
```




```
``
```




Block capabilities are used a bit differently because blocks without a block entity can have capabilities as well. The query is now performed on a `level`, with the `pos`ition that we are looking for as an additional parameter:


```
``
```




If the block entity and/or the block state is known, they can be passed to save on query time:


```
``
```




To give a more concrete example, here is how one might query an `IItemHandler` capability for a block, from the `Direction.NORTH` side:


```
``
```




## Block capability caching[​]()



When a capability is looked up, the system will perform the following steps under the hood:




- Fetch block entity and block state if they were not supplied.

- Fetch registered capability providers. (More on this below).

- Iterate the providers and ask them if they can provide the capability.

- One of the providers will return a capability instance, potentially allocating a new object.



The implementation is rather efficient, but for queries that are performed frequently, for example every game tick, these steps can take a significant amount of server time. The `BlockCapabilityCache` system provides a dramatic speedup for capabilities that are frequently queried at a given position.
tip

Generally, a `BlockCapabilityCache` will be created once and then stored in a field of the object performing frequent capability queries. When and where exactly you store the cache is up to you.


To create a cache, call `BlockCapabilityCache.create` with the capability to query, the level, the position, and the query context.


```
``
```




Querying the cache is then done with `getCapability()`:


```
``
```




**The cache is automatically cleared by the garbage collector, there is no need to unregister it.**


It is also possible to receive notifications when the capability object changes! This includes capabilities changing (`oldHandler != newHandler`), becoming unavailable (`null`) or becoming available again (not `null` anymore).


The cache then needs to be created with two additional parameters:




- A validity check, that is used to determine if the cache is still valid.




- In the simplest usage as a block entity field, `() -> !this.isRemoved()` will do.



- An invalidation listener, that is called when the capability changes.




- This is where you can react to capability changes, removals, or appearances.





```
``
```




## Block capability invalidation[​]()

info

Invalidation is exclusive to block capabilities. Entity and item stack capabilities cannot be cached and do not need to be invalidated.


To make sure that caches can correctly update their stored capability, **modders must call `level.invalidateCapabilities(pos)` whenever a capability changes, appears, or disappears**.


```
``
```




NeoForge already handles common cases such as chunk load/unloads and block entity creation/removal, but other cases need to be handled explicitly by modders. For example, modders must invalidate capabilities in the following cases:




- If a previously returned capability is no longer valid.

- If a capability-providing block (without a block entity) is placed or changes state, by overriding `onPlace`.

- If a capability-providing block (without a block entity) is removed, by overriding `onRemove`.



For a plain block example, refer to the `ComposterBlock.java` file.


For more information, refer to the javadoc of [`IBlockCapabilityProvider`]().


## Registering capabilities[​]()



A capability *provider* is what ultimately supplies a capability. A capability provider is a function that can either return a capability instance, or `null` if it cannot provide the capability. Providers are specific to:




- the given capability that they are providing for, and

- the block instance, block entity type, entity type, or item instance that they are providing for.



They need to be registered in the `RegisterCapabilitiesEvent`.


Block providers are registered with `registerBlock`. For example:


```
``
```




In general, registration will be specific to some block entity types, so the `registerBlockEntity` helper method is provided as well:


```
``
```


danger

If the capability previously returned by a block or block entity provider is no longer valid, *you must invalidate the caches** by calling `level.invalidateCapabilities(pos)`. Refer to the [invalidation section]() above for more information.


Entity registration is similar, using `registerEntity`:


```
``
```




Item registration is similar too. Note that the provider receives the stack:


```
``
```




## Registering capabilities for all objects[​]()



If for some reason you need to register a provider for all blocks, entities, or items, you will need to iterate the corresponding registry and register the provider for each object.


For example, NeoForge uses this system to register a fluid handler capability for all `BucketItem`s (excluding subclasses):


```
``
```




Providers are asked for a capability in the order that they are registered. Should you want to run before a provider that NeoForge already registers for one of your objects, register your `RegisterCapabilitiesEvent` handler with a higher priority.


For example:


```
``
```




See [`CapabilityHooks`]() for a list of the providers registered by NeoForge itself.[PreviousContainers]()[NextNamed Binary Tag (NBT)]()


- [Why Use Capabilities?]()
- [NeoForge-provided capabilities]()
- [Creating a capability]()
- [Querying capabilities]()
- [Block capability caching]()
- [Block capability invalidation]()
- [Registering capabilities]()
- [Registering capabilities for all objects]()Docs


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
        

