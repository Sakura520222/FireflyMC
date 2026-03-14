---
URL: https://docs.neoforged.net/docs/1.21.1/resources/client/sounds
抓取时间: 2026-03-13 22:28:23
源站: NeoForge 1.21.1 官方文档
---






Sounds | NeoForged docs




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
- SoundsVersion: 1.21 - 1.21.1On this page

# Sounds



Sounds, while not required for anything, can make a mod feel much more nuanced and alive. Minecraft offers you various ways to register and play sounds, which will be laid out in this article.


## Terminology[​]()



The Minecraft sound engine uses a variety of terms to refer to different things:




- **Sound event**: A sound event is an in-code trigger that tells the sound engine to play a certain sound. `SoundEvent`s are also the things you register to the game.

- **Sound category** or **sound source**: Sound categories are rough groupings of sounds that can be individually toggled. The sliders in the sound options GUI represent these categories: `master`, `block`, `player` etc. In code, they can be found in the `SoundSource` enum.

- **Sound definition**: A mapping of a sound event to one or multiple sound objects, plus some optional metadata. Sound definitions are located in a namespace's [`sounds.json` file]().

- **Sound object**: A JSON object consisting of a sound file location, plus some optional metadata.

- **Sound file**: An on-disk sound file. Minecraft only supports `.ogg` sound files.

danger

Due to the implementation of OpenAL (Minecraft's audio library), for your sound to have attenuation - that is, for it to get quieter and louder depending on the player's distance to it -, your sound file must be mono (single channel). Stereo (multichannel) sound files will not be subject to attenuation and always play at the player's location, making them ideal for ambient sounds and background music. See also [MC-146721]().


## Creating `SoundEvent`s[​]()



`SoundEvent`s are [registered objects](), meaning that they must be registered to the game through a `DeferredRegister` and be singletons:


```
``
```




Of course, don't forget to add your registry to the [mod event bus]() in the [mod constructor]():


```
``
```




And voilà, you have a sound event!


## `sounds.json`[​]()



*See also: [sounds.json]() on the [Minecraft Wiki]()*


Now, to connect your sound event to actual sound files, we need to create sound definitions. All sound definitions for a namespace are stored in a single file named `sounds.json`, also known as the sound definitions file, directly in the namespace's root. Every sound definition is a mapping of sound event id (e.g. `my_sound`) to a JSON sound object. Note that the sound event ids do not specify a namespace, as that is already determined by the namespace the sound definitions file is in. An example `sounds.json` would look something like this:


```
``
```




### Merging[​]()



Unlike most other resource files, `sounds.json` do not overwrite values in packs below them. Instead, they are merged together and then interpreted as one combined `sounds.json` file. Consider sounds `sound_1`, `sound_2`, `sound_3` and `sound_4` being defined in two `sounds.json` files from two different resource packs RP1 and RP2, where RP2 is placed below RP1:


`sounds.json` in RP1:


```
``
```




`sounds.json` in RP2:


```
``
```




The combined (merged) `sounds.json` file the game would then go on and use to load sounds would look something look this (only in memory, this file is never written anywhere):


```
``
```




## Playing Sounds[​]()



Minecraft offers various methods to play sounds, and it is sometimes unclear which one should be used. All methods accept a `SoundEvent`, which can either be your own or a vanilla one (vanilla sound events are found in the `SoundEvents` class). For the following method descriptions, client and server refer to the [logical client and logical server](), respectively.


### `Level`[​]()





- `playSeededSound(Player player, double x, double y, double z, Holder<SoundEvent> soundEvent, SoundSource soundSource, float volume, float pitch, long seed)`




- Client behavior: If the player passed in is the local player, play the sound event to the player at the given location, otherwise no-op.

- Server behavior: A packet instructing the client to play the sound event to the player at the given location is sent to all players except the one passed in.

- Usage: Call from client-initiated code that will run on both sides. The server not playing it to the initiating player prevents playing the sound event twice to them. Alternatively, call from server-initiated code (e.g. a [block entity][be]) with a `null` player to play the sound to everyone.



- `playSound(Player player, double x, double y, double z, SoundEvent soundEvent, SoundSource soundSource, float volume, float pitch)`




- Forwards to `playSeededSound` with a random seed selected and the holder wrapped around the `SoundEvent`



- `playSound(Player player, BlockPos pos, SoundEvent soundEvent, SoundSource soundSource, float volume, float pitch)`




- Forwards to the above method with `x`, `y` and `z` taking the values of `pos.getX() + 0.5`, `pos.getY() + 0.5` and `pos.getZ() + 0.5`, respectively.



- `playLocalSound(double x, double y, double z, SoundEvent soundEvent, SoundSource soundSource, float volume, float pitch, boolean distanceDelay)`




- Client behavior: Plays the sound to the player at the given location. Does not send anything to the server. If `distanceDelay` is `true`, delays the sound based on the distance to the player.

- Server behavior: No-op.

- Usage: Called from custom packets sent from the server. Vanilla uses this for thunder sounds.





### `ClientLevel`[​]()





- `playLocalSound(BlockPos pos, SoundEvent soundEvent, SoundSource soundSource, float volume, float pitch, boolean distanceDelay)`




- Forwards to `Level#playLocalSound` with `x`, `y` and `z` taking the values of `pos.getX() + 0.5`, `pos.getY() + 0.5` and `pos.getZ() + 0.5`, respectively.





### `Entity`[​]()





- `playSound(SoundEvent soundEvent, float volume, float pitch)`




- Forwards to `Level#playSound` with `null` as the player, `Entity#getSoundSource` as the sound source, the entity's position for x/y/z, and the other parameters passed in.





### `Player`[​]()





- `playSound(SoundEvent soundEvent, float volume, float pitch)` (overrides the method in `Entity`)




- Forwards to `Level#playSound` with `this` as the player, `SoundSource.PLAYER` as the sound source, the player's position for x/y/z, and the other parameters passed in. As such, the client/server behavior mimics the one from `Level#playSound`:




- Client behavior: Play the sound event to the client player at the given location.

- Server behavior: Play the sound event to everyone near the given location except the player this method was called on.







## Datagen[​]()



Sound files themselves can of course not be [datagenned](), but `sounds.json` files can. To do so, we extend `SoundDefinitionsProvider` and override the `registerSounds()` method:


```
``
```




As with every data provider, don't forget to register the provider to the event:


```
``
```

[PreviousParticles]()[NextTextures]()


- [Terminology]()
- [Creating `SoundEvent`s]()
- [`sounds.json`]()


- [Merging]()
- [Playing Sounds]()


- [`Level`]()
- [`ClientLevel`]()
- [`Entity`]()
- [`Player`]()
- [Datagen]()Docs


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
        

