---
URL: https://docs.neoforged.net/docs/1.21.1/items/tools
抓取时间: 2026-03-13 22:28:44
源站: NeoForge 1.21.1 官方文档
---






Tools & Armor | NeoForged docs




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
- Tools & ArmorVersion: 1.21 - 1.21.1On this page

# Tools & Armor



Tools are [items]() whose primary use is to break [blocks](). Many mods add new tool sets (for example copper tools) or new tool types (for example hammers).


## Custom Tool Sets[​]()



A tool set typically consists of five items: a pickaxe, an axe, a shovel, a hoe and a sword. (Swords aren't tools in the classical sense, but are included here for consistency as well.) All of those items have their corresponding class: `PickaxeItem`, `AxeItem`, `ShovelItem`, `HoeItem` and `SwordItem`, respectively. The class hierarchy of tools looks as follows:


```
``
```




`TieredItem` is a class that contains helpers for items with a certain `Tier` (read on). `DiggerItem` contains helpers for items that are designed to break blocks. Note that other items usually considered tools, such as shears, are not included in this hierarchy. Instead, they directly extend `Item` and hold the breaking logic themselves.


To create a standard set of tools, you must first define a `Tier`. For reference values, see Minecraft's `Tiers` enum. This example uses copper tools, you can use your own material here and adjust the values as needed.


```
``
```




Now that we have our `Tier`, we can use it for registering tools. All tool constructors have the same four parameters.


```
``
```




### Tags[​]()



When creating a `Tier`, it is assigned a block [tag]() containing blocks that will not drop anything if broken with this tool. For example, the `minecraft:incorrect_for_stone_tool` tag contains blocks like Diamond Ore, and the `minecraft:incorrect_for_iron_tool` tag contains blocks like Obsidian and Ancient Debris. To make it easier to assign blocks to their incorrect mining levels, a tag also exists for blocks that need this tool to be mined. For example, the `minecraft:needs_iron_tool` tag containslike Diamond Ore, and the `minecraft:needs_diamond_tool` tag contains blocks like Obsidian and Ancient Debris.


You can reuse one of the incorrect tags for your tool if you're fine with that. For example, if we wanted our copper tools to just be more durable stone tools, we'd pass in `BlockTags#INCORRECT_FOR_STONE_TOOL`.


Alternatively, we can create our own tag, like so:


```
``
```




And then, we populate our tag. For example, let's make copper able to mine gold ores, gold blocks and redstone ore, but not diamonds or emeralds. (Redstone blocks are already mineable by stone tools.) The tag file is located at `src/main/resources/data/mod_id/tags/block/needs_copper_tool.json` (where `mod_id` is your mod id):


```
``
```




Then, for our tag to pass into the tier, we can provide a negative constraint for any tools that are incorrect for stone tools but within our copper tools tag. The tag file is located at `src/main/resources/data/mod_id/tags/block/incorrect_for_cooper_tool.json`:


```
``
```




Finally, we can pass our tag into our tier creation, as seen above.


If you want to check if a tool can make a block state drop its blocks, call `Tool#isCorrectForDrops`. The `Tool` can be obtained by calling `ItemStack#get` with `DataComponents#TOOL`.


## Custom Tools[​]()



Custom tools can be created by adding a `Tool` [data component]() (via `DataComponents#TOOL`) to the list of default components on your item via `Item.Properties#component`. `DiggerItem` is an implementation which takes in a `Tier`, as explained above, to construct the `Tool`. `DiggerItem` also provides a convenience method called `#createAttributes` to supply to `Item.Properties#attributes` for your tool, such as the modified attack damage and attack speed.


A `Tool` contains a list of `Tool.Rule`s, the default mining speed when holding the tool (`1` by default), and the amount of damage the tool should take when mining a block (`1` by default). A `Tool.Rule` contains three pieces of information: a `HolderSet` of blocks to apply the rule to, an optional speed at which to mine the blocks in the set, and an optional boolean at which to determine whether these blocks can drop from this tool. If the optional are not set, then the other rules will be checked. The default behavior if all rules fail is the default mining speed and that the block cannot be dropped.
note

A `HolderSet` can be created from a `TagKey` via `Registry#getOrCreateTag`.


Creating a multitool-like item (i.e. an item that combines two or more tools into one, e.g. an axe and a pickaxe as one item) or any tool-like does not need to extend any of the existing `TieredItem`s. It simply can be implemented using a combination of the following parts:




- Adding a `Tool` with your own rules by setting `DataComponents#TOOL` via `Item.Properties#component`.

- Adding attributes to the item (e.g. attack damage, attack speed) via `Item.Properties#attributes`.

- Overriding `IItemExtension#canPerformAction` to determine what [`ItemAbility`s]() the item can perform.

- Calling `IBlockExtension#getToolModifiedState` if you want your item to modify the block state on right click based on the `ItemAbility`s.

- Adding your tool to some of the `minecraft:enchantable/*` tags so that your item can have certain enchantments applied to it.



## `ItemAbility`s[​]()



`ItemAbility`s are an abstraction over what an item can and cannot do. This includes both left-click and right-click behavior. NeoForge provides default `ItemAbility`s in the `ItemAbilities` class:




- Digging abilities. These exist for all four `DiggerItem` types as mentioned above, as well as sword and shears digging.

- Axe right-click abilities for stripping (logs), scraping (oxidized copper) and unwaxing (waxed copper).

- Shear abilities for harvesting (honeycombs), carving (pumpkins) and disarming (tripwires).

- Abilities for shovel flattening (dirt paths), sword sweeping, hoe tilling, shield blocking, and fishing rod casting.



To create your own `ItemAbility`s, use `ItemAbility#get` - it will create a new `ItemAbility` if needed. Then, in a custom tool type, override `IItemExtension#canPerformAction` as needed.


To query if an `ItemStack` can perform a certain `ItemAbility`, call `IItemStackExtension#canPerformAction`. Note that this works on any `Item`, not just tools.


## Armor[​]()



Similar to tools, armor uses a tier system (although a different one). What is called `Tier` for tools is called `ArmorMaterial` for armors. Like above, this example shows how to add copper armor; this can be adapted as needed. However, unlike `Tier`s, `ArmorMaterial`s need to be [registered](). For the vanilla values, see the `ArmorMaterials` class.


```
``
```




And then, we use that armor material in item registration.


```
``
```




When creating your armor texture, it is a good idea to work on top of the vanilla armor texture to see which part goes where.[PreviousData Components]()[NextMob Effects & Potions]()


- [Custom Tool Sets]()


- [Tags]()
- [Custom Tools]()
- [`ItemAbility`s]()
- [Armor]()Docs


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
        

