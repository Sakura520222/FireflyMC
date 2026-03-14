---
URL: https://docs.neoforged.net/docs/1.21.1/resources/client/models/modelloaders
抓取时间: 2026-03-13 22:28:17
源站: NeoForge 1.21.1 官方文档
---






Custom Model Loaders | NeoForged docs




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
- Custom Model LoadersVersion: 1.21 - 1.21.1On this page

# Custom Model Loaders



A model is simply a shape. It can be a cube, a collection of cubes, a collection of triangles, or any other geometrical shape (or collection of geometrical shape). For most contexts, it is not relevant how a model is defined, as everything will end up as a `BakedModel` in memory anyway. As such, NeoForge adds the ability to register custom model loaders that can transform any model you want into a `BakedModel` for the game to use.


The entry point for a block model remains the model JSON file. However, you can specify a `loader` field in the root of the JSON that will swap out the default loader for your own loader. A custom model loader may ignore all fields the default loader requires.


## Builtin Model Loaders[​]()



Besides the default model loader, NeoForge offers several builtin loaders, each serving a different purpose.


### Composite Model[​]()



A composite model can be used to specify different model parts in the parent and only apply some of them in a child. This is best illustrated by an example. Consider the following parent model at `examplemod:example_composite_model`:


```
``
```




Then, we can disable and enable individual parts in a child model of `examplemod:example_composite_model`:


```
``
```




To [datagen]() this model, use the custom loader class `CompositeModelBuilder`.


### Dynamic Fluid Container Model[​]()



The dynamic fluid container model, also called dynamic bucket model after its most common use case, is used for items that represent a fluid container (such as a bucket or a tank) and want to show the fluid within the model. This only works if there is a fixed amount of fluids (e.g. only lava and powder snow) that can be used, use a [`BlockEntityWithoutLevelRenderer`]() instead if the fluid is arbitrary.


```
``
```




Very often, dynamic fluid container models will directly use the bucket model. This is done by specifying the `neoforge:item_bucket` parent model, like so:


```
``
```




To [datagen]() this model, use the custom loader class `DynamicFluidContainerModelBuilder`. Be aware that for legacy support reasons, this class also provides a method to set the `apply_tint` property, which is no longer used.


### Elements Model[​]()



An elements model consists of block model [elements]() and an optional [root transform](). Intended mainly for usage outside regular model rendering, for example within a [BER]().


```
``
```




### Empty Model[​]()



An empty model just renders nothing at all.


```
``
```




### Item Layer Model[​]()



Item layer models are a variant of the standard `item/generated` model that offer the following additional features:




- Unlimited amount of layers (instead of the default 5)

- Per-layer [render types]()



```
``
```




To [datagen]() this model, use the custom loader class `ItemLayerModelBuilder`.


### OBJ Model[​]()



The OBJ model loader allows you to use Wavefront `.obj` 3D models in the game, allowing for arbitrary shapes (including triangles, circles, etc.) to be included in a model. The `.obj` model must be placed in the `models` folder (or a subfolder thereof), and a `.mtl` file with the same name must be provided (or set manually), so for example, an OBJ model at `models/block/example.obj` must have a corresponding MTL file at `models/block/example.mtl`.


```
``
```




To [datagen]() this model, use the custom loader class `ObjModelBuilder`.


### Separate Transforms Model[​]()



A separate transforms model can be used to switch between different models based on the perspective. The perspectives are the same as for the `display` block in a [normal model](). This works by specifying a base model (as a fallback) and then specifying per-perspective override models. Note that each of these can be fully-fledged models if you so desire, but it is usually easiest to just refer to another model by using a child model of that model, like so:


```
``
```




To [datagen]() this model, use the custom loader class `SeparateTransformsModelBuilder`.


## Creating Custom Model Loaders[​]()



To create your own model loader, you need three classes, plus an event handler:




- A geometry loader class

- A geometry class

- A dynamic [baked model]() class

- A [client-side]() [event handler]() for `ModelEvent.RegisterGeometryLoaders` that registers the geometry loader



To illustrate how these classes are connected, we will follow a model being loaded:




- During model loading, a model JSON with the `loader` property set to your loader is passed to your geometry loader. The geometry loader then reads the model JSON and returns a geometry object using the model JSON's properties.

- During model baking, the geometry is baked, returning a dynamic baked model.

- During model rendering, the dynamic baked model is used for rendering.



Let's illustrate this further through a basic class setup. The geometry loader class is named `MyGeometryLoader`, the geometry class is named `MyGeometry`, and the dynamic baked model class is named `MyDynamicModel`:


```
``
```




When all is done, don't forget to actually register your loader, otherwise all the work will have been for nothing:


```
``
```




### Datagen[​]()



Of course, we can also [datagen]() our models. To do so, we need a class that extends `CustomLoaderBuilder`:


```
``
```




To use this loader builder, do the following during block (or item) [model datagen]():


```
``
```




Then, call your field setters on the `loaderBuilder`.


#### Visibility[​]()



The default implementation of `CustomLoaderBuilder` holds methods for applying visibility. You may choose to use or ignore the `visibility` property in your model loader. Currently, only the [composite model loader]() makes use of this property.


### Reusing the Default Model Loader[​]()



In some contexts, it makes sense to reuse the vanilla model loader and just building your model logic on top of that instead of outright replacing it. We can do so using a neat trick: In the model loader, we simply remove the `loader` property and send it back to the model deserializer, tricking it into thinking that it is a regular model now. We then pass it to the geometry, bake the model geometry there (like the default geometry handler would) and pass it along to the dynamic model, where we can then use the model's quads in whatever way we want:


```
``
```

[PreviousModel Datagen]()[NextParticles]()


- [Builtin Model Loaders]()


- [Composite Model]()
- [Dynamic Fluid Container Model]()
- [Elements Model]()
- [Empty Model]()
- [Item Layer Model]()
- [OBJ Model]()
- [Separate Transforms Model]()
- [Creating Custom Model Loaders]()


- [Datagen]()
- [Reusing the Default Model Loader]()Docs


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
        

