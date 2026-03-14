---
URL: https://docs.neoforged.net/docs/1.21.1/misc/config
抓取时间: 2026-03-13 22:27:58
源站: NeoForge 1.21.1 官方文档
---






Configuration | NeoForged docs




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
- ConfigurationVersion: 1.21 - 1.21.1On this page

# Configuration



Configurations define settings and consumer preferences that can be applied to a mod instance. NeoForge uses a configuration system using [TOML]() files and read with [NightConfig]().


## Creating a Configuration[​]()



A configuration can be created using a subtype of `IConfigSpec`. NeoForge implements the type via `ModConfigSpec` and enables its construction through `ModConfigSpec.Builder`. The builder can separate the config values into sections via `Builder#push` to create a section and `Builder#pop` to leave a section. Afterwards, the configuration can be built using one of two methods:
MethodDescription`build`Creates the `ModConfigSpec`.`configure`Creates a pair of the class holding the config values and the `ModConfigSpec`.
note

`ModConfigSpec.Builder#configure` is typically used with a `static` block and a class that takes in `ModConfigSpec.Builder` as part of its constructor to attach and hold the values:

```
``
```




Each config value can be supplied with additional context to provide additional behavior. Contexts must be defined before the config value is fully built:
MethodDescription`comment`Provides a description of what the config value does. Can provide multiple strings for a multiline comment.`translation`Provides a translation key for the name of the config value.`worldRestart`The world must be restarted before the config value can be changed.


### ConfigValue[​]()



Config values can be built with the provided contexts (if defined) using any of the `#define` methods.


All config value methods take in at least two components:




- A path representing the name of the variable: a `.` separated string representing the sections the config value is in

- The default value when no valid configuration is present



The `ConfigValue` specific methods take in two additional components:




- A validator to make sure the deserialized object is valid

- A class representing the data type of the config value



```
``
```




The values themselves can be obtained using `ConfigValue#get`. The values are additionally cached to prevent multiple readings from files.


#### Additional Config Value Types[​]()





- **Range Values**




- Description: Value must be between the defined bounds

- Class Type: `Comparable<T>`

- Method Name: `#defineInRange`

- Additional Components:




- The minimum and maximum the config value may be

- A class representing the data type of the config value





note

`DoubleValue`s, `IntValue`s, and `LongValue`s are range values which specify the class as `Double`, `Integer`, and `Long` respectively.




- 


**Whitelisted Values**




- Description: Value must be in supplied collection

- Class Type: `T`

- Method Name: `#defineInList`

- Additional Components:




- A collection of the allowed values the configuration can be





- 


**List Values**




- Description: Value is a list of entries

- Class Type: `List<T>`

- Method Name: `#defineList`, `#defineListAllowEmpty` if list can be empty

- Additional Components:




- A supplier that returns a default value to use when a new entry is added in configuration screens.

- A validator to make sure a deserialized element from the list is valid

- (optional) A vaidator to make sure the list does not get too little or too many entries





- 


**Enum Values**




- Description: An enum value in the supplied collection

- Class Type: `Enum<T>`

- Method Name: `#defineEnum`

- Additional Components:




- A getter to convert a string or integer into an enum

- A collection of the allowed values the configuration can be





- 


**Boolean Values**




- Description: A `boolean` value

- Class Type: `Boolean`

- Method Name: `#define`





## Registering a Configuration[​]()



Once a `ModConfigSpec` has been built, it must be registered to allow NeoForge to load, track, and sync the configuration settings as required. Configurations should be registered in the mod constructor via `ModConatiner#registerConfig`. A configuration can be registered with a [given type]() representing the side the config belongs to, the `ModConfigSpec`, and optionally a specific file name for the configuration.


```
``
```




### Configuration Types[​]()



Configuration types determine where the configuration file is located, what time it is loaded, and whether the file is synced across the network. All configurations are, by default, either loaded from `.minecraft/config` on the physical client or `<server_folder>/config` on the physical server. Some nuances between each configuration type can be found in the following subsections.
tip

NeoForge documents the [config types]() within their codebase.




- `STARTUP`




- Loaded on both the physical client and physical server from the config folder

- Read immediately on registration

- **NOT** synced across the network

- Suffixed with `-startup` by default



warning

Configurations registered under the `STARTUP` type can cause desyncs between the client and server, such as if the configuration is used to disable the registration of content. Therefore, it is highly recommended that any configurations within `STARTUP` are not used to enable or disable features that may change the content of the mod.




- `CLIENT`




- Loaded **ONLY** on the physical client from the config folder




- There is no server location for this configuration type



- Read immedately before `FMLCommonSetupEvent` is fired

- **NOT** synced across the network

- Suffixed with `-client` by default



- `COMMON`




- Loaded on both the physical client and physical server from the config folder

- Read immedately before `FMLCommonSetupEvent` is fired

- **NOT** synced across the network

- Suffixed with `-common` by default



- `SERVER`




- Loaded on both the physical client and physical server from the config folder




- Can be overridden for each world by adding a config to:




- Client: `.minecraft/saves/<world_name>/serverconfig`

- Server: `<server_folder>/world/serverconfig`





- Read immedately before `ServerAboutToStartEvent` is fired

- Synced across the network to the client

- Suffixed with `-server` by default





## Configuration Events[​]()



Operations that occur whenever a config is loaded or reloaded can be done using the `ModConfigEvent.Loading` and `ModConfigEvent.Reloading` events. The events must be [registered]() to the mod event bus.
caution

These events are called for all configurations for the mod; the `ModConfig` object provided should be used to denote which configuration is being loaded or reloaded.


## Configuration Screen[​]()



A configuration screen allows users to edit the config values for a mod while in-game without needing to open any files. The screen will automatically parse your registered config files and populate the screen.


A mod can use the built-in configuration screen that NeoForge provides. Mods can extend `ConfigurationScreen` to change the behavior of the default screen or make their own configuration screen. Mods can also create their own screen from scratch and provide that custom screen to NeoForge through the below extension point.


A configuration screen can be registered for a mod by registering a `IConfigScreenFactory` extension point during mod construction on the [client]():


```
``
```




The configuration screen can be accessed in game by going to the 'Mods' page, selecting the mod from the sidebar, and clicking the 'Config' button. Startup, Common, and Client config options will always be editable at any point. Server configs are only editable in the screen when playing on a world locally. If connected to a server or to another person's LAN world, Server config option will be disabled in the screen. The first page of the config screen for the mod will show every registered config file for players to pick which one to edit.
warning

Translation keys should be added and have the text defined within the lang JSON for all config entries if you are making a screen.

You can specify a translation key for a config by using the `ModConfigSpec$Builder#translation` method, so we can extend the previous code to:

```
``
```



To make translating easier, open the configuration screen and visit all of the configs and their subsections. Then back out to the mod list screen. All untranslated config entries that were encountered will be printed to the console at this point. This makes it easier to know what to translate and what the translation keys are.[PreviousExtensible Enums]()[NextDebug Profiler]()


- [Creating a Configuration]()


- [ConfigValue]()
- [Registering a Configuration]()


- [Configuration Types]()
- [Configuration Events]()
- [Configuration Screen]()Docs


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
        

