---
URL: https://docs.neoforged.net/docs/1.21.1/datastorage/nbt
抓取时间: 2026-03-13 22:27:48
源站: NeoForge 1.21.1 官方文档
---






Named Binary Tag (NBT) | NeoForged docs




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


- [Named Binary Tag (NBT)]()
- [Codecs]()
- [Data Attachments]()
- [Saved Data]()
- [GUIs]()
- [Worldgen]()
- [Networking]()
- [Advanced Topics]()
- [Miscellaneous]()This is documentation for NeoForged **1.21 - 1.21.1**, which is no longer actively maintained.For up-to-date documentation, see the **[latest version]()** (1.21.11).


- []()
- Data Storage
- Named Binary Tag (NBT)Version: 1.21 - 1.21.1On this page

# Named Binary Tag (NBT)



NBT is a format introduced in the earliest days of Minecraft, written by Notch himself. It is widely used throughout the Minecraft codebase for data storage.


## Specification[​]()



The NBT spec is similar to the JSON spec, with a few differences:




- Distinct types for bytes, shorts, longs and floats exist, suffixed by `b`, `s`, `l` and `f`, respectively, similar to how they would be represented in Java code.




- Doubles may also be suffixed with `d`, but this is not required, similar to Java code. The optional `i` suffix available in Java for integers is not permitted.

- The suffixes are not case-sensitive. So for example, `64b` is the same as `64B`, and `0.5F` is the same as `0.5f`.



- Booleans do not exist, they are instead represented by bytes. `true` becomes `1b`, `false` becomes `0b`.




- The current implementation treats all non-zero values as `true`, so `2b` would be treated as `true` as well.



- There is no `null` equivalent in NBT.

- Quotes around keys are optional. So a JSON property `"duration": 20` can become both `duration: 20` and `"duration": 20` in NBT.

- What is known in JSON as a sub-object is known in NBT as a **compound tag** (or just compound).

- NBT lists cannot mix and match types, unlike in JSON. The list type is determined by the first element, or defined in code.




- However, lists of lists can mix and match different list types. So a list of two lists, where the first one is a list of strings and the second one is a list of bytes, is allowed.



- There are special **array** types that are different from lists, but follow their scheme of containing elements in square brackets. There are three array types:




- Byte arrays, denoted by a `B;` at the beginning of the array. Example: `[B;0b,30b]`

- Integer arrays, denoted by a `I;` at the beginning of the array. Example: `[I;0,-300]`

- Long arrays, denoted by an `L;` at the beginning of the array. Example: `[L;0l,240l]`



- Trailing commas in lists, arrays and compound tags are allowed.



## NBT Files[​]()



Minecraft uses `.nbt` files extensively, for example for structure files in [datapacks](). Region files (`.mca`) that contain the contents of a region (i.e. a collection of chunks), as well as the various `.dat` files used in different places by the game, are NBT files as well.


NBT files are typically compressed with GZip. As such, they are binary files and cannot be edited directly.


## NBT in Code[​]()



Like in JSON, all NBT objects are children of an enclosing object. So let's create one:


```
``
```




We can now put our data into that tag:


```
``
```




Several helpers exist here, for example, `putIntArray` also has a convenience method that takes a `List<Integer>` in addition to the standard variant that takes an `int[]`.


Of course, we can also get values from that tag:


```
``
```




Number types will return 0 if absent. Strings will return `""` if absent. More complex types (lists, arrays, compounds) will throw an exception if absent.


As such, we want to safeguard by checking if a tag element exists:


```
``
```




The `TAG_INT` constant is defined in `Tag`, which is the super interface for all tag types. Most tag types besides `CompoundTag` are mostly internal, for example `ByteTag` or `StringTag`, though the direct `CompoundTag#get` and `#put` methods can work with them if you ever stumble across some.


There is one obvious exception, though: `ListTag`s. Working with these is special because when getting a list tag through `CompoundTag#getList`, you must also specify the list type. So getting a list of strings, for example, would work like this:


```
``
```




Similarly, when creating a `ListTag`, you must also specify the list type during creation:


```
``
```




Finally, working with `CompoundTag`s inside other `CompoundTag`s directly utilizes `CompoundTag#get` and `#put`:


```
``
```




## Usages of NBT[​]()



NBT is used in a lot of places in Minecraft. Some of the most common examples include [`BlockEntity`]()s and `Entity`s.
note

`ItemStack`s abstract away the usage of NBT into [data components]().


## See Also[​]()





- [NBT Format on the Minecraft Wiki]()
[PreviousCapabilities]()[NextCodecs]()


- [Specification]()
- [NBT Files]()
- [NBT in Code]()
- [Usages of NBT]()
- [See Also]()Docs


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
        

