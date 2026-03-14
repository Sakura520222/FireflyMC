---
URL: https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata
抓取时间: 2026-03-13 22:27:51
源站: NeoForge 1.21.1 官方文档
---






Saved Data | NeoForged docs




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
- Saved DataVersion: 1.21 - 1.21.1On this page

# Saved Data



The Saved Data (SD) system can be used to save additional data on levels.


*If the data is specific to some block entities, chunks, or entities, consider using a [data attachment]() instead.*


## Declaration[​]()



Each SD implementation must subtype the `SavedData` class. There are two important methods to be aware of:




- `save`: Allows the implementation to write NBT data to the level.

- `setDirty`: A method that must be called after changing the data, to notify the game that there are changes that need to be written. If not called, `#save` will not get called and the original data will remain unchanged.



## Attaching to a Level[​]()



Any `SavedData` is loaded and/or attached to a level dynamically. As such, if one is never created on a level, then it will not exist.


`SavedData`s are created and loaded from the `DimensionDataStorage`, which can be accessed by calling either `ServerChunkCache#getDataStorage` or `ServerLevel#getDataStorage`. From there, you can get or create an instance of your SD by calling `DimensionDataStorage#computeIfAbsent`. This will attempt to get the current instance of the SD if present or create a new one and load all available data.


`DimensionDataStorage#computeIfAbsent` takes in two arguments. The first is an instance of `SavedData.Factory`, which consists of a supplier to construct a new instance of the SD and a function to load NBT data into a SD and return it. The second argument is the name of the `.dat` file stored within the `data` folder for the implemented level. The name must be a valid filename and can not contain `/` or `\`.


For example, if a SD was named "example" within the Nether, then a file would be created at `./<level_folder>/DIM-1/data/example.dat` and would be implemented like so:


```
``
```




If a SD is not specific to a level, the SD should be attached to the Overworld, which can be obtained from `MinecraftServer#overworld`. The Overworld is the only dimension that is never fully unloaded and as such makes it perfect to store multi-level data on.[PreviousData Attachments]()[NextMenus]()


- [Declaration]()
- [Attaching to a Level]()Docs


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
        

