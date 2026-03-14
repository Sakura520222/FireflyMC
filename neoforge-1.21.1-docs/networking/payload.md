---
URL: https://docs.neoforged.net/docs/1.21.1/networking/payload
抓取时间: 2026-03-13 22:28:37
源站: NeoForge 1.21.1 官方文档
---






Registering Payloads | NeoForged docs




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
- Registering PayloadsVersion: 1.21 - 1.21.1On this page

# Registering Payloads



Payloads are a way to send arbitrary data between the client and the server. They are registered using the `PayloadRegistrar` from the `RegisterPayloadHandlersEvent` event.


```
``
```




Assuming we want to send the following data:


```
``
```




Then we can implement the `CustomPacketPayload` interface to create a payload that can be used to send and receive this data.


```
``
```




As you can see from the example above the `CustomPacketPayload` interface requires us to implement the `type` method. The `type` method is responsible for returning a unique identifier for this payload. We then also need a reader to register this later on with the `StreamCodec` to read and write the payload data.


Finally, we can register this payload with the registrar:


```
``
```




Dissecting the code above we can notice a couple of things:




- The registrar has `play*` methods, that can be used for registering payloads which are sent during the play phase of the game.




- Not visible in this code are the methods `configuration*` and `common*`; however, they can also be used to register payloads for the configuration phase. The `common` method can be used to register payloads for both the configuration and play phase simultaneously.



- The registrar uses a `*Bidirectional` method, that can be used for registering payloads which are sent to both the logical server and logical client.




- Not visible in this code are the methods `*ToClient` and `*ToServer`; however, they can also be used to register payloads to only the logical client or only the logical server, respectively.



- The type of the payload is used as a unique identifier for the payload.

- The [stream codec]() is used to read and write the payload to and from the buffer sent across the network

- The payload handler is a callback for when the payload arrives on one of the logical sides.




- If a `*Bidirectional` method is used, a `DirectionalPayloadHandler` can be used to provide two separate payload handlers for each of the logical sides.





Now that we have registered the payload we need to implement a handler. For this example we will specifically take a look at the client side handler, however the server side handler is very similar.


```
``
```




Here a couple of things are of note:




- The handling method here gets the payload, and a contextual object.

- The handling method of the payload is, by default, invoked on the main thread.



If you need to do some computation that is resource intensive, then the work should be done on the network thread, instead of blocking the main thread. This is done by setting the `HandlerThread` of the `PayloadRegistrar` to `HandlerThread#NETWORK` via `PayloadRegistrar#executesOn` before registering the payload.


```
``
```


note

All payloads registered after an `executesOn` call will retain the same thread execution location until `executesOn` is called again.

```
``
```




Here a couple of things are of note:




- If you want to run code on the main game thread you can use `enqueueWork` to submit a task to the main thread.




- The method will return a `CompletableFuture` that will be completed on the main thread.

- Notice: A `CompletableFuture` is returned, this means that you can chain multiple tasks together, and handle exceptions in a single place.

- If you do not handle the exception in the `CompletableFuture` then it will be swallowed, **and you will not be notified of it**.





```
``
```




With your own payloads you can then use those to configure the client and server using [Configuration Tasks]().


## Sending Payloads[​]()



`CustomPacketPayload`s are sent across the network using vanilla's packet system by wrapping the payload via `ServerboundCustomPayloadPacket` when sending to the server, or `ClientboundCustomPayloadPacket` when sending to the client. Payloads sent to the client can only contain at most 1 MiB of data while payloads to the server can only contain less than 32 KiB.


All payloads are sent via `Connection#send` with some level of abstraction; however, it is generally inconvenient to call these methods if you want to send packets to multiple people based on a given condition. Therefore, `PacketDistributor` contains a number of convenience implementations to send payloads. There is only one method to send packets to the server (`sendToServer`); however, there are numerous methods to send packets to the client depending on which players should receive the payload.


```
``
```




See the `PacketDistributor` class for more implementations.[PreviousNetworking]()[NextStream Codecs]()


- [Sending Payloads]()Docs


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
        

