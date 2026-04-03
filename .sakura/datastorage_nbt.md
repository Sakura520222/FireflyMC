# Named Binary Tag (NBT)

Version: 1.21 - 1.21.1
# Named Binary Tag (NBT)

NBT is a format introduced in the earliest days of Minecraft, written by Notch himself. It is widely used throughout the Minecraft codebase for data storage.

## Specification[​](#specification)

The NBT spec is similar to the JSON spec, with a few differences:

Distinct types for bytes, shorts, longs and floats exist, suffixed by `b`, `s`, `l` and `f`, respectively, similar to how they would be represented in Java code.

- Doubles may also be suffixed with `d`, but this is not required, similar to Java code. The optional `i` suffix available in Java for integers is not permitted.

- The suffixes are not case-sensitive. So for example, `64b` is the same as `64B`, and `0.5F` is the same as `0.5f`.

Booleans do not exist, they are instead represented by bytes. `true` becomes `1b`, `false` becomes `0b`.

- The current implementation treats all non-zero values as `true`, so `2b` would be treated as `true` as well.

- There is no `null` equivalent in NBT.

- Quotes around keys are optional. So a JSON property `&quot;duration&quot;: 20` can become both `duration: 20` and `&quot;duration&quot;: 20` in NBT.

- What is known in JSON as a sub-object is known in NBT as a **compound tag** (or just compound).
NBT lists cannot mix and match types, unlike in JSON. The list type is determined by the first element, or defined in code.

- However, lists of lists can mix and match different list types. So a list of two lists, where the first one is a list of strings and the second one is a list of bytes, is allowed.

There are special **array** types that are different from lists, but follow their scheme of containing elements in square brackets. There are three array types:

- Byte arrays, denoted by a `B;` at the beginning of the array. Example: `[B;0b,30b]`

- Integer arrays, denoted by a `I;` at the beginning of the array. Example: `[I;0,-300]`

- Long arrays, denoted by an `L;` at the beginning of the array. Example: `[L;0l,240l]`

- Trailing commas in lists, arrays and compound tags are allowed.

## NBT Files[​](#nbt-files)

Minecraft uses `.nbt` files extensively, for example for structure files in [datapacks](/docs/1.21.1/resources/#data). Region files (`.mca`) that contain the contents of a region (i.e. a collection of chunks), as well as the various `.dat` files used in different places by the game, are NBT files as well.

NBT files are typically compressed with GZip. As such, they are binary files and cannot be edited directly.

## NBT in Code[​](#nbt-in-code)

Like in JSON, all NBT objects are children of an enclosing object. So let&#x27;s create one:

```
CompoundTag tag = new CompoundTag();
```

We can now put our data into that tag:

```
tag.putInt(&quot;Color&quot;, 0xffffff);tag.putString(&quot;Level&quot;, &quot;minecraft:overworld&quot;);tag.putDouble(&quot;IAmRunningOutOfIdeasForNamesHere&quot;, 1d);
```

Several helpers exist here, for example, `putIntArray` also has a convenience method that takes a `List&lt;Integer&gt;` in addition to the standard variant that takes an `int[]`.

Of course, we can also get values from that tag:

```
int color = tag.getInt(&quot;Color&quot;);String level = tag.getString(&quot;Level&quot;);double d = tag.getDouble(&quot;IAmRunningOutOfIdeasForNamesHere&quot;);
```

Number types will return 0 if absent. Strings will return `&quot;&quot;` if absent. More complex types (lists, arrays, compounds) will throw an exception if absent.

As such, we want to safeguard by checking if a tag element exists:

```
boolean hasColor = tag.contains(&quot;Color&quot;);boolean hasColorMoreExplicitly = tag.contains(&quot;Color&quot;, Tag.TAG_INT);
```

The `TAG_INT` constant is defined in `Tag`, which is the super interface for all tag types. Most tag types besides `CompoundTag` are mostly internal, for example `ByteTag` or `StringTag`, though the direct `CompoundTag#get` and `#put` methods can work with them if you ever stumble across some.

There is one obvious exception, though: `ListTag`s. Working with these is special because when getting a list tag through `CompoundTag#getList`, you must also specify the list type. So getting a list of strings, for example, would work like this:

```
ListTag list = tag.getList(&quot;SomeListHere&quot;, Tag.TAG_STRING);
```

Similarly, when creating a `ListTag`, you must also specify the list type during creation:

```
ListTag list = new ListTag(List.of(&quot;Value1&quot;, &quot;Value2&quot;), Tag.TAG_STRING);
```

Finally, working with `CompoundTag`s inside other `CompoundTag`s directly utilizes `CompoundTag#get` and `#put`:

```
tag.put(&quot;Tag&quot;, new CompoundTag());tag.get(&quot;Tag&quot;);
```

## Usages of NBT[​](#usages-of-nbt)

NBT is used in a lot of places in Minecraft. Some of the most common examples include [`BlockEntity`](/docs/1.21.1/blockentities/)s and `Entity`s.

note
`ItemStack`s abstract away the usage of NBT into [data components](/docs/1.21.1/items/datacomponents).

## See Also[​](#see-also)

- [NBT Format on the Minecraft Wiki](https://minecraft.wiki/w/NBT_format)
