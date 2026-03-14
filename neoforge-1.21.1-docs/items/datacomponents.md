---
URL: https://docs.neoforged.net/docs/1.21.1/items/datacomponents
抓取时间: 2026-03-13 22:28:45
源站: NeoForge 1.21.1 官方文档
---






Data Components | NeoForged docs




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
- Data ComponentsVersion: 1.21 - 1.21.1On this page

# Data Components



Data components are key-value pairs within a map used to store data on an `ItemStack`. Each piece of data, such as firework explosions or tools, are stored as actual objects on the stack, making the values visible and operable without having to dynamically transform a general encoded instance (e.g., `CompoundTag`, `JsonElement`).


## `DataComponentType`[​]()



Each data component has an associated `DataComponentType<T>`, where `T` is the component value type. The `DataComponentType` represents a key to reference the stored component value along with some codecs to handle reading and writing to the disk and network, if desired.


A list of existing components can be found within `DataComponents`.


### Creating Custom Data Components[​]()



The component value associated with the `DataComponentType` must implement `hashCode` and `equals` and should be considered **immutable** when stored.
note

Component values can very easily be implemented using a record. Record fields are immutable and implement `hashCode` and `equals`.


```
``
```




A standard `DataComponentType` can be created via `DataComponentType#builder` and built using `DataComponentType.Builder#build`. The builder contains three settings: `persistent`, `networkSynchronized`, `cacheEncoding`.


`persistent` specifies the [`Codec`]() used to read and write the component value to disk. `networkSynchronized` specifies the `StreamCodec` used to read and write the component across the network. If `networkSynchronized` is not specified, then the `Codec` provided in `persistent` will be wrapped and used as the [`StreamCodec`]().
warning

Either `persistent` or `networkSynchronized` must be provided in the builder; otherwise, a `NullPointerException` will be thrown. If no data should be sent across the network, then set `networkSynchronized` to `StreamCodec#unit`, providing the default component value.


`cacheEncoding` caches the encoding result of the `Codec` such that any subsequent encodes uses the cached value if the component value hasn't changed. This should only be used if the component value is expected to rarely or never change.


`DataComponentType` are registry objects and must be [registered]().



- Latest
- [21.0.0, 21.1.48]

```
``
```



```
``
```




## The Component Map[​]()



All data components are stored within a `DataComponentMap`, using the `DataComponentType` as the key and the object as the value. `DataComponentMap` functions similarly to a read-only `Map`. As such, there are methods to `#get` an entry given its `DataComponentType` or provide a default if not present (via `#getOrDefault`).


```
``
```




### `PatchedDataComponentMap`[​]()



As the default `DataComponentMap` only provides methods for read-based operations, write-based operations are supported using the subclass `PatchedDataComponentMap`. This includes `#set`ting the value of a component or `#remove`ing it altogether.


`PatchedDataComponentMap` stores changes using a prototype and patch map. The prototype is a `DataComponentMap` that contains the default components and their
values this map should have. The patch map is a map of `DataComponentType`s to `Optional` values that contain the changes made to the default components.


```
``
```


danger

Both the prototype and patch map are part of the hash code for the `PatchedDataComponentMap`. As such, any component values within the map should be treated as **immutable**. Always call `#set` or one of its referring methods discussed below after modifying the value of a data component.


## The Component Holder[​]()



All instances that can hold data components implement `DataComponentHolder`. `DataComponentHolder` is effectively a delegate to the read-only methods within `DataComponentMap`.


```
``
```




### `MutableDataComponentHolder`[​]()



`MutableDataComponentHolder` is an interface provided by NeoForge to support write-based methods to the component map. All implementations within Vanilla and NeoForge store data components using a `PatchedDataComponentMap`, so the `#set` and `#remove` methods also have delegates with the same name.


In addition, `MutableDataComponentHolder` also provides an `#update` method which handles getting the component value or the provided default if none is set, operating on the value, and then setting it back to the map. The operator is either a `UnaryOperator`, which takes in the component value and returns the component value, or a `BiFunction`, which takes in the component value and another object and returns the component value.


```
``
```




## Adding Default Data Components to Items[​]()



Although data components are stored on an `ItemStack`, a map of default components can be set on an `Item` to be passed to the `ItemStack` as a prototype when constructed. A component can be added to the `Item` via `Item.Properties#component`.


```
``
```




If the data component should be added to an existing item that belongs to Vanilla or another mod, then `ModifyDefaultComponentsEvent` should be listened for on the [**mod event bus**](). The event provides the `modify` and `modifyMatching` methods which allows the `DataComponentPatch.Builder` to be modified for the associated items. The builder can either `#set` components or `#remove` existing components.


```
``
```




## Using Custom Component Holders[​]()



To create a custom data component holder, the holder object simply needs to implement `MutableDataComponentHolder` and implement the missing methods. The holder object must contain a field representing the `PatchedDataComponentMap` to implement the associated methods.


```
``
```




### `DataComponentPatch` and Codecs[​]()



To persist components to disk or send information across the network, the holder could send the entire `DataComponentMap`. However, this is generally a waste of information as any defaults will already be present wherever the data is sent to. So, instead, we use a `DataComponentPatch` to send the associated data. `DataComponentPatch`es only contain the patch information of the component map without any defaults. The patches are then applied to the prototype in the receiver's location.


A `DataComponentPatch` can be created from a `PatchedDataComponentMap` via `#patch`. Likewise, `PatchedDataComponentMap#fromPatch` can construct a `PatchedDataComponentMap` given the prototype `DataComponentMap` and a `DataComponentPatch`.


```
``
```




[Syncing the holder data across the network]() and reading/writing the data to disk must be done manually.[PreviousThe Interaction Pipeline]()[NextTools & Armor]()


- [`DataComponentType`]()


- [Creating Custom Data Components]()
- [The Component Map]()


- [`PatchedDataComponentMap`]()
- [The Component Holder]()


- [`MutableDataComponentHolder`]()
- [Adding Default Data Components to Items]()
- [Using Custom Component Holders]()


- [`DataComponentPatch` and Codecs]()Docs


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
        

