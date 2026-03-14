---
URL: https://docs.neoforged.net/docs/1.21.1/resources/server/recipes/builtin
抓取时间: 2026-03-13 22:28:38
源站: NeoForge 1.21.1 官方文档
---






Built-In Recipe Types | NeoForged docs




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
- Built-In Recipe TypesVersion: 1.21 - 1.21.1On this page

# Built-In Recipe Types



Minecraft provides a variety of recipe types and serializers out of the box for you to use. This article will explain each recipe type, as well as how to generate them.


## Crafting[​]()



Crafting recipes are typically made in crafting tables, crafters, or in modded crafting tables or machines. Their recipe type is `minecraft:crafting`.


### Shaped Crafting[​]()



Some of the most important recipes - such as the crafting table, sticks, or most tools - are created through shaped recipes. These recipes are defined by a crafting pattern or shape (hence "shaped") in which the items must be inserted. Let's have a look at what an example looks like:


```
``
```




Let's digest this line for line:




- `type`: This is the id of the shaped recipe serializer, `minecraft:crafting_shaped`.

- `category`: This optional field defines the category in the crafting book.

- `key` and `pattern`: Together, these define how the items must be put into the crafting grid.




- The pattern defines up to three lines of up to three-wide strings that define the shape. All lines must be the same length, i.e. the pattern must form a rectangular shape. Spaces can be used to denote slots that should stay empty.

- The key associates the characters used in the pattern with [ingredients](). In the above example, all `X`s in the pattern must be iron ingots, and all `#`s must be sticks.



