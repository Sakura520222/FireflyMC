---
URL: https://docs.neoforged.net/docs/1.21.1/networking/configuration-tasks
抓取时间: 2026-03-13 22:28:35
源站: NeoForge 1.21.1 官方文档
---






Using Configuration Tasks | NeoForged docs




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


- [Registering Payloads]()
- [Stream Codecs]()
- [Using Configuration Tasks]()
- [Entities]()
- [Advanced Topics]()
- [Miscellaneous]()This is documentation for NeoForged **1.21 - 1.21.1**, which is no longer actively maintained.For up-to-date documentation, see the **[latest version]()** (1.21.11).


- []()
- [Networking]()
- Using Configuration TasksVersion: 1.21 - 1.21.1On this page

# Using Configuration Tasks



The networking protocol for the client and server has a specific phase where the server can configure the client before the player actually joins the game. This phase is called the configuration phase, and is for example used by the vanilla server to send the resource pack information to the client.


This phase can also be used by mods to configure the client before the player joins the game.


## Registering a configuration task[​]()



The first step to using the configuration phase is to register a configuration task. This can be done by registering a new configuration task in the `RegisterConfigurationTasksEvent` event.


```
``
```




The `RegisterConfigurationTasksEvent` event is fired on the mod bus, and exposes the current listener used by the server to configure the relevant client. A modder can use the exposed listener to figure out if the client is running the mod, and if so, register a configuration task.


## Implementing a configuration task[​]()



A configuration task is a simple interface: `ICustomConfigurationTask`. This interface has two methods: `void run(Consumer<CustomPacketPayload> sender);`, and `ConfigurationTask.Type type();` which returns the type of the configuration task. The type is used to identify the configuration task. An example of a configuration task is shown below:


```
``
```




## Acknowledging a configuration task[​]()



Your configuration is executed on the server, and the server needs to know when the next configuration task can be executed. This is done by acknowledging the execution of said configuration task.


There are two primary ways of achieving this:


### Capturing the listener[​]()



When the client does not need to acknowledge the configuration task, then the listener can be captured, and the configuration task can be acknowledged directly on the server side.


```
``
```




To use such a configuration task, the listener needs to be captured in the `RegisterConfigurationTasksEvent` event.


```
``
```




Then the next configuration task will be executed immediately after the current configuration task has completed, and the client does not need to acknowledge the configuration task. Additionally, the server will not wait for the client to properly process the send payloads.


### Acknowledging the configuration task[​]()



When the client needs to acknowledge the configuration task, then you will need to send your own payload to the client:


```
``
```




When a payload from a server side configuration task is properly processed you can send this payload to the server to acknowledge the configuration task.


```
``
```




Where `onMyData` is the handler for the payload that was sent by the server side configuration task.


When the server receives this payload it will acknowledge the configuration task, and the next configuration task will be executed:


```
``
```




Where `onAck` is the handler for the payload that was sent by the client.


## Stalling the login process[​]()



When the configuration is not acknowledged, then the server will wait forever, and the client will never join the game. So it is important to always acknowledge the configuration task, unless the configuration task failed, then you can disconnect the client.[PreviousStream Codecs]()[NextEntities]()


- [Registering a configuration task]()
- [Implementing a configuration task]()
- [Acknowledging a configuration task]()


- [Capturing the listener]()
- [Acknowledging the configuration task]()
- [Stalling the login process]()Docs


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
        

