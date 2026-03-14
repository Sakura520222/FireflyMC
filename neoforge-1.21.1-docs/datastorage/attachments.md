---
URL: https://docs.neoforged.net/docs/1.21.1/datastorage/attachments
抓取时间: 2026-03-13 22:27:49
源站: NeoForge 1.21.1 官方文档
---






Data Attachments | NeoForged docs




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
- Data AttachmentsVersion: 1.21 - 1.21.1On this page

# Data Attachments



The data attachment system allows mods to attach and store additional data on block entities, chunks, and entities.


*To store additional level data, you can use [SavedData]().*
note

Data attachments for item stacks have been superceeded by vanilla's [data components]().


## Creating an attachment type[​]()



To use the system, you need to register an `AttachmentType`. The attachment type contains the following configuration:




- A default value supplier to create the instance when the data is first accessed.

- An optional serializer if the attachment should be persisted.

- (If a serializer was configured) The `copyOnDeath` flag to automatically copy entity data on death (see below).

tip

If you don't want your attachment to persist, do not provide a serializer.


There are a few ways to provide an attachment serializer: directly implementing `IAttachmentSerializer`, implementing `INBTSerializable` and using the static `AttachmentType#serializable` method to create the builder, or providing a codec to the builder.


In any case, the attachment **must be registered** to the `NeoForgeRegistries.ATTACHMENT_TYPES` registry. Here is an example:


```
``
```




## Using the attachment type[​]()



Once the attachment type is registered, it can be used on any holder object. Calling `getData` if no data is present will attach a new default instance.


```
``
```




If attaching a default instance is not desired, a `hasData` check can be added:


```
``
```




The data can also be updated with `setData`:


```
``
```


important

Usually, block entities and chunks need to be marked as dirty when they are modified (with `setChanged` and `setUnsaved(true)`). This is done automatically for calls to `setData`:

```
``
```



but if you modify some data that you obtained from `getData` (including a newly created default instance) then you must mark block entities and chunks as dirty explicitly:

```
``
```




## Sharing data with the client[​]()



To sync block entity, chunk, or entity attachments to a client, you need to [send a packet to the client]() yourself. For chunks, you can use `ChunkWatchEvent.Sent` to know when to send chunk data to a player.


## Copying data on player death[​]()



By default, entity data attachments are not copied on player death. To automatically copy an attachment on player death, set `copyOnDeath` in the attachment builder.


More complex handling can be implemented via `PlayerEvent.Clone` by reading the data from the original entity and assigning it to the new entity. In this event, the `#isWasDeath` method can be used to distinguish between respawning after death and returning from the End. This is important because the data will already exist when returning from the End, so care has to be taken to not duplicate values in this case.


For example:


```
``
```

[PreviousCodecs]()[NextSaved Data]()


- [Creating an attachment type]()
- [Using the attachment type]()
- [Sharing data with the client]()
- [Copying data on player death]()Docs


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
        

