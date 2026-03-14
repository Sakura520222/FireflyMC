---
URL: https://docs.neoforged.net/docs/1.21.1/resources/server/advancements
抓取时间: 2026-03-13 22:28:26
源站: NeoForge 1.21.1 官方文档
---






Advancements | NeoForged docs




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
- [Server]()


- [Advancements]()
- [Data Load Conditions]()
- [Damage Types & Damage Sources]()
- [Data Maps]()
- [Enchantments]()
- [Loot Tables]()
- [Recipes]()
- [Tags]()
- [Inventories & Transfers]()
- [Data Storage]()
- [GUIs]()
- [Worldgen]()
- [Networking]()
- [Advanced Topics]()
- [Miscellaneous]()This is documentation for NeoForged **1.21 - 1.21.1**, which is no longer actively maintained.For up-to-date documentation, see the **[latest version]()** (1.21.11).


- []()
- [Resources]()
- Server
- AdvancementsVersion: 1.21 - 1.21.1On this page

# Advancements



Advancements are quest-like tasks that can be achieved by the player. Advancements are awarded based on advancement criteria, and can run behavior when completed.


A new advancement can be added by creating a JSON file in your namespace's `advancement` subfolder. So for example, if we want to add an advancement named `example_name` for a mod with the mod id `examplemod`, it will be located at `data/examplemod/advancement/example_name.json`. An advancement's ID will be relative to the `advancement` directory, so for our example, it would be `examplemod:example_name`. Any name can be chosen, and the advancement will automatically be picked up by the game. Java code is only necessary if you want to add new criteria or trigger a certain criterion from code (see below).


## Specification[​]()



An advancement JSON file may contain the following entries:




- `parent`: The parent advancement ID of this advancement. Circular references will be detected and cause a loading failure. Optional; if absent, this advancement will be considered a root advancement. Root advancements are advancements that have no parent set. They will be the root of their [advancement tree]().

- `display`: The object holding several properties used for display of the advancement in the advancement GUI. Optional; if absent, this advancement will be invisible, but can still be triggered.




- `icon`: A [JSON representation of an item stack]().

- `text`: A [text component]() to use as the advancement's title.

- `description`: A [text component]() to use as the advancement's description.

- `frame`: The frame type of the advancement. Accepts `challenge`, `goal` and `task`. Optional, defaults to `task`.

- `background`: The texture to use for the tree background. This is not relative to the `textures` directory, i.e. the `textures/` folder prefix must be included. Optional, defaults to the missing texture. Only effective on root advancements.

- `show_toast`: Whether to show a toast in the top right corner on completion. Optional, defaults to true.

- `announce_to_chat`: Whether to announce advancement completion in the chat. Optional, defaults to true.

- `hidden`: Whether to hide this advancement and all children from the advancement GUI until it is completed. Has no effect on root advancements themselves, but still hides all of their children. Optional, defaults to false.



- `criteria`: A map of criteria this advancement should track. Every criterion is identified by its map key. A list of criteria triggers added by Minecraft can be found in the `CriteriaTriggers` class, and the JSON specifications can be found on the [Minecraft Wiki](). For implementing your own criteria or triggering criteria from code, see below.

- `requirements`: A list of lists that determine what criteria are required. This is a list of OR lists that are ANDed together, or in other words, every sublist must have at least one criterion matching. Optional, defaults to all criteria being required.

- `rewards`: An object representing the rewards to grant when this advancement is completed. Optional, all values of the object are also optional.




- `experience`: The amount of experience to award to the player.

- `recipes`: A list of [recipe]() IDs to unlock.

- `loot`: A list of [loot tables]() to roll and give to the player.

- `function`: A [function]() to run. If you want to run multiple functions, create a wrapper function that runs all other functions.



- `sends_telemetry_event`: Determines whether telemetry data should be collected when this advancement is completed or not. Only actually does anything if in the `minecraft` namespace. Optional, defaults to false.

- `neoforge:conditions`: NeoForge-added. A list of [conditions]() that must be passed for the advancement to be loaded. Optional.



### Advancement Trees[​]()



Advancement files may be grouped in directories, which tells the game to create multiple advancement tabs. One advancement tab may contain one or more advancement trees, depending on the amount of root advancements. Empty advancement tabs will automatically be hidden.
tip

Minecraft only ever has one root advancement per tab, and always calls the root advancement `root`. It is suggested to follow this practice.


