---
URL: https://docs.neoforged.net/docs/1.21.1/resources/client/models/datagen
抓取时间: 2026-03-13 22:28:22
源站: NeoForge 1.21.1 官方文档
---






Model Datagen | NeoForged docs




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


- [I18n and L10n]()
- [Models]()


- [Baked Models]()
- [Model Datagen]()
- [Custom Model Loaders]()
- [Particles]()
- [Sounds]()
- [Textures]()
- [Server]()
- [Inventories & Transfers]()
- [Data Storage]()
- [GUIs]()
- [Worldgen]()
- [Networking]()
- [Advanced Topics]()
- [Miscellaneous]()This is documentation for NeoForged **1.21 - 1.21.1**, which is no longer actively maintained.For up-to-date documentation, see the **[latest version]()** (1.21.11).


- []()
- [Resources]()
- Client
- [Models]()
- Model DatagenVersion: 1.21 - 1.21.1On this page

# Model Datagen



Like most JSON data, block and item models can be [datagenned](). Since some things are common between item and block models, so is some of the datagen code.


## Model Datagen Classes[​]()



### `ModelBuilder`[​]()



Every model starts out as a `ModelBuilder` of some sort - usually a `BlockModelBuilder` or an `ItemModelBuilder`, depending on what you are generating. It contains all the properties of the model: its parent, its textures, its elements, its transforms, its loader, etc. Each of the properties can be set by a method:
MethodEffect`#texture(String key, ResourceLocation texture)`Adds a texture variable with the given key and the given texture location. Has an overload where the second parameter is a `String`.`#renderType(ResourceLocation renderType)`Sets the render type. Has an overload where the parameter is a `String`. For a list of valid values, see the `RenderType` class.`#ao(boolean ao)`Sets whether to use [ambient occlusion]() or not.`#guiLight(GuiLight light)`Sets the GUI light. May be `GuiLight.FRONT` or `GuiLight.SIDE`.`#element()`Adds a new `ElementBuilder` (equivalent to adding a new [element]() to the model). Returns said `ElementBuilder` for further modification.`#transforms()`Returns the builder's `TransformVecBuilder`, used for setting the `display` on a model.`#customLoader(BiFunction customLoaderFactory)`Using the given factory, makes this model use a [custom loader](), and thus, a custom loader builder. This changes the builder type, and as such may use different methods, depending on the loader's implementation. NeoForge provides a few custom loaders out of the box, see the linked article for more info (including datagen).
tip

While elaborate and complex models can be created through datagen, it is recommended to instead use modeling software such as [Blockbench]() to create more complex models and then have the exported models be used, either directly or as parents for other models.


### `ModelProvider`[​]()



Both block and item model datagen utilize subclasses of `ModelProvider`, named `BlockModelProvider` and `ItemModelProvider`, respectively. While item model datagen directly extends `ItemModelProvider`, block model datagen uses the `BlockStateProvider` base class, which has an internal `BlockModelProvider` that can be accessed via `BlockStateProvider#models()`. Additionally, `BlockStateProvider` also has its own internal `ItemModelProvider`, accessible via `BlockStateProvider#itemModels()`. The most important part of `ModelProvider` is the `getBuilder(String path)` method, which returns a `BlockModelBuilder` (or `ItemModelBuilder`) at the given location.


However, `ModelProvider` also contains various helper methods. The most important helper method is probably `withExistingParent(String name, ResourceLocation parent)`, which returns a new builder (via `getBuilder(name)`) and sets the given `ResourceLocation` as model parent. Two other very common helpers are `mcLoc(String name)`, which returns a `ResourceLocation` with the namespace `minecraft` and the given name as path, and `modLoc(String name)`, which does the same but with the provider's mod id (so usually your mod id) instead of `minecraft`. Furthermore, it provides various helper methods that are shortcuts for `#withExistingParent` for common things such as slabs, stairs, fences, doors, etc.


### `ModelFile`[​]()



Finally, the last important class is `ModelFile`. A `ModelFile` is an in-code representation of a model JSON on disk. `ModelFile` is an abstract class and has two inner subclasses `ExistingModelFile` and `UncheckedModelFile`. An `ExistingModelFile`'s existence is verified using an `ExistingFileHelper`, while an `UncheckedModelFile` is assumed to be existent without further checking. In addition, a `ModelBuilder` is considered to be a `ModelFile` as well.


## Block Model Datagen[​]()



