---
URL: https://docs.neoforged.net/docs/1.21.1/blockentities/ber
抓取时间: 2026-03-13 22:28:18
源站: NeoForge 1.21.1 官方文档
---






BlockEntityRenderer | NeoForged docs




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


- [BlockEntityRenderer]()
- [Resources]()
- [Inventories & Transfers]()
- [Data Storage]()
- [GUIs]()
- [Worldgen]()
- [Networking]()
- [Advanced Topics]()
- [Miscellaneous]()This is documentation for NeoForged **1.21 - 1.21.1**, which is no longer actively maintained.For up-to-date documentation, see the **[latest version]()** (1.21.11).


- []()
- [Block Entities]()
- BlockEntityRendererVersion: 1.21 - 1.21.1On this page

# BlockEntityRenderer



A `BlockEntityRenderer`, often abbreviated as BER, is used to render [blocks]() in a way that cannot be represented with a [static baked model]() (JSON, OBJ, others). For example, this could be used to dynamically render container contents of a chest-like block. A block entity renderer requires the block to have a [`BlockEntity`](), even if the block does not store any data otherwise.


To create a BER, create a class that inherits from `BlockEntityRenderer`. It takes a generic argument specifying the block's `BlockEntity` class, which is used as a parameter type in the BER's `render` method.


```
``
```




Only one BER may exist for a given `BlockEntityType<?>`. Therefore, values that are specific to a single block entity instance should be stored in that block entity instance, rather than the BER itself.


When you have created your BER, you must also register it to `EntityRenderersEvent.RegisterRenderers`, an [event]() fired on the [mod event bus]():


```
``
```




In the event that you do not need the BER provider context in your BER, you can also remove the constructor:


```
``
```




## `BlockEntityWithoutLevelRenderer`[​]()



`BlockEntityWithoutLevelRenderer`, colloquially known as BEWLR, is an adaptation of the regular `BlockEntityRenderer` for special [item]() rendering (hence "without level", as items do not have level context). Its overall purpose is the same: do special rendering for cases where static models aren't enough.


To add a BEWLR, create a class that extends `BlockEntityWithoutLevelRenderer` and overrides `#renderByItem`. It also requires some additional constructor setup:


```
``
```




Keep in mind that, like with BERs, there is only one instance of your BEWLR. Stack-specific properties should therefore be stored in the stack, not the BEWLR.


Unlike BERs, we do not register BEWLRs directly. Instead, we register an instance of `IClientItemExtensions` to the `RegisterClientExtensionsEvent`. `IClientItemExtensions` is an interface that allows us to specify a number of rendering-related behaviors on items, such as (but not limited to) a BEWLR. As such, our implementation of that interface could look like so:


```
``
```




And then, we can register our `IClientItemExtensions` to the event:


```
``
```


info

`IClientItemExtensions` are generally expected to be treated as singletons. Do not construct them outside `RegisterClientExtensionsEvent`![PreviousBlock Entities]()[NextResources]()


- [`BlockEntityWithoutLevelRenderer`]()Docs


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
        

