---
URL: https://docs.neoforged.net/docs/1.21.1/misc/debugprofiler
抓取时间: 2026-03-13 22:28:00
源站: NeoForge 1.21.1 官方文档
---






Debug Profiler | NeoForged docs




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
- Debug ProfilerVersion: 1.21 - 1.21.1On this page

# Debug Profiler



Minecraft provides a Debug Profiler that provides system data, current game settings, JVM data, level data, and sided tick information to find time consuming code. Considering things like `TickEvent`s and ticking `BlockEntities`, this can be very useful for modders and server owners that want to find a lag source.


## Using the Debug Profiler[​]()



The Debug Profiler is very simple to use. It requires the debug keybind `F3 + L` to start the profiler. After 10 seconds, it will automatically stop; however, it can be stopped earlier by pressing the keybind again.
note

Naturally, you can only profile code paths that are actually being reached. `Entities` and `BlockEntities` that you want to profile must exist in the level to show up in the results.


After you have stopped the debugger, it will create a new zip within the `debug/profiling` subdirectory in your run directory.
The file name will be formatted with the date and time as `yyyy-mm-dd_hh_mi_ss-WorldName-VersionNumber.zip`


## Reading a Profiling result[​]()



Within each sided folder (`client` and `server`), you will find a `profiling.txt` file containing the result data. At the top, it first tells you how long in milliseconds it was running and how many ticks ran in that time.


Below that, you will find information similar to the snippet below:


```
``
```




Here is a small explanation of what each part means:
[02]tick99.31%95.81%The Depth of the sectionThe Name of the SectionThe percentage of time it took in relation to it's parent. For Layer 0, it is the percentage of the time a tick takes. For Layer 1, it is the percentage of the time its parent takes.The second percentage tells you how much time it took from the entire tick.


## Profiling your own code[​]()



The Debug Profiler has basic support for `Entity` and `BlockEntity`. If you would like to profile something else, you may need to manually create your sections like so:


```
``
```




You can obtain the `ProfilerFiller` instance from a `Level`, `MinecraftServer`, or `Minecraft` instance.
Now you just need to search the results file for your section name.[PreviousConfiguration]()[NextGame Tests]()


- [Using the Debug Profiler]()
- [Reading a Profiling result]()
- [Profiling your own code]()Docs


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
        

