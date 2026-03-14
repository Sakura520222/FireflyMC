---
URL: https://docs.neoforged.net/docs/1.21.1/resources/client/textures
抓取时间: 2026-03-13 22:28:24
源站: NeoForge 1.21.1 官方文档
---






Textures | NeoForged docs




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
- TexturesVersion: 1.21 - 1.21.1On this page

# Textures



All textures in Minecraft are PNG files located within a namespace's `textures` folder. JPG, GIF and other image formats are not supported. The path of [resource locations]() referring to textures is generally relative to the `textures` folder, so for example, the resource location `examplemod:block/example_block` refers to the texture file at `assets/examplemod/textures/block/example_block.png`.


Textures should generally be in sizes that are powers of two, for example 16x16 or 32x32. Unlike older versions, modern Minecraft natively supports block and item texture sizes greater than 16x16. For textures that are not in powers of two that you render yourself anyway (for example GUI backgrounds), create an empty file in the next available power-of-two size (often 256x256), and add your texture in the top left corner of that file, leaving the rest of the file empty. The actual size of the drawn texture can then be set in the code that uses the texture.


## Texture Metadata[​]()



Texture metadata can be specified in a file named exactly the same as the texture, with an additional `.mcmeta` suffix. For example, an animated texture at `textures/block/example.png` would need an accompanying `textures/block/example.png.mcmeta` file. The `.mcmeta` file has the following format (all optional):


```
``
```




## Animated Textures[​]()



Minecraft natively supports animated textures for blocks and items. Animated textures consist of a texture file where the different animation stages are located below each other (for example, an animated 16x16 texture with 8 phases would be represented through a 16x128 PNG file).


To actually be animated and not just be displayed as a distorted texture, there must be an `animation` object in the texture metadata. The sub-object can be empty, but may contain the following optional entries:


```
``
```

[PreviousSounds]()[NextAdvancements]()


- [Texture Metadata]()
- [Animated Textures]()Docs


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
        

