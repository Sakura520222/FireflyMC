---
URL: https://docs.neoforged.net/docs/1.21.1/resources/server/recipes/ingredients
抓取时间: 2026-03-13 22:28:32
源站: NeoForge 1.21.1 官方文档
---






Ingredients | NeoForged docs




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
- [Server]()


- [Advancements]()
- [Data Load Conditions]()
- [Damage Types & Damage Sources]()
- [Data Maps]()
- [Enchantments]()
- [Loot Tables]()
- [Recipes]()


- [Built-In Recipe Types]()
- [Ingredients]()
- [Tags]()
- [Inventories & Transfers]()
- [Data Storage]()
- [GUIs]()
- [Worldgen]()
- [Networking]()
- [Advanced Topics]()
- [Miscellaneous]()This is documentation for NeoForged **1.21 - 1.21.1**, which is no longer actively maintained.For up-to-date documentation, see the **[latest version]()** (1.21.11).


- []()
- [Resources]()
- Server
- [Recipes]()
- IngredientsVersion: 1.21 - 1.21.1On this page

# Ingredients



`Ingredient`s are used in [recipes]() to check whether a given [`ItemStack`]() is a valid input for the recipe. For this purpose, `Ingredient` implements `Predicate<ItemStack>`, and `#test` can be called to confirm if a given `ItemStack` matches the ingredient.


Unfortunately, many internals of `Ingredient` are a mess. NeoForge works around this by ignoring the `Ingredient` class where possible, instead introducing the `ICustomIngredient` interface for custom ingredients. This is not a direct replacement for regular `Ingredient`s, but we can convert to and from `Ingredient`s using `ICustomIngredient#toVanilla` and `Ingredient#getCustomIngredient`, respectively.


## Built-In Ingredient Types[​]()



The simplest way to get an ingredient is using the `Ingredient#of` helpers. Several variants exist:




- `Ingredient.of()` returns an empty ingredient.

- `Ingredient.of(Blocks.IRON_BLOCK, Items.GOLD_BLOCK)` returns an ingredient that accepts either an iron or a gold block. The parameter is a vararg of [`ItemLike`s](), which means that any amount of both blocks and items may be used.

- `Ingredient.of(new ItemStack(Items.DIAMOND_SWORD))` returns an ingredient that accepts an item stack. Be aware that counts and data components are ignored.

- `Ingredient.of(Stream.of(new ItemStack(Items.DIAMOND_SWORD)))` returns an ingredient that accepts an item stack. Like the previous method, but with a `Stream<ItemStack>` for if you happen to get your hands on one of those.

- `Ingredient.of(ItemTags.WOODEN_SLABS)` returns an ingredient that accepts any item from the specified [tag](), for example any wooden slab.



Additionally, NeoForge adds a few additional ingredients:




- `new BlockTagIngredient(BlockTags.CONVERTABLE_TO_MUD)` returns an ingredient similar to the tag variant of `Ingredient.of()`, but with a block tag instead. This should be used for cases where you'd use an item tag, but there is only a block tag available (for example `minecraft:convertable_to_mud`).

- `CompoundIngredient.of(Ingredient.of(Items.DIRT))` returns an ingredient with child ingredients, passed in the constructor (vararg parameter). The ingredient matches if any of its children matches.

- `DataComponentIngredient.of(true, new ItemStack(Items.DIAMOND_SWORD))` returns an ingredient that, in addition to the item, also matches the data component. The boolean parameter denotes strict matching (true) or partial matching (false). Strict matching means the data components must match exactly, while partial matching means the data components must match, but other data components may also be present. Additional overloads of `#of` exist that allow specifying multiple `Item`s, or provide other options.

- `DifferenceIngredient.of(Ingredient.of(ItemTags.PLANKS), Ingredient.of(ItemTags.NON_FLAMMABLE_WOOD))` returns an ingredient that matches everything in the first ingredient that doesn't also match the second ingredient. The given example only matches planks that can burn (i.e. all planks except crimson planks, warped planks and modded nether wood planks).

- `IntersectionIngredient.of(Ingredient.of(ItemTags.PLANKS), Ingredient.of(ItemTags.NON_FLAMMABLE_WOOD))` returns an ingredient that matches everything that matches both sub-ingredients. The given example only matches planks that cannot burn (i.e. crimson planks, warped planks and modded nether wood planks).



Keep in mind that the NeoForge-provided ingredient types are `ICustomIngredient`s and must call `#toVanilla` before using them in vanilla contexts, as outlined in the beginning of this article.


## Custom Ingredient Types[​]()



It is possible for modders to add their custom ingredient types through the `ICustomIngredient` system. For the sake of example, let's make an enchanted item ingredient that accepts an item tag and a map of enchantments to min levels:


```
``
```




Custom ingredients are a [registry](), so we must register our ingredient. We do so using the `IngredientType` class provided by NeoForge, which is basically a wrapper around a [`MapCodec`]() and optionally a [`StreamCodec`]().


```
``
```




When we have done that, we also need to override `#getType` in our ingredient class:


```
``
```




And there we go! Our ingredient type is ready to use.


## JSON Representation[​]()



Due to vanilla ingredients being pretty limited and NeoForge introducing a whole new registry for them, it's also worth looking at what the built-in and our own ingredients look like in JSON.


Ingredients that specify a `type` are generally assumed to be non-vanilla. For example:


```
``
```




Or another example using our own ingredient:


```
``
```




If the `type` is unspecified, then we have a vanilla ingredient. Vanilla ingredients can specify one of two properties: `item` or `tag`.


An example for a vanilla item ingredient:


```
``
```




An example for a vanilla tag ingredient:


```
``
```

[PreviousBuilt-In Recipe Types]()[NextTags]()


- [Built-In Ingredient Types]()
- [Custom Ingredient Types]()
- [JSON Representation]()Docs


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
        

