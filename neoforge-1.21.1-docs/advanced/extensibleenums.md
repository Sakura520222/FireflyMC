---
URL: https://docs.neoforged.net/docs/1.21.1/advanced/extensibleenums
抓取时间: 2026-03-13 22:27:44
源站: NeoForge 1.21.1 官方文档
---






Extensible Enums | NeoForged docs




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


- [Access Transformers]()
- [Extensible Enums]()
- [Miscellaneous]()This is documentation for NeoForged **1.21 - 1.21.1**, which is no longer actively maintained.For up-to-date documentation, see the **[latest version]()** (1.21.11).


- []()
- Advanced Topics
- Extensible EnumsVersion: 1.21 - 1.21.1On this page

# Extensible Enums



Extensible Enums are an enhancement of specific Vanilla enums to allow new entries to be added. This is done by modifying the compiled bytecode of the enum at runtime to add the elements.


## `IExtensibleEnum`[​]()



All enums that can have new entries implement the `IExtensibleEnum` interface. This interface acts as a marker to allow the `RuntimeEnumExtender` launch plugin service to know what enums should be transformed.
warning

You should **not** be implementing this interface on your own enums. Use maps or registries instead depending on your usecase.
Enums which are not patched to implement the interface cannot have the interface added to them via mixins or coremods due to the order the transformers run in.


### Creating an Enum Entry[​]()



To create new enum entries, a JSON file needs to be created and referenced in the `neoforge.mods.toml` with the `enumExtensions` entry of a `[[mods]]` block. The specified path must be relative to the `resources` directory:


```
``
```




The definition of the entry consists of the target enum's class name, the new field's name (must be prefixed with the mod ID), the descriptor of the constructor to use for constructing the entry and the parameters to be passed to said constructor.


```
``
```




```
``
```




#### Constructor[​]()



The constructor must be specified as a [method descriptor]() and must only contain the parameters visible in the source code, omitting the hidden constant name and ordinal parameters.
If a constructor is marked with the `@ReservedConstructor` annotation, then it cannot be used for modded enum constants.


#### Parameters[​]()



The parameters can be specified in three ways with limitations depending on the parameter types:




- Inline in the JSON file as an array of constants (only allowed for primitive values, Strings and for passing null to any reference type)

- As a reference to a field of type `EnumProxy<TheEnum>` in a class from the mod (see `EnumProxy` example above)




- The first parameter specifies the target enum and the subsequent parameters are the ones to be passed to the enum constructor



- As a reference to a method returning `Object`, where the return value is the parameter value to use. The method must have exactly two parameters of type `int` (index of the parameter) and `Class<?>` (expected type of the parameter)




- The `Class<?>` object should be used to cast (`Class#cast()`) the return value in order to keep `ClassCastException`s in mod code.



warning

The fields and/or methods used as sources for parameter values should be in a separate class to avoid unintentionally loading mod classes too early.


Certain parameters have additional rules:




- If the parameter is an int ID parameter related to a `@IndexedEnum` annotation on the enum, then it is ignored and replaced by the entry's ordinal. If said parameter is specified inline in the JSON, then it must be specified as `-1`, otherwise an exception is thrown.

- If the parameter is a String name parameter related to a `@NamedEnum` annotation on the enum, then it must be prefixed by the mod ID in the `namespace:path` format known from `ResourceLocation`s, otherwise an exception is thrown.



#### Retrieving the Generated Constant[​]()



The generated enum constant can be retrieved via `TheEnum.valueOf(String)`. If a field reference is used to provide the parameters, then the constant can also be retrieved from the `EnumProxy` object via `EnumProxy#getValue()`.


## Contributing to NeoForge[​]()



To add a new extensible enum to NeoForge, there are at least two required things to do:




- Make the enum implement `IExtensibleEnum` to mark that this enum should be transformed via the `RuntimeEnumExtender`.

- Add a `getExtensionInfo` method that returns `ExtensionInfo.nonExtended(TheEnum.class)`.



Further action is required depending on specific details about the enum:




- If the enum has an int ID parameter which should match the entry's ordinal, then the enum should be annotated with `@NumberedEnum` with the ID's parameter index as the annotation's value if it's not the first parameter

- If the enum has a String name parameter which is used for serialization and should therefore be namespaced, then the enum should be annotated with `@NamedEnum` with the name's parameter index as the annotation's value if it's not the first parameter

- If the enum is sent over the network, then it should be annotated with `@NetworkedEnum` with the annotation's parameter specifying in which direction the values may be sent (clientbound, serverbound or bidirectional)




- Warning: networked enums will require additional steps once network checks for enums are implemented in NeoForge



- If the enum has constructors which are not usable by mods (i.e. because they require registry objects on an enum that may be initialized before modded registration runs), then they should be annotated with `@ReservedConstructor`

note

The `getExtensionInfo` method will be transformed at runtime to provide a dynamically generated `ExtensionInfo` if the enum actually had any entries added to it.


```
``
```

[PreviousAccess Transformers]()[NextConfiguration]()


- [`IExtensibleEnum`]()


- [Creating an Enum Entry]()
- [Contributing to NeoForge]()Docs


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
        

