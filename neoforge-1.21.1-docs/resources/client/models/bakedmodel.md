---
URL: https://docs.neoforged.net/docs/1.21.1/resources/client/models/bakedmodel
抓取时间: 2026-03-13 22:28:19
源站: NeoForge 1.21.1 官方文档
---






Baked Models | NeoForged docs




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
- Baked ModelsVersion: 1.21 - 1.21.1On this page

# Baked Models



`BakedModel`s are the in-code representation of a shape with textures. They can originate from multiple sources, for example from a call to `UnbakedModel#bake` (default model loader) or `IUnbakedGeometry#bake` ([custom model loaders]()). Some [block entity renderers]() also make use of baked models. There is no limit to how complex a model may be.


Models are stored in the `ModelManager`, which can be accessed through `Minecraft.getInstance().modelManager`. Then, you can call `ModelManager#getModel` to get a certain model by its [`ResourceLocation`]() or [`ModelResourceLocation`](). Mods will basically always reuse a model that was previously automatically loaded and baked.


## Methods of `BakedModel`[​]()



### `getQuads`[​]()



The most important method of a baked model is `getQuads`. This method is responsible for returning a list of `BakedQuad`s, which can then be sent to the GPU. A quad compares to a triangle in a modeling program (and in most other games), however due to Minecraft's general focus on squares, the developers elected to use quads (4 vertices) instead of triangles (3 vertices) for rendering in Minecraft. `getQuads` has five parameters that can be used:




- A `BlockState`: The [blockstate]() being rendered. May be null, indicating that an item is being rendered.

- A `Direction`: The direction of the face being culled against. May be null, which means quads that cannot be occluded should be returned.

- A `RandomSource`: A client-bound random source you can use for randomization.

- A `ModelData`: The extra model data to use. This may contain additional data from the block entity needed for rendering. Supplied by `BakedModel#getModelData`.

- A `RenderType`: The [render type]() to use for rendering the block. May be null, indicating that the quads for all render types used by this model should be returned. Otherwise, it is one of the render types returned by `BakedModel#getRenderTypes` (see below).



Models should heavily cache. This is because even though chunks are only rebuilt when a block in them changes, the computations done in this method still need to be as fast as possible and should ideally be cached heavily due to the amount of times this method will be called per chunk section (up to seven times per RenderType used by a given model * amount of RenderTypes used by the respective model * 4096 blocks per chunk section). In addition, [BERs]() or entity renderers may actually call this method several times per frame.


### `applyTransform` and `getTransforms`[​]()



`applyTransform` allows for applying custom logic when applying perspective transformations to the model, including returning a completely separate model. This method is added by NeoForge as a replacement for the vanilla `getTransforms()` method, which only allows you to customize the transforms themselves, but not the way they are applied. However, `applyTransform`'s default implementation defers to `getTransforms`, so if you only need custom transforms, you can also override `getTransforms` and be done with it. `applyTransforms` offers three parameters:




- An `ItemDisplayContext`: The [perspective]() the model is being transformed to.

- A `PoseStack`: The pose stack used for rendering.

- A `boolean`: Whether to use modified values for left-hand rendering instead of the default right hand rendering; `true` if the rendered hand is the left hand (off hand, or main hand if left hand mode is enabled in the options)

note

`applyTransform` and `getTransforms` only apply to item models.


### Others[​]()