Now, to actually generate blockstate and block model files, extend `BlockStateProvider` and override the `registerStatesAndModels()` method. Note that block models will always be placed in the `models/block` subfolder, but references are relative to `models` (i.e. they must always be prefixed with `block/`). In most cases, it makes sense to choose from one of the many predefined helper methods:


```
``
```




Additionally, helpers for the following common block models exist in `BlockStateProvider`:




- Stairs

- Slabs

- Buttons

- Pressure Plates

- Signs

- Fences

- Fence Gates

- Walls

- Panes

- Doors

- Trapdoors



In some cases, the blockstates don't need special casing, but the models do. For this case, the `BlockModelProvider`, accessible via `BlockStateProvider#models()`, provides a few additional helpers, all of which accept a name as the first parameter and most of which are in some way related to full cubes. They will typically be used as model file parameters for e.g. `simpleBlock`. The helpers include supporting methods for the ones in `BlockStateProvider`, as well as:




- `withExistingParent`: Already mentioned before, this method returns a new model builder with the given parent. The parent must either already exist or be created before the model.

- `getExistingFile`: Performs a lookup in the model provider's `ExistingFileHelper`, returning the corresponding `ModelFile` if present and throwing an `IllegalStateException` otherwise.

- `singleTexture`: Accepts a parent and a single texture location, returning a model with the given parent, and with the texture variable `texture` set to the given texture location.

- `sideBottomTop`: Accepts a parent and three texture locations, returning a model with the given parent and the side, bottom and top textures set to the three texture locations.

- `cube`: Accepts six texture resource locations for the six sides, returning a full cube model with the six sides set to the six textures.

- `cubeAll`: Accepts a texture location, returning a full cube model with the given texture applied to all six sides. A mix between `singleTexture` and `cube`, if you will.

- `cubeTop`: Accepts two texture locations, returning a full cube model with the first texture applied to the sides and the bottom, and the second texture applied to the top.

- `cubeBottomTop`: Accepts three texture locations, returning a full cube model with the side, bottom and top textures set to the three texture locations. A mix between `cube` and `sideBottomTop`, if you will.

- `cubeColumn` and `cubeColumnHorizontal`: Accepts two texture locations, returning a "standing" or "laying" pillar cube model with the side and end textures set to the two texture locations. Used by `BlockStateProvider#logBlock`, `BlockStateProvider#axisBlock` and their variants.

- `orientable`: Accepts three texture locations, returning a cube with a "front" texture. The three texture locations are the side, front and top texture, respectively.

- `orientableVertical`: Variant of `orientable` that omits the top parameter, instead using the side parameter as well.

- `orientableWithBottom`: Variant of `orientable` that has a fourth parameter for a bottom texture between the front and top parameter.

- `crop`: Accepts a texture location, returning a crop-like model with the given texture, as used by the four vanilla crops.

- `cross`: Accepts a texture location, returning a cross model with the given texture, as used by flowers, saplings and many other foliage blocks.

- `torch`: Accepts a texture location, returning a torch model with the given texture.

- `wall_torch`: Accepts a texture location, returning a wall torch model with the given texture (wall torches are separate blocks from standing torches).

- `carpet`: Accepts a texture location, returning a carpet model with the given texture.



Finally, don't forget to register your block state provider to the event:


```
``
```




### `ConfiguredModel.Builder`[​]()



If the default helpers won't do it for you, you can also directly build model objects using a `ConfiguredModel.Builder` and then use them in a `VariantBlockStateBuilder` to build a `variants` blockstate file, or in a `MultiPartBlockStateBuilder` to build a `multipart` blockstate file:


```
``
```




## Item Model Datagen[​]()



Generating item models is considerably simpler, which is mainly due to the fact that we operate directly on an `ItemModelProvider` instead of using an intermediate class like `BlockStateProvider`, which is of course because item models don't have an equivalent to blockstate files and are instead used directly.


Similar to above, we create a class and have it extend the base provider, in this case `ItemModelProvider`. Since we are directly in a subclass of `ModelProvider`, all `models()` calls become `this` (or are omitted).


```
``
```




And like all data providers, don't forget to register your provider to the event:


```
``
```

[PreviousBaked Models]()[NextCustom Model Loaders]()


- [Model Datagen Classes]()


- [`ModelBuilder`]()
- [`ModelProvider`]()
- [`ModelFile`]()
- [Block Model Datagen]()


- [`ConfiguredModel.Builder`]()
- [Item Model Datagen]()Docs


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
        

