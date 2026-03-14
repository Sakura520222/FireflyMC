---
URL: https://docs.neoforged.net/docs/1.21.1/misc/resourcelocation
抓取时间: 2026-03-13 22:28:03
源站: NeoForge 1.21.1 官方文档
---






Resource Locations | NeoForged docs




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
- [Worldgen]()
- [Networking]()
- [Advanced Topics]()
- [Miscellaneous]()


- [Configuration]()
- [Debug Profiler]()
- [Game Tests]()
- [Key Mappings]()
- [Resource Locations]()
- [NeoForge Update Checker]()This is documentation for NeoForged **1.21 - 1.21.1**, which is no longer actively maintained.For up-to-date documentation, see the **[latest version]()** (1.21.11).


- []()
- Miscellaneous
- Resource LocationsVersion: 1.21 - 1.21.1On this page

# Resource Locations



`ResourceLocation`s are one of the most important things in Minecraft. They are used as keys in [registries](), as identifiers for data or resource files, as references to models in code, and in a lot of other places. A `ResourceLocation` consists of two parts: a namespace and a path, separated by a `:`.


The namespace denotes what mod, resource pack or datapack the location refers to. For example, a mod with the mod id `examplemod` will use the `examplemod` namespace. Minecraft uses the `minecraft` namespace. Extra namespaces can be defined at will simply by creating a corresponding data folder, this is usually done by datapacks to keep their logic separate from the point where they integrate with vanilla.


The path is a reference to whatever object you want, inside your namespace. For example, `minecraft:cow` is a reference to something named `cow` in the `minecraft` namespace - usually this location would be used to get the cow entity from the entity registry. Another example would be `examplemod:example_item`, which would probably be used to get your mod's `example_item` from the item registry.


`ResourceLocation`s may only contain lowercase letters, digits, underscores, dots and hyphens. Paths may additionally contain forward slashes. Note that due to Java module restrictions, mod ids may not contain hyphens, which by extension means that mod namespaces may not contain hyphens either (they are still permitted in paths).
info

A `ResourceLocation` on its own says nothing about what kind of objects we are using it for. Objects named `minecraft:dirt` exist in multiple places, for example. It is up to whatever receives the `ResourceLocation` to associate an object with it.


A new `ResourceLocation` can be created by calling `ResourceLocation.fromNamespaceAndPath("examplemod", "example_item")` or `ResourceLocation.parse("examplemod:example_item")`. If `withDefaultNamespace` is used, the string will be used as the path, and `minecraft` will be used as the namespace. So for example, `ResourceLocation.withDefaultNamespace("example_item")` will result in `minecraft:example_item`.


The namespace and path of a `ResourceLocation` can be retrieved using `ResourceLocation#getNamespace()` and `#getPath()`, respectively, and the combined form can be retrieved through `ResourceLocation#toString`.


`ResourceLocation`s are immutable. All utility methods on `ResourceLocation`, such as `withPrefix` or `withSuffix`, return a new `ResourceLocation`.


## Resolving `ResourceLocation`s[​]()



Some places, for example registries, use `ResourceLocation`s directly. Some other places, however, will resolve the `ResourceLocation` as needed. For example:




- `ResourceLocation`s are used as identifiers for GUI background. For example, the furnace GUI uses the resource location `minecraft:textures/gui/container/furnace.png`. This maps to the file `assets/minecraft/textures/gui/container/furnace.png` on disk. Note that the `.png` suffix is required in this resource location.

- `ResourceLocation`s are used as identifiers for block models. For example, the block model of dirt uses the resource location `minecraft:block/dirt`. This maps to the file `assets/minecraft/models/block/dirt.json` on disk. Note that the `.json` suffix is not required here. Note as well that this resource location automatically maps into the `models` subfolder.

- `ResourceLocation`s are used as identifiers for recipes. For example, the iron block crafting recipe uses the resource location `minecraft:iron_block`. This maps to the file `data/minecraft/recipe/iron_block.json` on disk. Note that the `.json` suffix is not required here. Note as well that this resource location automatically maps into the `recipe` subfolder.



Whether the `ResourceLocation` expects a file suffix, or what exactly the resource location resolves to, depends on the use case.


## `ModelResourceLocation`s[​]()



`ModelResourceLocation`s are a special kind of resource location that includes a third part, called the variant. Minecraft uses these mainly to differentiate between different variants of models, where the different variants are used in different display contexts (for example with tridents, which have different models in first person, third person and inventories). The variant is always `inventory` for items, and the comma-delimited string of property-value pairs for blockstates (for example `facing=north,waterlogged=false`, empty for blocks with no blockstate properties).


The variant is appended to the regular resource location, along with a `#`. For example, the full name of the diamond sword's item model is `minecraft:diamond_sword#inventory`. However, in most contexts, the `inventory` variant can be omitted.


`ModelResourceLocation` is a [client only]() class. This means that servers referencing this class will crash with a `NoClassDefFoundError`.


## `ResourceKey`s[​]()



`ResourceKey`s combine a registry id with a registry name. An example would be a registry key with the registry id `minecraft:item` and the registry name `minecraft:diamond_sword`. Unlike a `ResourceLocation`, `ResourceKey`s actually refer to a unique element, thus being able to clearly identify an element. They are most commonly used in contexts where many different registries come in contact with one another. A common use case are datapacks, especially worldgen.


A new `ResourceKey` can be created through the static method `ResourceKey#create(ResourceKey<? extends Registry<T>>, ResourceLocation)`. The second parameter here is the registry name, while the first parameter is what is known as a registry key. Registry keys are a special kind of `ResourceKey` whose registry is the root registry (i.e. the registry of all other registries). A registry key can be created via `ResourceKey#createRegistryKey(ResourceLocation)` with the desired registry's id.


`ResourceKey`s are interned at creation. This means that comparing by reference equality (`==`) is possible and encouraged, but their creation is comparatively expensive.[PreviousKey Mappings]()[NextNeoForge Update Checker]()


- [Resolving `ResourceLocation`s]()
- [`ModelResourceLocation`s]()
- [`ResourceKey`s]()Docs


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
        

