---
URL: https://docs.neoforged.net/docs/1.21.1/networking/entities
抓取时间: 2026-03-13 22:28:36
源站: NeoForge 1.21.1 官方文档
---






Entities | NeoForged docs




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
- EntitiesVersion: 1.21 - 1.21.1On this page

# Entities



In addition to regular network messages, there are various other systems provided to handle synchronizing entity data.


## Spawn Data[​]()



Since 1.20.2 Mojang introduced the concept of Bundle packets, which are used to send entity spawn packets together. This allows for more data to be sent with the spawn packet, and for that data to be sent more efficiently.


You can add extra data to the spawn packet NeoForge sends by implementing the following interface.


### IEntityWithComplexSpawn[​]()



If your entity has data that is needed on the client, but does not change over time, then it can be added to the entity spawn packet using this interface. `#writeSpawnData` and `#readSpawnData` control how the data should be encoded to/decoded from the network buffer. Alternatively you can override the method `IEntityExtension#sendPairingData` which is called when the entity's initial data is sent to the client. This method is called on the server, and can be used to send additional payloads to the client within the same bundle as the spawn packet.


## Dynamic Data Parameters[​]()



This is the main vanilla system for synchronizing entity data from the server to the client. As such, a number of vanilla examples are available to refer to.


Firstly, you need a `EntityDataAccessor<T>` for the data you wish to keep synchronized. This should be stored as a `static final` field in your entity class, obtained by calling `SynchedEntityData#defineId` and passing the entity class and a serializer for that type of data. The available serializer implementations can be found as static constants within the `EntityDataSerializers` class.
caution

You should **only** create data parameters for your own entities, *within that entity's class*. Adding parameters to entities you do not control can cause the IDs used to send that data over the network to become desynchronized, causing difficult to debug crashes.


Then, override `Entity#defineSynchedData` and call `SynchedEntityData.Builder#define` for each of your data parameters, passing the parameter and an initial value to use. Remember to always call the `super` method first!


You can then get and set these values via your entity's `entityData` instance. Changes made will be synchronized to the client automatically.[PreviousUsing Configuration Tasks]()[NextAccess Transformers]()


- [Spawn Data]()


- [IEntityWithComplexSpawn]()
- [Dynamic Data Parameters]()Docs


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
        

