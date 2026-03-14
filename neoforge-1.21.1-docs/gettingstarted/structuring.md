---
URL: https://docs.neoforged.net/docs/1.21.1/gettingstarted/structuring
抓取时间: 2026-03-13 22:28:08
源站: NeoForge 1.21.1 官方文档
---






Structuring Your Mod | NeoForged docs




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


- [Mod Files]()
- [Structuring Your Mod]()
- [Versioning]()
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
- [Miscellaneous]()This is documentation for NeoForged **1.21 - 1.21.1**, which is no longer actively maintained.For up-to-date documentation, see the **[latest version]()** (1.21.11).


- []()
- [Getting Started]()
- Structuring Your ModVersion: 1.21 - 1.21.1On this page

# Structuring Your Mod



Structured mods are beneficial for maintenance, making contributions, and providing a clearer understanding of the underlying codebase. Some of the recommendations from Java, Minecraft, and NeoForge are listed below.
note

You do not have to follow the advice below; you can structure your mod any way you see fit. However, it is still highly recommended to do so.


## Packaging[​]()



When structuring your mod, pick a unique, top-level package structure. Many programmers will use the same name for different classes, interfaces, etc. Java allows classes to have the same name as long as they are in different packages. As such, if two classes have the same package with the same name, only one would be loaded, most likely causing the game to crash.


```
``
```




This is even more relevant when it comes to loading modules. If there are class files in two packages under the same name in separate modules, this will cause the mod loader to crash on startup since mod modules are exported to the game and other mods.


```
``
```




As such, your top level package should be something that you own: a domain, email address, a (subdomain of a) website, etc. It can even be your name or username as long as you can guarantee that it will be uniquely identifiable within the expected target. Furthermore, the top-level package should also match your [group id]().
TypeValueTop-Level PackageDomainexample.com`com.example`Subdomainexample.github.io`io.github.example`Email[[email protected]]()`com.gmail.example`


The next level package should then be your mod's id (e.g. `com.example.examplemod` where `examplemod` is the mod id). This will guarantee that, unless you have two mods with the same id (which should never be the case), your packages should not have any issues loading.


You can find some additional naming conventions on [Oracle's tutorial page]().


### Sub-package Organization[​]()



In addition to the top-level package, it is highly recommend to break your mod's classes between subpackages. There are two major methods on how to do so:




- **Group By Function**: Make subpackages for classes with a common purpose. For example, blocks can be under `block`, items under `item`, entities under `entity`, etc. Minecraft itself uses a similar structure (with some exceptions).

- **Group By Logic**: Make subpackages for classes with a common logic. For example, if you were creating a new type of crafting table, you would put its block, menu, item, and more under `feature.crafting_table`.



#### Client, Server, and Data Packages[​]()



In general, code only for a given side or runtime should be isolated from the other classes in a separate subpackage. For example, code related to [data generation]() should go in a `data` package, and code only on the dedicated server should go in a `server` package.


It is highly recommended that [client-only code]() should be isolated in a `client` subpackage. This is because dedicated servers have no access to any of the client-only packages in Minecraft and will crash if your mod tries to access them anyway. As such, having a dedicated package provides a decent sanity check to verify you are not reaching across sides within your mod.


## Class Naming Schemes[​]()



A common class naming scheme makes it easier to decipher the purpose of the class or to easily locate specific classes.


Classes are commonly suffixed with its type, for example:




- An `Item` called `PowerRing` -> `PowerRingItem`.

- A `Block` called `NotDirt` -> `NotDirtBlock`.

- A menu for an `Oven` -> `OvenMenu`.

tip

Mojang typically follows a similar structure for all classes except entities. Those are represented by just their names (e.g. `Pig`, `Zombie`, etc.).


## Choose One Method from Many[​]()



There are many methods for performing a certain task: registering an object, listening for events, etc. It's generally recommended to be consistent by using a single method to accomplish a given task. This improves readability and avoids weird interactions or redundancies that may occur (e.g. your event listener running twice).[PreviousMod Files]()[NextVersioning]()


- [Packaging]()


- [Sub-package Organization]()
- [Class Naming Schemes]()
- [Choose One Method from Many]()Docs


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
        

