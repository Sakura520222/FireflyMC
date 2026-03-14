---
URL: https://docs.neoforged.net/docs/1.21.1/resources/client/particles
抓取时间: 2026-03-13 22:28:15
源站: NeoForge 1.21.1 官方文档
---






Particles | NeoForged docs




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


- [Client]()


- [I18n and L10n]()
- [Models]()
- [Particles]()
- [Sounds]()
- [Textures]()
- [Server]()
- [Inventories & Transfers]()
- [Data Storage]()
- [GUIs]()
- [Worldgen]()
- [Networking]()
- [Advanced Topics]()
- [Miscellaneous]()This is documentation for NeoForged **1.21 - 1.21.1**, which is no longer actively maintained.For up-to-date documentation, see the **[latest version]()** (1.21.11).


- []()
- [Resources]()
- Client
- ParticlesVersion: 1.21 - 1.21.1On this page

# Particles



Particles are 2D effects that polish the game and add immersion. They can be spawned both client and server [side](), but being mostly visual in nature, critical parts exist only on the physical (and logical) client side.


## Registering Particles[​]()



### `ParticleType`[​]()



Particles are registered using `ParticleType`s. These work similar to `EntityType`s or `BlockEntityType`s, in that there's a `Particle` class - every spawned particle is an instance of that class -, and then there's the `ParticleType` class, holding some common information, that is used for registration. `ParticleType`s are a [registry](), which means that we want to register them using a `DeferredRegister` like all other registered objects:


```
``
```


info

A `ParticleType` is only necessary if you need to work with particles on the server side. The client can also use `Particle`s directly.


### `Particle`[​]()



A `Particle` is what is later spawned into the world and displayed to the player. While you may extend `Particle` and implement things yourself, in many cases it will be better to extend `TextureSheetParticle` instead, as this class provides helpers for things such as animating and scaling, and also does the actual rendering for you (all of which you'd need to implement yourself if extending `Particle` directly).


Most properties of `Particle`s are controlled by fields such as `gravity`, `lifetime`, `hasPhysics`, `friction`, etc. The only two methods that make sense to implement yourself are `tick` and `move`, both of which do exactly what you'd expect. As such, custom particle classes are often short, consisting e.g. only of a constructor that sets some fields and lets the superclass handle the rest. A basic implementation would look somewhat like this:


```
``
```




### `ParticleProvider`[​]()



Next, particle types must register a `ParticleProvider`. `ParticleProvider` is a client-only class responsible for actually creating our `Particle`s through the `createParticle` method. While more elaborate code can be included here, many particle providers are as simple as this:


```
``
```




Your particle provider must then be associated with the particle type in the [client-side]() [mod bus]() [event]() `RegisterParticleProvidersEvent`:


```
``
```




### Particle Descriptions[​]()



Finally, we must associate our particle type with a texture. Similar to how items are associated with an item model, we associate our particle type with what is known as a particle description. A particle description is a JSON file in the `assets/<namespace>/particles` directory and has the same name as the particle type (so for example `my_particle.json` for the above example). The particle definition JSON has the following format:


```
``
```




A particle definition is required when using a particle that takes in a `SpriteSet`, which is done when registering a particle provider via `registerSpriteSet` or `registerSprite`. They must **not** be provided for particle providers registered via `#registerSpecial`.
danger

A mismatched list of sprite set particle factories and particle definition files, i.e. a particle description without a corresponding particle factory, or vice versa, will throw an exception!
note

While particle descriptions must have providers registered a certain way, they are only used if the `ParticleRenderType` (set via `Particle#getRenderType`) uses the `TextureAtlas#LOCATION_PARTICLES` as the shader texture. For vanilla render types, these are `PARTICLE_SHEET_OPAQUE`, `PARTICLE_SHEET_TRANSLUCENT`, and `PARTICLE_SHEET_LIT`.


### Datagen[​]()



Particle definition files can also be [datagenned]() by extending `ParticleDescriptionProvider` and overriding the `#addDescriptions()` method:


```
``
```




Don't forget to add the provider to the `GatherDataEvent`:


```
``
```




### Custom `ParticleType`s[​]()



While for most cases `SimpleParticleType` suffices, it is sometimes necessary to attach additional data to the particle on the server side. This is where a custom `ParticleType` and an associated custom `ParticleOptions` are required. Let's start with the `ParticleOptions`, as that is where the information is actually stored:


```
``
```




We then use this `ParticleOptions` implementation in our custom `ParticleType`...


```
``
```




... and reference it during registration:


```
``
```




## Spawning Particles[​]()



As a reminder from before, the server only knows `ParticleType`s and `ParticleOption`s, while the client works directly with `Particle`s provided by `ParticleProvider`s that are associated with a `ParticleType`. Consequently, the ways in which particles are spawned are vastly different depending on the side you are on.




- **Common code**: Call `Level#addParticle` or `Level#addAlwaysVisibleParticle`. This is the preferred way of creating particles that are visible to everyone.

- **Client code**: Use the common code way. Alternatively, create a `new Particle()` with the particle class of your choice and call `Minecraft.getInstance().particleEngine#add(Particle)` with that particle. Note that particles added this way will only display for the client and thus not be visible to other players.

- **Server code**: Call `ServerLevel#sendParticles`. Used in vanilla by the `/particle` command.
[PreviousCustom Model Loaders]()[NextSounds]()


- [Registering Particles]()


- [`ParticleType`]()
- [`Particle`]()
- [`ParticleProvider`]()
- [Particle Descriptions]()
- [Datagen]()
- [Custom `ParticleType`s]()
- [Spawning Particles]()Docs


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
        

