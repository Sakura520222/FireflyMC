---
URL: https://docs.neoforged.net/docs/1.21.1/misc/updatechecker
抓取时间: 2026-03-13 22:28:05
源站: NeoForge 1.21.1 官方文档
---






NeoForge Update Checker | NeoForged docs




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
- NeoForge Update CheckerVersion: 1.21 - 1.21.1On this page

# NeoForge Update Checker



NeoForge provides a very lightweight, opt-in, update-checking framework. If any mods have an available update, it will show a flashing icon on the 'Mods' button of the main menu and mod list along with the respective changelogs. It *does not* download updates automatically.


## Getting Started[​]()



The first thing you want to do is specify the `updateJSONURL` parameter in your `mods.toml` file. The value of this parameter should be a valid URL pointing to an update JSON file. This file can be hosted on your own web server, GitHub, or wherever you want as long as it can be reliably reached by all users of your mod.


## Update JSON format[​]()



The JSON itself has a relatively simple format as follows:


```
``
```




This is fairly self-explanatory, but some notes:




- 


The link under `homepage` is the link the user will be shown when the mod is outdated.


- 


NeoForge uses an internal algorithm to determine whether one version string of your mod is "newer" than another. Most versioning schemes should be compatible, but see the `ComparableVersion` class if you are concerned about whether your scheme is supported. Adherence to [Maven versioning]() is highly recommended.


- 


The changelog string can be separated into lines using `\n`. Some prefer to include a abbreviated changelog, then link to an external site that provides a full listing of changes.


- 


Manually inputting data can be chore. You can configure your `build.gradle` to automatically update this file when building a release as Groovy has native JSON parsing support. Doing this is left as an exercise to the reader.


- 


Some examples can be found here for [nocubes](), [Corail Tombstone]() and [Chisels & Bits 2]().




## Retrieving Update Check Results[​]()



You can retrieve the results of the NeoForge Update Checker using `VersionChecker#getResult(IModInfo)`. You can obtain your `IModInfo` via `ModContainer#getModInfo`, where `ModContainer` can be added as a parameter to your mod constructor. You can obtain any other mod's `ModContainer` using `ModList.get().getModContainerById(<modId>)`. The returned object has a method `#status` which indicates the status of the version check.
StatusDescription`FAILED`The version checker could not connect to the URL provided.`UP_TO_DATE`The current version is equal to the recommended version.`AHEAD`The current version is newer than the recommended version if there is not latest version.`OUTDATED`There is a new recommended or latest version.`BETA_OUTDATED`There is a new latest version.`BETA`The current version is equal to or newer than the latest version.`PENDING`The result requested has not finished yet, so you should try again in a little bit.


The returned object will also have the target version and any changelog lines as specified in `update.json`.[PreviousResource Locations]()


- [Getting Started]()
- [Update JSON format]()
- [Retrieving Update Check Results]()Docs


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
        

