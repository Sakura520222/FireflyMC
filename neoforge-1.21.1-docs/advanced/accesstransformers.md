---
URL: https://docs.neoforged.net/docs/1.21.1/advanced/accesstransformers
抓取时间: 2026-03-13 22:27:42
源站: NeoForge 1.21.1 官方文档
---






Access Transformers | NeoForged docs




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
- Access TransformersVersion: 1.21 - 1.21.1On this page

# Access Transformers



Access Transformers (ATs for short) allow for widening the visibility and modifying the `final` flags of classes, methods, and fields. They allow modders to access and modify otherwise inaccessible members in classes outside their control.


The [specification document]() can be viewed on the NeoForged GitHub.


## Adding ATs[​]()



Adding an Access Transformer to your mod project is as simple as adding a single line into your `build.gradle`:


Access Transformers need to be declared in `build.gradle`. AT files can be specified anywhere as long as they are copied to the `resources` output directory on compilation.


```
``
```




By default, NeoForge will search for `META-INF/accesstransformer.cfg`. If the `build.gradle` specifies access transformers in any other location, then their location needs to be defined within `neoforge.mods.toml`:


```
``
```




Additionally, multiple AT files can be specified and will be applied in order. This can be useful for larger mods with multiple packages.


```
``
```




```
``
```




After adding or modifying any Access Transformer, the Gradle project must be refreshed for the transformations to take effect.


## The Access Transformer Specification[​]()



### Comments[​]()



All text after a `#` until the end of the line will be treated as a comment and will not be parsed.


### Access Modifiers[​]()



Access modifiers specify to what new member visibility the given target will be transformed to. In decreasing order of visibility:




- `public` - visible to all classes inside and outside its package

- `protected` - visible only to classes inside the package and subclasses

- `default` - visible only to classes inside the package

- `private` - visible only to inside the class



A special modifier `+f` and `-f` can be appended to the aforementioned modifiers to either add or remove respectively the `final` modifier, which prevents subclassing, method overriding, or field modification when applied.
danger

Directives only modify the method they directly reference; any overriding methods will not be access-transformed. It is advised to ensure transformed methods do not have non-transformed overrides that restrict the visibility, which will result in the JVM throwing an error.

Examples of methods that can be safely transformed are `final` methods (or methods in `final` classes), and `static` methods. `private` methods are generally safe as well; however, they could cause unintentional overrides in any subtypes, so some additional manual validation should be performed.


### Targets and Directives[​]()



#### Classes[​]()



To target classes:


```
``
```




Inner classes are denoted by combining the fully qualified name of the outer class and the name of the inner class with a `$` as separator.


#### Fields[​]()



To target fields:


```
``
```




#### Methods[​]()



Targeting methods require a special syntax to denote the method parameters and return type:


```
``
```


Specifying Types[​]()



Also called "descriptors": see the [Java Virtual Machine Specification, SE 21, sections 4.3.2 and 4.3.3]() for more technical details.




- `B` - `byte`, a signed byte

- `C` - `char`, a Unicode character code point in UTF-16

- `D` - `double`, a double-precision floating-point value

- `F` - `float`, a single-precision floating-point value

- `I` - `integer`, a 32-bit integer

- `J` - `long`, a 64-bit integer

- `S` - `short`, a signed short

- `Z` - `boolean`, a `true` or `false` value

- `[` - references one dimension of an array




- Example: `[[S` refers to `short[][]`



- `L<class name>;` - references a reference type




- Example: `Ljava/lang/String;` refers to `java.lang.String` reference type *(note the use of slashes instead of periods)*



- `(` - references a method descriptor, parameters should be supplied here or nothing if no parameters are present




- Example: `<method>(I)Z` refers to a method that requires an integer argument and returns a boolean



- `V` - indicates a method returns no value, can only be used at the end of a method descriptor




- Example: `<method>()V` refers to a method that has no arguments and returns nothing





### Examples[​]()



```
``
```

[PreviousEntities]()[NextExtensible Enums]()


- [Adding ATs]()
- [The Access Transformer Specification]()


- [Comments]()
- [Access Modifiers]()
- [Targets and Directives]()
- [Examples]()Docs


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
        

