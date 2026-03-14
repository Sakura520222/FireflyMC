---
URL: https://docs.neoforged.net/docs/1.21.1/datastorage/codecs
抓取时间: 2026-03-13 22:27:50
源站: NeoForge 1.21.1 官方文档
---






Codecs | NeoForged docs




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
- CodecsVersion: 1.21 - 1.21.1On this page

# Codecs



Codecs are a serialization tool from Mojang's [DataFixerUpper]() used to describe how objects can be transformed between different formats, such as `JsonElement`s for JSON and `Tag`s for NBT.


## Using Codecs[​]()



Codecs are primarily used to encode, or serialize, Java objects to some data format type and decode, or deserialize, formatted data objects back to its associated Java type. This is typically accomplished using `Codec#encodeStart` and `Codec#parse`, respectively.


### DynamicOps[​]()



To determine what intermediate file format to encode and decode to, both `#encodeStart` and `#parse` require a `DynamicOps` instance to define the data within that format.


The [DataFixerUpper]() library contains `JsonOps` to codec JSON data stored in [`Gson`'s]() `JsonElement` instances. `JsonOps` supports two versions of `JsonElement` serialization: `JsonOps#INSTANCE` which defines a standard JSON file, and `JsonOps#COMPRESSED` which allows data to be compressed into a single string.


```
``
```




Minecraft also provides `NbtOps` to codec NBT data stored in `Tag` instances. This can be referenced using `NbtOps#INSTANCE`.


```
``
```




To handle registry entries, Minecraft provides `RegistryOps`, which contains a lookup provider to get available registry elements. These can be created by `RegistryOps#create` that takes in the `DynamicOps` with the specific type to store the data within and the lookup provider containing access to the available registries. NeoForge extends `RegistryOps` to create `ConditionalOps`: a registry codec lookup that can handle [conditions to load the entry]().


```
``
```




#### Format Conversion[​]()



`DynamicOps` can also be used separately to convert between two different encoded formats. This can be done using `#convertTo` and supplying the `DynamicOps` format and the encoded object to convert.


```
``
```




### DataResult[​]()



Encoded or decoded data using codecs return a `DataResult` which holds the converted instance or some error data depending on whether the conversion was successful. When the conversion is successful, the `Optional` supplied by `#result` will contain the successfully converted object. If the conversion fails, the `Optional` supplied by `#error` will contain the `PartialResult`, which holds the error message and a partially converted object depending on the codec.


Additionally, there are many methods on `DataResult` that can be used to transform the result or error into the desired format. For example, `#resultOrPartial` will return an `Optional` containing the result on success, and the partially converted object on failure. The method takes in a string consumer to determine how to report the error message if present.


```
``
```




## Existing Codecs[​]()



### Primitives[​]()



The `Codec` class contains static instances of codecs for certain defined primitives.
CodecJava Type`BOOL``Boolean``BYTE``Byte``SHORT``Short``INT``Integer``LONG``Long``FLOAT``Float``DOUBLE``Double``STRING``String`*`BYTE_BUFFER``ByteBuffer``INT_STREAM``IntStream``LONG_STREAM``LongStream``PASSTHROUGH``Dynamic<?>`**`EMPTY``Unit`***


* `String` can be limited to a certain number of characters via `Codec#string` or `Codec#sizeLimitedString`.


** `Dynamic` is an object which holds a value encoded in a supported `DynamicOps` format. These are typically used to convert encoded object formats into other encoded object formats.


*** `Unit` is an object used to represent `null` objects.


### Vanilla and NeoForge[​]()



Minecraft and NeoForge define many codecs for objects that are frequently encoded and decoded. Some examples include `ResourceLocation#CODEC` for `ResourceLocation`s, `ExtraCodecs#INSTANT_ISO8601` for `Instant`s in the `DateTimeFormatter#ISO_INSTANT` format, and `CompoundTag#CODEC` for `CompoundTag`s.
caution

`CompoundTag`s cannot decode lists of numbers from JSON using `JsonOps`. `JsonOps`, when converting, sets a number to its most narrow type. `ListTag`s force a specific type for its data, so numbers with different types (e.g. `64` would be `byte`, `384` would be `short`) will throw an error on conversion.


Vanilla and NeoForge registries also have codecs for the type of object the registry contains (e.g. `BuiltInRegistries#BLOCK` have a `Codec<Block>`). `Registry#byNameCodec` will encode the registry object to their registry name. Vanilla registries also have a `Registry#holderByNameCodec` which encodes to a registry name and decodes to the registry object wrapped in a `Holder`.


## Creating Codecs[​]()



Codecs can be created for encoding and decoding any object. For understanding purposes, the equivalent encoded JSON will be shown.


### Records[​]()



Codecs can define objects through the use of records. Each record codec defines any object with explicit named fields. There are many ways to create a record codec, but the simplest is via `RecordCodecBuilder#create`.


`RecordCodecBuilder#create` takes in a function which defines an `Instance` and returns an application (`App`) of the object. A correlation can be drawn to creating a class *instance* and the constructors used to *apply* the class to the constructed object.


```
``
```




#### Fields[​]()



An `Instance` can define up to 16 fields using `#group`. Each field must be an application defining the instance the object is being made for and the type of the object. The simplest way to meet this requirement is by taking a `Codec`, setting the name of the field to decode from, and setting the getter used to encode the field.


A field can be created from a `Codec` using `#fieldOf`, if the field is required, or `#optionalFieldOf`, if the field is wrapped in an `Optional` or defaulted. Either method requires a string containing the name of the field in the encoded object. The getter used to encode the field can then be set using `#forGetter`, taking in a function which given the object, returns the field data.
warning

`#optionalFieldOf` will throw an error if there is an element that throws an error when parsing. If the error should be consumed, use `#lenientOptionalFieldOf` instead.


From there, the resulting product can be applied via `#apply` to define how the instance should construct the object for the application. For ease of convenience, the grouped fields should be listed in the same order they appear in the constructor such that the function can simply be a constructor method reference.


```
``
```




```
``
```




### Transformers[​]()



Codecs can be transformed into equivalent, or partially equivalent, representations through mapping methods. Each mapping method takes in two functions: one to transform the current type into the new type, and one to transform the new type back to the current type. This is done through the `#xmap` function.


```
``
```




If a type is partially equivalent, meaning that there are some restrictions during conversion, there are mapping functions which return a `DataResult` which can be used to return an error state whenever an exception or invalid state is reached.
Is A Fully Equivalent to BIs B Fully Equivalent to ATransform MethodYesYes`#xmap`YesNo`#flatComapMap`NoYes`#comapFlatMap`NoNo`#flatXMap`


```
``
```




```
``
```




#### Range Codecs[​]()



Range codecs are an implementation of `#flatXMap` which returns an error `DataResult` if the value is not inclusively between the set minimum and maximum. The value is still provided as a partial result if outside the bounds. There are implementations for integers, floats, and doubles via `#intRange`, `#floatRange`, and `#doubleRange` respectively.


```
``
```




```
``
```




#### String Resolver[​]()



`Codec#stringResolver` is an implementation of `flatXmap` which maps a string to some kind of object.


```
``
```




```
``
```




### Defaults[​]()



If the result of encoding or decoding fails, a default value can be supplied instead via `Codec#orElse` or `Codec#orElseGet`.


```
``
```




```
``
```




### Unit[​]()



A codec which supplies an in-code value and encodes to nothing can be represented using `Codec#unit`. This is useful if a codec uses a non-encodable entry within the data object.


```
``
```




```
``
```




### Lazy Initialized[​]()



Sometimes, a codec may rely on data that is not present when it is constructed. In these situations `Codec#lazyInitialized` can be used to for a codec to construct itself on first encoding/decoding. The method takes in a supplied codec.


```
``
```




```
``
```




### List[​]()



A codec for a list of objects can be generated from an object codec via `Codec#listOf`. `listOf` can also take in integers representing the minimum and maximum size of the list. `sizeLimitedListOf` does the same but only specifies a maximum bound.


```
``
```




```
``
```




List objects decoded using a list codec are stored in an **immutable** list. If a mutable list is needed, a [transformer]() should be applied to the list codec.


### Map[​]()



A codec for a map of keys and value objects can be generated from two codecs via `Codec#unboundedMap`. Unbounded maps can specify any string-based or string-transformed value to be a key.


```
``
```




```
``
```




Map objects decoded using a unbounded map codec are stored in an **immutable** map. If a mutable map is needed, a [transformer]() should be applied to the map codec.
caution

Unbounded maps only support keys that encode/decode to/from strings. A key-value [pair]() list codec can be used to get around this restriction.


### Pair[​]()



A codec for pairs of objects can be generated from two codecs via `Codec#pair`.


A pair codec decodes objects by first decoding the left object in the pair, then taking the remaining part of the encoded object and decodes the right object from that. As such, the codecs must either express something about the encoded object after decoding (such as [records]()), or they have to be augmented into a `MapCodec` and transformed into a regular codec via `#codec`. This can typically done by making the codec a [field]() of some object.


```
``
```




```
``
```


tip

A map codec with a non-string key can be encoded/decoded using a list of key-value pairs applied with a [transformer]().


### Either[​]()



A codec for two different methods of encoding/decoding some object data can be generated from two codecs via `Codec#either`.


An either codec attempts to decode the object using the first codec. If it fails, it attempts to decode using the second codec. If that also fails, then the `DataResult` will only contain the error from the second codec failure.


```
``
```




```
``
```


tip

This can be used in conjunction with a [transformer]() to get a specific object from two different methods of encoding.


#### Xor[​]()



`Codec#xor` is a special case of the [either]() codec where a result is only successful if one of the two methods are processed successfully. If both codecs can be processed, then an error is thrown instead.


```
``
```




```
``
```




#### Alternative[​]()



`Codec#withAlternative` is a special case of the [either]() codec where both codecs are trying to decode the same object, but stored in a different format. The first, or primary, codec will attempt to decode the object. On failure, the second codec will be used instead. Encoding will always use the primary codec.


```
``
```




```
``
```




### Recursive[​]()



Sometimes, an object may reference an object of the same type as a field. For example, `EntityPredicate` takes in an `EntityPredicate` for the vehicle, passenger, and targeted entity. In this case, `Codec#recursive` can be used to supply the codec as part of a function to create the codec.


```
``
```




```
``
```




### Dispatch[​]()



Codecs can have subcodecs which can decode a particular object based upon some specified type via `Codec#dispatch`. This is typically used in registries which contain codecs, such as rule tests or block placers.


A dispatch codec first attempts to get the encoded type from some string key (usually `type`). From there, the type is decoded, calling a getter for the specific codec used to decode the actual object. If the `DynamicOps` used to decode the object compresses its maps, or the object codec itself is not augmented into a `MapCodec` (such as records or fielded primitives), then the object needs to be stored within a `value` key. Otherwise, the object is decoded at the same level as the rest of the data.


```
``
```




```
``
```

[PreviousNamed Binary Tag (NBT)]()[NextData Attachments]()


- [Using Codecs]()


- [DynamicOps]()
- [DataResult]()
- [Existing Codecs]()


- [Primitives]()
- [Vanilla and NeoForge]()
- [Creating Codecs]()


- [Records]()
- [Transformers]()
- [Defaults]()
- [Unit]()
- [Lazy Initialized]()
- [List]()
- [Map]()
- [Pair]()
- [Either]()
- [Recursive]()
- [Dispatch]()Docs


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
        