## Criteria Triggers[​]()



To unlock an advancement, the specified criteria must be met. Criteria are tracked through triggers, which are executed from code when the associated action happens (e.g. the `player_killed_entity` trigger executes when the player kills the specified entity). Any time an advancement is loaded into the game, the criteria defined are read and added as listeners to the trigger. When a trigger is executed, all advancements that have a listener for the corresponding criterion are rechecked for completion. If the advancement is completed, the listeners are removed.


Custom criteria triggers are made up of two parts: the trigger, which is activated in code by calling `#trigger`, and the instance which defines the conditions under which the trigger should award the criterion. The trigger extends `SimpleCriterionTrigger<T>` while the instance implements `SimpleCriterionTrigger.SimpleInstance`. The generic value `T` represents the trigger instance type.


### `SimpleCriterionTrigger.SimpleInstance`[​]()



A `SimpleCriterionTrigger.SimpleInstance` represents a single criterion defined in the `criteria` object. Trigger instances are responsible for holding the defined conditions, and returning whether the inputs match the condition.


Conditions are usually passed in through the constructor. The `SimpleCriterionTrigger.SimpleInstance` interface requires only one function, called `#player`, which returns the conditions the player must meet as an `Optional<ContextAwarePredicate>`. If the subclass is a record with a `player` parameter of this type (as below), the automatically generated `#player` method will suffice.


```
``
```




Typically, trigger instances have static helper methods which construct the full `Criterion<T>` object from the arguments to the instance. This allows these instances to be easily created during data generation, but are optional.


```
``
```




Finally, a method should be added which takes in the current data state and returns whether the user has met the necessary conditions. The conditions of the player are already checked through `SimpleCriterionTrigger#trigger(ServerPlayer, Predicate)`. Most trigger instances call this method `#matches`.


```
``
```




### `SimpleCriterionTrigger`[​]()



The `SimpleCriterionTrigger<T>` implementation has two purposes: supplying a method to check trigger instances and run attached listeners on success, and specifying a [codec]() to serialize the trigger instance (`T`).


First, we want to add a method that takes the inputs we need and calls `SimpleCriterionTrigger#trigger` to properly handle checking all listeners. Most trigger instances also name this method `#trigger`. Reusing our example trigger instance from above, our trigger would look something like this:


```
``
```




Triggers must be registered to the `Registries.TRIGGER_TYPE` [registry]():


```
``
```




And then, triggers must define a [codec]() to serialize and deserialize the trigger instance by overriding `#codec`. This codec is typically created as a constant within the instance implementation.


```
``
```




For the earlier example of a record with a `ContextAwarePredicate` and an `ItemPredicate`, the codec could be:


```
``
```




### Calling Criterion Triggers[​]()



Whenever the action being checked is performed, the `#trigger` method defined by our `SimpleCriterionTrigger` subclass should be called. Of course, you can also call on vanilla triggers, which are found in `CriteriaTriggers`.


```
``
```




## Data Generation[​]()



Advancements can be [datagenned]() using an `AdvancementProvider`. An `AdvancementProvider` accepts a list of `AdvancementGenerator`s, which actually generate the advancements using `Advancement.Builder`.
warning

Both Minecraft and NeoForge provide a class named `AdvancementProvider`, located at `net.minecraft.data.advancements.AdvancementProvider` and `net.neoforged.neoforge.common.data.AdvancementProvider`, respectively. The NeoForge class is an improvement on the one Minecraft provides, and should always be used in favor of the Minecraft one. The following documentation always assumes usage of the NeoForge `AdvancementProvider` class.


To start, create a subclass of `AdvancementProvider`:


```
``
```




Now, the next step is to fill the list with our generators. To do so, we add one or more generators as static classes and then add an instance of each of them to the currently empty list in the constructor parameter.


```
``
```




To generate an advancement, you want to use an `Advancement.Builder`:


```
``
```




Of course, don't forget to add your provider to the `GatherDataEvent`:


```
``
```

[PreviousTextures]()[NextData Load Conditions]()


- [Specification]()


- [Advancement Trees]()
- [Criteria Triggers]()


- [`SimpleCriterionTrigger.SimpleInstance`]()
- [`SimpleCriterionTrigger`]()
- [Calling Criterion Triggers]()
- [Data Generation]()Docs


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
        