- `result`: The result of the recipe. This is [an item stack's JSON representation]().

- Not shown in the example is the `group` key. This optional string property creates a group in the recipe book. Recipes in the same group will be displayed as one in the recipe book.



And then, let's have a look at how you'd generate this recipe:


```
``
```




Additionally, you can call `#group` to set the recipe book group.


### Shapeless Crafting[​]()



Unlike shaped crafting recipes, shapeless crafting recipes do not care about the order the ingredients are passed in. As such, there is no pattern and key, instead there is just a list of ingredients:


```
``
```




Like before, let's digest this line for line:




- `type`: This is the id of the shapeless recipe serializer, `minecraft:crafting_shapeless`.

- `category`: This optional field defines the category in the crafting book.

- `ingredients`: A list of [ingredients](). The list order is preserved in code for recipe viewing purposes, but the recipe itself accepts the ingredients in any order.

- `result`: The result of the recipe. This is [an item stack's JSON representation]().

- Not shown in the example is the `group` key. This optional string property creates a group in the recipe book. Recipes in the same group will be displayed as one in the recipe book.



And then, let's have a look at how you'd generate this recipe:


```
``
```




Additionally, you can call `#group` to set the recipe book group.
info

One-item recipes (e.g. storage blocks unpacking) should be shapeless recipes to follow vanilla standards.


### Special Crafting[​]()



In some cases, outputs must be created dynamically from inputs. Most of the time, this is to set data components on the output by copying or calculating their values from the input stacks. These recipes usually only specify the type and hardcode everything else. For example:


```
``
```




This recipe, which is for leather armor dyeing, just specifies the type and hardcodes everything else - most notably the color calculation, which would be hard to express in JSON. Minecraft prefixes all special crafting recipes with `crafting_special_`, however this practice is not necessary to follow.


Generating this recipe looks as follows:


```
``
```




Vanilla provides the following special crafting serializers (mods may add more):




- `minecraft:crafting_special_armordye`: For dyeing leather armor and other dyeable items.

- `minecraft:crafting_special_bannerduplicate`: For duplicating banners.

- `minecraft:crafting_special_bookcloning`: For copying written books. This increases the resulting book's generation property by one.

- `minecraft:crafting_special_firework_rocket`: For crafting firework rockets.

- `minecraft:crafting_special_firework_star`: For crafting firework stars.

- `minecraft:crafting_special_firework_star_fade`: For applying a fade to a firework star.

- `minecraft:crafting_special_mapcloning`: For copying filled maps. Also works for treasure maps.

- `minecraft:crafting_special_mapextending`: For extending filled maps.

- `minecraft:crafting_special_repairitem`: For repairing two broken items into one.

- `minecraft:crafting_special_shielddecoration`: For applying a banner to a shield.

- `minecraft:crafting_special_shulkerboxcoloring`: For coloring a shulker box while preserving its contents.

- `minecraft:crafting_special_suspiciousstew`: For crafting suspicious stews depending on the input flower.

- `minecraft:crafting_special_tippedarrow`: For crafting tipped arrows depending on the input potion.

- `minecraft:crafting_decorated_pot`: For crafting decorated pots from sherds.



## Furnace-like Recipes[​]()



The second most important group of recipes are the ones made through smelting or a similar process. All recipes made in furnaces (type `minecraft:smelting`), smokers (`minecraft:smoking`), blast furnaces (`minecraft:blasting`) and campfires (`minecraft:campfire_cooking`) use the same format:


```
``
```




Let's digest this line by line:




- `type`: This is the id of the recipe serializer, `minecraft:smelting`. This may be different depending on what kind of furnace-like recipe you're making.

- `category`: This optional field defines the category in the crafting book.

- `cookingtime`: This field determines how long the recipes needs to be processed, in ticks. All vanilla furnace recipes use 200, smokers and blast furnaces use 100, and campfires use 600. However, this can be any value you want.

- `experience`: Determines the amount of experience rewarded when making this recipe. This field is optional, and no experience will be awarded if it is omitted.

- `ingredient`: The input [ingredient]() of the recipe.

- `result`: The result of the recipe. This is [an item stack's JSON representation]().



Datagen for these recipes looks like this:


```
``
```


info

The recipe type for these recipes is the same as their recipe serializer, i.e. furnaces use `minecraft:smelting`, smokers use `minecraft:smoking`, and so on.


## Stonecutting[​]()



Stonecutter recipes use the `minecraft:stonecutting` recipe type. They are about as simple as it gets, with only a type, an input and an output:


```
``
```




The `type` defines the recipe serializer (`minecraft:stonecutting`). The ingredient is an [ingredient](), and the result is a basic [item stack JSON](). Like crafting recipes, they can also optionally specify a `group` for grouping in the recipe book.


Datagen is also simple:


```
``
```




Note that the single item recipe builder does not support actual ItemStack results, and as such, no results with data components. The recipe codec, however, does support them, so a custom builder would need to be implemented if this functionality was desired.


## Smithing[​]()



The smithing table supports two different recipe serializers. One is for transforming inputs into outputs, copying over the components of the input (such as enchantments), and the other is for applying components to the input. Both use the `minecraft:smithing` recipe type, and require three inputs, named the base, the template, and the addition item.


### Transform Smithing[​]()



This recipe serializer is for transforming two input items into one, preserving the data components of the first input. Vanilla uses this mainly for netherite equipment, however any items can be used here:


```
``
```




Let's break this down line by line:




- `type`: This is the id of the recipe serializer, `minecraft:smithing_transform`.

- `base`: The base [ingredient]() of the recipe. Usually, this is some piece of equipment.

- `template`: The template [ingredient]() of the recipe. Usually, this is a smithing template.

- `addition`: The addition [ingredient]() of the recipe. Usually, this is some sort of material, for example a netherite ingot.

- `result`: The result of the recipe. This is [an item stack's JSON representation]().



During datagen, call on `SmithingTransformRecipeBuilder#smithing` to add your recipe:


```
``
```




### Trim Smithing[​]()



Trim smithing is the process of applying armor trims to armor:


```
``
```




Again, let's break this down into its bits:




- `type`: This is the id of the recipe serializer, `minecraft:smithing_trim`.

- `base`: The base [ingredient]() of the recipe. All vanilla use cases use the `minecraft:trimmable_armor` tag here.

- `template`: The template [ingredient]() of the recipe. All vanilla use cases use a smithing trim template here.

- `addition`: The addition [ingredient]() of the recipe. All vanilla use cases use the `minecraft:trim_materials` tag here.



This recipe serializer is notably missing a result field. This is because it uses the base input and "applies" the template and addition items on it, i.e., it sets the base's components based on the other inputs and uses the result of that operation as the recipe's result.


During datagen, call on `SmithingTrimRecipeBuilder#smithingTrim` to add your recipe:


```
``
```

[PreviousRecipes]()[NextIngredients]()


- [Crafting]()


- [Shaped Crafting]()
- [Shapeless Crafting]()
- [Special Crafting]()
- [Furnace-like Recipes]()
- [Stonecutting]()
- [Smithing]()


- [Transform Smithing]()
- [Trim Smithing]()Docs


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
        