Other methods in `BakedModel` that you may override and/or query include:
SignatureEffect`TriState useAmbientOcclusion()`Whether to use [ambient occlusion]() or not. Accepts a `BlockState`, `RenderType` and `ModelData` parameter and returns a `TriState` which allows not only force-disabling AO but also force-enabling AO. Has two overloads that each return a `boolean` parameter and accept either only a `BlockState` or no parameters at all; both of these are deprecated for removal in favor of the first variant.`boolean isGui3d()`Whether this model renders as 3d or flat in GUI slots.`boolean usesBlockLight()`Whether to use 3D lighting (`true`) or flat lighting from the front (`false`) when lighting the model.`boolean isCustomRenderer()`If true, skips normal rendering and calls an associated [`BlockEntityWithoutLevelRenderer`]()'s `renderByItem` method instead. If false, renders through the default renderer.`ItemOverrides getOverrides()`Returns the [`ItemOverrides`]() associated with this model. This is only relevant on item models.`ModelData getModelData(BlockAndTintGetter, BlockPos, BlockState, ModelData)`Returns the model data to use for the model. This method is passed an existing `ModelData` that is either the result of `BlockEntity#getModelData()` if the block has an associated block entity, or `ModelData.EMPTY` if that is not the case. This method can be used for blocks that need model data, but do not have a block entity, for example for blocks with connected textures.`TextureAtlasSprite getParticleIcon(ModelData)`Returns the particle sprite to use for the model. May use the model data to use different particle sprites for different model data values. NeoForge-added, replacing the vanilla `getParticleIcon()` overload with no parameters.`ChunkRenderTypeSet getRenderTypes(BlockState, RandomSource, ModelData)`Returns a `ChunkRenderTypeSet` containing the render type(s) to use for rendering the block model. A `ChunkRenderTypeSet` is a set-backed ordered `Iterable<RenderType>`. By default falls back to [getting the render type from the model JSON](). Only used for block models, item models use the overload below.`List<RenderType> getRenderTypes(ItemStack, boolean)`Returns a `List<RenderType>` containing the render type(s) to use for rendering the item model. By default falls back to the normal model-bound render type lookup, which always yields a list with one element. Only used for item models, block models use the overload above.


## Perspectives[​]()



Minecraft's render engine recognizes a total of 8 perspective types (9 if you include the in-code fallback) for item rendering. These are used in a model JSON's `display` block, and represented in code through the `ItemDisplayContext` enum.
Enum valueJSON keyUsage`THIRD_PERSON_RIGHT_HAND``"thirdperson_righthand"`Right hand in third person (F5 view, or on other players)`THIRD_PERSON_LEFT_HAND``"thirdperson_lefthand"`Left hand in third person (F5 view, or on other players)`FIRST_PERSON_RIGHT_HAND``"firstperson_righthand"`Right hand in first person`FIRST_PERSON_LEFT_HAND``"firstperson_lefthand"`Left hand in first person`HEAD``"head"`When in a player's head armor slot (often only achievable via commands)`GUI``"gui"`Inventories, player hotbar`GROUND``"ground"`Dropped items; note that the rotation of the dropped item is handled by the dropped item renderer, not the model`FIXED``"fixed"`Item frames`NONE``"none"`Fallback purposes in code, should not be used in JSON


## `ItemOverrides`[​]()



`ItemOverrides` is a class that provides a way for baked models to process the state of an [`ItemStack`]() and return a new baked model through the `#resolve` method. `#resolve` has five parameters:




- A `BakedModel`: The original model.

- An `ItemStack`: The item stack being rendered.

- A `ClientLevel`: The level the model is being rendered in. This should only be used for querying the level, not mutating it in any way. May be null.

- A `LivingEntity`: The entity the model is rendered on. May be null, e.g. when rendering from a [block entity renderer]().

- An `int`: A seed for randomizing.



`ItemOverrides` also hold the model's override options as `BakedOverride`s. An object of `BakedOverride` is an in-code representation of a model's [`overrides`]() block. It can be used by baked models to return different models depending on its contents. A list of all `BakedOverride`s of an `ItemOverrides` instance can be retrieved through `ItemOverrides#getOverrides()`.


## `BakedModelWrapper`[​]()



A `BakedModelWrapper` can be used to modify an already existing `BakedModel`. `BakedModelWrapper` is a subclass of `BakedModel` that accepts another `BakedModel` (the "original" model) in the constructor and by default redirects all methods to the original model. Your implementation can then override only select methods, like so:


```
``
```




After writing your model wrapper class, you must apply the wrappers to the models it should affect. Do so in a [client-side]() [event handler]() for `ModelEvent.ModifyBakingResult`:


```
``
```


warning

It is generally encouraged to use a [custom model loader]() over wrapping baked models in `ModelEvent.ModifyBakingResult` when possible. Custom model loaders can also use `BakedModelWrapper`s if needed.[PreviousModels]()[NextModel Datagen]()


- [Methods of `BakedModel`]()


- [`getQuads`]()
- [`applyTransform` and `getTransforms`]()
- [Others]()
- [Perspectives]()
- [`ItemOverrides`]()
- [`BakedModelWrapper`]()Docs


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
        

