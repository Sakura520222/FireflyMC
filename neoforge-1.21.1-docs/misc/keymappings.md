---
URL: https://docs.neoforged.net/docs/1.21.1/misc/keymappings
抓取时间: 2026-03-13 22:28:02
源站: NeoForge 1.21.1 官方文档
---






Key Mappings | NeoForged docs




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
- Key MappingsVersion: 1.21 - 1.21.1On this page

# Key Mappings



A key mapping, or key binding, defines a particular action that should be tied to an input: mouse click, key press, etc. Each action defined by a key mapping can be checked whenever the client can take an input. Furthermore, each key mapping can be assigned to any input through the [Controls option menu]().


## Registering a `KeyMapping`[​]()



A `KeyMapping` can be registered by listening to the `RegisterKeyMappingsEvent` on the [mod event bus]() only on the physical client and calling `#register`.


```
``
```




## Creating a `KeyMapping`[​]()



A `KeyMapping` can be created using it's constructor. The `KeyMapping` takes in a [translation key]() defining the name of the mapping, the default input of the mapping, and the [translation key]() defining the category the mapping will be put within in the [Controls option menu]().
tip

A `KeyMapping` can be added to a custom category by providing a category [translation key]() not provided by vanilla. Custom category translation keys should contain the mod id (e.g. `key.categories.examplemod.examplecategory`).


### Default Inputs[​]()



Each key mapping has a default input associated with it. This is provided through `InputConstants.Key`. Each input consists of an `InputConstants.Type`, which defines what device is providing the input, and an integer, which defines the associated identifier of the input on the device.


Vanilla provides three types of inputs: `KEYSYM`, which defines a keyboard through the provided `GLFW` key tokens, `SCANCODE`, which defines a keyboard through the platform-specific scancode, and `MOUSE`, which defines a mouse.
note

It is highly recommended to use `KEYSYM` over `SCANCODE` for keyboards as `GLFW` key tokens are not tied to any particular system. You can read more on the [GLFW docs]().


The integer is dependent on the type provided. All input codes are defined in `GLFW`: `KEYSYM` tokens are prefixed with `GLFW_KEY_*` while `MOUSE` codes are prefixed with `GLFW_MOUSE_*`.


```
``
```


note

If the key mapping should not be mapped to a default, the input should be set to `InputConstants#UNKNOWN`. The vanilla constructor will require you to extract the input code via `InputConstants$Key#getValue` while the NeoForge constructor can be supplied the raw input field.


### `IKeyConflictContext`[​]()



Not all mappings are used in every context. Some mappings are only used in a GUI, while others are only used purely in game. To avoid mappings of the same key used in different contexts conflicting with each other, an `IKeyConflictContext` can be assigned.


Each conflict context contains two methods: `#isActive`, which defines if the mapping can be used in the current game state, and `#conflicts`, which defines whether the mapping conflicts with a key in the same or different conflict context.


Currently, NeoForge defines three basic contexts through `KeyConflictContext`: `UNIVERSAL`, which is the default meaning the key can be used in every context, `GUI`, which means the mapping can only be used when a `Screen` is open, and `IN_GAME`, which means the mapping can only be used if a `Screen` is not open. New conflict contexts can be created by implementing `IKeyConflictContext`.


```
``
```




### `KeyModifier`[​]()



Modders may not want mappings to have the same behavior if a modifier key is held at the same (e.g. `G` vs `CTRL + G`). To remedy this, NeoForge adds an additional parameter to the constructor to take in a `KeyModifier` which can apply control (`KeyModifier#CONTROL`), shift (`KeyModifier#SHIFT`), or alt (`KeyModifier#ALT`) to any input. `KeyModifier#NONE` is the default and will apply no modifier.


A modifier can be added in the [controls option menu]() by holding down the modifier key and the associated input.


```
``
```




## Checking a `KeyMapping`[​]()



A `KeyMapping` can be checked to see whether it has been clicked. Depending on when, the mapping can be used in a conditional to apply the associated logic.


### Within the Game[​]()



Within the game, a mapping should be checked by listening to `ClientTickEvent.Post` on the [event bus]() and checking `KeyMapping#consumeClick` within a while loop. `#consumeClick` will return `true` only the number of times the input was performed and not already previously handled, so it won't infinitely stall the game.


```
``
```


caution

Do not use the `InputEvent`s as an alternative to `ClientTickEvent.Post`. There are separate events for keyboard and mouse inputs only, so they wouldn't handle any additional inputs.


### Inside a GUI[​]()



Within a GUI, a mapping can be checked within one of the `GuiEventListener` methods using `IKeyMappingExtension#isActiveAndMatches`. The most common methods which can be checked are `#keyPressed` and `#mouseClicked`.


`#keyPressed` takes in the `GLFW` key token, the platform-specific scan code, and a bitfield of the held down modifiers. A key can be checked against a mapping by creating the input using `InputConstants#getKey`. The modifiers are already checked within the mapping methods itself.


```
``
```


note

If you do not own the screen which you are trying to check a **key** for, you can listen to the `Pre` or `Post` events of `ScreenEvent.KeyPressed` on the [event bus]() instead.


`#mouseClicked` takes in the mouse's x position, y position, and the button clicked. A mouse button can be checked against a mapping by creating the input using `InputConstants.Type#getOrCreate` with the `MOUSE` input.


```
``
```


note

If you do not own the screen which you are trying to check a **mouse** for, you can listen to the `Pre` or `Post` events of `ScreenEvent.MouseButtonPressed` on the [event bus]() instead.[PreviousGame Tests]()[NextResource Locations]()


- [Registering a `KeyMapping`]()
- [Creating a `KeyMapping`]()


- [Default Inputs]()
- [`IKeyConflictContext`]()
- [`KeyModifier`]()
- [Checking a `KeyMapping`]()


- [Within the Game]()
- [Inside a GUI]()Docs


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
        

