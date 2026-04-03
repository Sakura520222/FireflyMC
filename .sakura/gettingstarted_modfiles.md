# Mod Files

Version: 1.21 - 1.21.1
# Mod Files

The mod files are responsible for determining what mods are packaged into your JAR, what information to display within the &#x27;Mods&#x27; menu, and how your mod should be loaded in the game.

## `gradle.properties`[​](#gradleproperties)

The `gradle.properties` file holds various common properties of your mod, such as the mod id or mod version. During building, Gradle reads the values in these files and inlines them in various places, such as the [neoforge.mods.toml](#neoforgemodstoml) file. This way, you only need to change values in one place, and they are then applied everywhere for you.

Most values are also explained as comments in [the MDK&#x27;s `gradle.properties` file](https://github.com/NeoForgeMDKs/MDK-1.21-NeoGradle/blob/main/gradle.properties).

| Property | Description | Example |  || `org.gradle.jvmargs` | Allows you to pass extra JVM arguments to Gradle. Most commonly, this is used to assign more/less memory to Gradle. Note that this is for Gradle itself, not Minecraft. | `org.gradle.jvmargs=-Xmx3G` |  || `org.gradle.daemon` | Whether Gradle should use the daemon when building. | `org.gradle.daemon=false` |  || `org.gradle.debug` | Whether Gradle is set to debug mode. Debug mode mainly means more Gradle log output. Note that this is for Gradle itself, not Minecraft. | `org.gradle.debug=false` |  || `minecraft_version` | The Minecraft version you are modding on. Must match with `neo_version`. | `minecraft_version=1.20.6` |  || `minecraft_version_range` | The Minecraft version range this mod can use, as a [Maven Version Range](https://maven.apache.org/enforcer/enforcer-rules/versionRanges.html). Note that [snapshots, pre-releases and release candidates](/docs/1.21.1/gettingstarted/versioning#minecraft) are not guaranteed to sort properly, as they do not follow maven versioning. | `minecraft_version_range=[1.20.6,1.21)` |  || `neo_version` | The NeoForge version you are modding on. Must match with `minecraft_version`. See [NeoForge Versioning](/docs/1.21.1/gettingstarted/versioning#neoforge) for more information on how NeoForge versioning works. | `neo_version=20.6.62` |  || `neo_version_range` | The NeoForge version range this mod can use, as a [Maven Version Range](https://maven.apache.org/enforcer/enforcer-rules/versionRanges.html). | `neo_version_range=[20.6.62,20.7)` |  || `loader_version_range` | The version range of the mod loader this mod can use, as a [Maven Version Range](https://maven.apache.org/enforcer/enforcer-rules/versionRanges.html). Note that the loader versioning is decoupled from NeoForge versioning. | `loader_version_range=[1,)` |  || `mod_id` | See [The Mod ID](#the-mod-id). | `mod_id=examplemod` |  || `mod_name` | The human-readable display name of your mod. By default, this can only be seen in the mod list, however, mods such as [JEI](https://www.curseforge.com/minecraft/mc-mods/jei) prominently display mod names in item tooltips as well. | `mod_name=Example Mod` |  || `mod_license` | The license your mod is provided under. It is suggested that this is set to the [SPDX identifier](https://spdx.org/licenses/) you are using and/or a link to the license. You can visit [https://choosealicense.com/](https://choosealicense.com/) to help pick the license you want to use. | `mod_license=MIT` |  || `mod_version` | The version of your mod, shown in the mod list. See [the page on Versioning](/docs/1.21.1/gettingstarted/versioning) for more information. | `mod_version=1.0` |  || `mod_group_id` | See [The Group ID](#the-group-id). | `mod_group_id=com.example.examplemod` |  || `mod_authors` | The authors of the mod, shown in the mod list. | `mod_authors=ExampleModder` |  || `mod_description` | The description of the mod, as a multiline string, shown in the mod list. Newline characters (`\n`) can be used and will be replaced properly. | `mod_description=Example mod description.` |  |

### The Mod ID[​](#the-mod-id)

The mod ID is the main way your mod is distinguished from others. It is used in a wide variety of places, including as the namespace for your mod&#x27;s [registries](/docs/1.21.1/concepts/registries#deferredregister), and as your [resource and data pack](/docs/1.21.1/resources/) namespaces. Having two mods with the same id will prevent the game from loading.

As such, your mod ID should be something unique and memorable. Usually, it will be your mod&#x27;s display name (but lower case), or some variation thereof. Mod IDs may only contain lowercase letters, digits and underscores, and must be between 2 and 64 characters long (both inclusive).

info
Changing this property in the `gradle.properties` file will automatically apply the change everywhere, except for the [`@Mod` annotation](#javafml-and-mod) in your main mod class. There, you need to change it manually to match the value in the `gradle.properties` file.

### The Group ID[​](#the-group-id)

While the `group` property in the `build.gradle` is only necessary if you plan to publish your mod to a maven, it is considered good practice to always properly set this. This is done for you through the `gradle.properties`&#x27;s `mod_group_id` property.

The group id should be set to your top-level package. See [Packaging](/docs/1.21.1/gettingstarted/structuring#packaging) for more information.

```
# In your gradle.properties filemod_group_id=com.example
```

The packages within your java source (`src/main/java`) should also now conform to this structure, with an inner package representing the mod id:

```
com- example (top-level package specified in group property)    - mymod (the mod id)        - MyMod.java (renamed ExampleMod.java)
```

## `neoforge.mods.toml`[​](#neoforgemodstoml)

The `neoforge.mods.toml` file, located at `src/main/resources/META-INF/neoforge.mods.toml`, is a file in [TOML](https://toml.io/) format that defines the metadata of your mod(s). It also contains additional information on how your mod(s) should be loaded into the game, as well as display information that is displayed within the &#x27;Mods&#x27; menu. The [`neoforge.mods.toml` file provided by the MDK](https://github.com/NeoForgeMDKs/MDK-1.21-NeoGradle/blob/main/src/main/resources/META-INF/neoforge.mods.toml) contains comments explaining every entry, they will be explained here in more detail.

The `neoforge.mods.toml` can be separated into three parts: the non-mod-specific properties, which are linked to the mod file; the mod properties, with a section for each mod; and the dependency configurations, with a section for each mod&#x27;s or mods&#x27; dependencies. Some of the properties associated with the `neoforge.mods.toml` file are mandatory; mandatory properties require a value to be specified, otherwise an exception will be thrown.

note
In the default MDK, Gradle replaces various properties in this file with the values specified in the `gradle.properties` file. For example, the line `license=&quot;${mod_license}&quot;` means that the `license` field is replaced by the `mod_license` property from `gradle.properties`. Values that are replaced like this should be changed in the `gradle.properties` instead of changing them here.

### Non-Mod-Specific Properties[​](#non-mod-specific-properties)

Non-mod-specific properties are properties associated with the JAR itself, indicating how to load the mod(s) and any additional global metadata.

| Property | Type | Default | Description | Example |  || `modLoader` | string | **mandatory** | The language loader used by the mod(s). Can be used to support alternative language structures, such as Kotlin objects for the main file, or different methods of determining the entrypoint, such as an interface or method. NeoForge provides the Java loader [`&quot;javafml&quot;`](#javafml-and-mod) and the lowcode/nocode loader [`&quot;lowcodefml&quot;`](#lowcodefml). | `modLoader=&quot;javafml&quot;` |  || `loaderVersion` | string | **mandatory** | The acceptable version range of the language loader, expressed as a [Maven Version Range](https://maven.apache.org/enforcer/enforcer-rules/versionRanges.html). For `javafml` and `lowcodefml`, this is currently version `1`. | `loaderVersion=&quot;[1,)&quot;` |  || `license` | string | **mandatory** | The license the mod(s) in this JAR are provided under. It is suggested that this is set to the [SPDX identifier](https://spdx.org/licenses/) you are using and/or a link to the license. You can visit [https://choosealicense.com/](https://choosealicense.com/) to help pick the license you want to use. | `license=&quot;MIT&quot;` |  || `showAsResourcePack` | boolean | `false` | When `true`, the mod(s)&#x27;s resources will be displayed as a separate resource pack on the &#x27;Resource Packs&#x27; menu, rather than being combined with the &#x27;Mod Resources&#x27; pack. | `showAsResourcePack=true` |  || `showAsDataPack` | boolean | `false` | When `true`, the mod(s)&#x27;s data files will be displayed as a separate data pack on the &#x27;Data Packs&#x27; menu, rather than being combined with the &#x27;Mod Data&#x27; pack. | `showAsDataPack=true` |  || `services` | array | `[]` | An array of services your mod uses. This is consumed as part of the created module for the mod from NeoForge&#x27;s implementation of the Java Platform Module System. | `services=[&quot;net.neoforged.neoforgespi.language.IModLanguageProvider&quot;]` |  || `properties` | table | `{}` | A table of substitution properties. This is used by `StringSubstitutor` to replace `${file.&lt;key&gt;}` with its corresponding value. | `properties={&quot;example&quot;=&quot;1.2.3&quot;}` (can then be referenced by `${file.example}`) |  || `issueTrackerURL` | string | *nothing* | A URL representing the place to report and track issues with the mod(s). | `&quot;https://github.com/neoforged/NeoForge/issues&quot;` |  |
note
The `services` property is functionally equivalent to specifying the [`uses` directive in a module](https://docs.oracle.com/javase/specs/jls/se21/html/jls-7.html#jls-7.7.3), which allows [loading a service of a given type](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/ServiceLoader.html#load(java.lang.Class)).

Alternatively, it can be defined in a service file inside the `src/main/resources/META-INF/services` folder, where the file name is the fully-qualified name of the service, and the file content is the name of the service to load (see also [this example from the AtlasViewer mod](https://github.com/XFactHD/AtlasViewer/blob/1.20.2/neoforge/src/main/resources/META-INF/services/xfacthd.atlasviewer.platform.services.IPlatformHelper)).

### Mod-Specific Properties[​](#mod-specific-properties)

Mod-specific properties are tied to the specified mod using the `[[mods]]` header. This is an [array of tables](https://toml.io/en/v1.0.0#array-of-tables); all key/value properties will be attached to that mod until the next header.

```
# Properties for examplemod1[[mods]]modId = &quot;examplemod1&quot;# Properties for examplemod2[[mods]]modId = &quot;examplemod2&quot;
```

| Property | Type | Default | Description | Example |  || `modId` | string | **mandatory** | See [The Mod ID](#the-mod-id). | `modId=&quot;examplemod&quot;` |  || `namespace` | string | value of `modId` | An override namespace for the mod. Must also be a valid [mod ID](#the-mod-id), but may additionally include dots or dashes. Currently unused. | `namespace=&quot;example&quot;` |  || `version` | string | `&quot;1&quot;` | The version of the mod, preferably in a [variation of Maven versioning](/docs/1.21.1/gettingstarted/versioning). When set to `${file.jarVersion}`, it will be replaced with the value of the `Implementation-Version` property in the JAR&#x27;s manifest (displays as `0.0NONE` in a development environment). | `version=&quot;1.20.2-1.0.0&quot;` |  || `displayName` | string | value of `modId` | The display name of the mod. Used when representing the mod on a screen (e.g., mod list, mod mismatch). | `displayName=&quot;Example Mod&quot;` |  || `description` | string | `&#x27;&#x27;&#x27;MISSING DESCRIPTION&#x27;&#x27;&#x27;` | The description of the mod shown in the mod list screen. It is recommended to use a [multiline literal string](https://toml.io/en/v1.0.0#string). This value is also translatable, see [Translating Mod Metadata](/docs/1.21.1/resources/client/i18n#translating-mod-metadata) for more info. | `description=&#x27;&#x27;&#x27;This is an example.&#x27;&#x27;&#x27;` |  || `logoFile` | string | *nothing* | The name and extension of an image file used on the mods list screen. The logo must be in the root of the JAR or directly in the root of the source set (e.g. `src/main/resources` for the main source set). | `logoFile=&quot;example_logo.png&quot;` |  || `logoBlur` | boolean | `true` | Whether to use `GL_LINEAR*` (true) or `GL_NEAREST*` (false) to render the `logoFile`. In simpler terms, this means whether the logo should be blurred or not when trying to scale the logo. | `logoBlur=false` |  || `updateJSONURL` | string | *nothing* | A URL to a JSON used by the [update checker](/docs/1.21.1/misc/updatechecker) to make sure the mod you are playing is the latest version. | `updateJSONURL=&quot;https://example.github.io/update_checker.json&quot;` |  || `modUrl` | string | *nothing* | A URL to the download page of the mod. Currently unused. | `modUrl=&quot;https://neoforged.net/&quot;` |  || `credits` | string | *nothing* | Credits and acknowledges for the mod shown on the mod list screen. | `credits=&quot;The person over here and there.&quot;` |  || `authors` | string | *nothing* | The authors of the mod shown on the mod list screen. | `authors=&quot;Example Person&quot;` |  || `displayURL` | string | *nothing* | A URL to the display page of the mod shown on the mod list screen. | `displayURL=&quot;https://neoforged.net/&quot;` |  || `enumExtensions` | string | *nothing* | The file path of a JSON file used for [enum extension](/docs/1.21.1/advanced/extensibleenums) | `enumExtensions=&quot;META_INF/enumextensions.json&quot;` |  |

#### Features[​](#features)

The features system allows mods to demand that certain settings, software, or hardware are available when loading the system. When a feature is not satisfied, mod loading will fail, informing the user about the requirement. These configurations are created using the [array of tables](https://toml.io/en/v1.0.0#array-of-tables) `[[features.&lt;modid&gt;]]`, where `modid` is the identifier of the mod that consumes the feature. Currently, NeoForge provides the following features:

| Feature | Description | Example |  || `javaVersion` | The acceptable version range of the Java version, expressed as a [Maven Version Range](https://maven.apache.org/enforcer/enforcer-rules/versionRanges.html). This should be the supported version used by Minecraft. | `javaVersion=&quot;[17,)&quot;` |  || `openGLVersion` | The acceptable version range of the OpenGL version, expressed as a [Maven Version Range](https://maven.apache.org/enforcer/enforcer-rules/versionRanges.html). Minecraft requires OpenGL 3.2 or newer. If you want to require a newer OpenGL version, you can do so here. | `openGLVersion=&quot;[4.6,)&quot;` |  |

#### Mod Properties[​](#mod-properties)

The mod properties system is a map of arbitrary keys to values that are associated with a particular mod. These can be useful when a mod file defines multiple mods that provide different metadata. From there, the specific property value for some key can be obtained by getting the object value from the map via `IModInfo#getModProperties`. These configurations are created using the [array of tables](https://toml.io/en/v1.0.0#array-of-tables) `[[modproperties.&lt;modid&gt;]]`, where `modid` is the identifier of the mod that consumes the defined properties.

```
// Assume we have two mods `mod1` and `mod2` with the following property configuration// [[modproperties.mod1]]// key=&quot;value1&quot;// [[modproperties.mod2]]// key=&quot;value2&quot;@Mod(&quot;mod1&quot;)public class ModOne {    private final String key;    public ModOne(ModContainer container) {        // Will store &#x27;value1&#x27; in key        this.key = (String) container.getModInfo().getModProperties().get(&quot;key&quot;);    }}@Mod(&quot;mod2&quot;)public class ModTwo {    private final String key;    public ModTwo(ModContainer container) {        // Will store &#x27;value2&#x27; in key        this.key = (String) container.getModInfo().getModProperties().get(&quot;key&quot;);    }}
```

### Access Transformer-Specific Properties[​](#access-transformer-specific-properties)

[Access Transformer-specific properties](/docs/1.21.1/advanced/accesstransformers#adding-ats) are tied to the specified access transformer using the `[[accessTransformers]]` header. This is an [array of tables](https://toml.io/en/v1.0.0#array-of-tables); all key/value properties will be attached to that access transformer until the next header. The access transformer header is optional; however, when specified, all elements are mandatory.

| Property | Type | Default | Description | Example |  || `file` | string | **mandatory** | See [Adding ATs](/docs/1.21.1/advanced/accesstransformers#adding-ats). | `file=&quot;at.cfg&quot;` |  |

### Mixin Configuration Properties[​](#mixin-configuration-properties)

[Mixin Configuration Properties](https://github.com/SpongePowered/Mixin/wiki/Introduction-to-Mixins---The-Mixin-Environment#mixin-configuration-files) are tied to the specified mixin config using the `[[mixins]]` header. This is an [array of tables](https://toml.io/en/v1.0.0#array-of-tables); all key/value properties will be attached to that mixin block until the next header. The mixin header is optional; however, when specified, all elements are mandatory.

| Property | Type | Default | Description | Example |  || `config` | string | **mandatory** | The location of the mixin configuration file. | `config=&quot;examplemod.mixins.json&quot;` |  |

### Dependency Configurations[​](#dependency-configurations)

Mods can specify their dependencies, which are checked by NeoForge before loading the mods. These configurations are created using the [array of tables](https://toml.io/en/v1.0.0#array-of-tables) `[[dependencies.&lt;modid&gt;]]`, where `modid` is the identifier of the mod that consumes the dependency.

| Property | Type | Default | Description | Example |  || `modId` | string | **mandatory** | The identifier of the mod added as a dependency. | `modId=&quot;jei&quot;` |  || `type` | string | `&quot;required&quot;` | Specifies the nature of this dependency: `&quot;required&quot;` is the default and prevents the mod from loading if this dependency is missing; `&quot;optional&quot;` will not prevent the mod from loading if the dependency is missing, but still validates that the dependency is compatible; `&quot;incompatible&quot;` prevents the mod from loading if this dependency is present; `&quot;discouraged&quot;` still allows the mod to load if the dependency is present, but presents a warning to the user. | `type=&quot;incompatible&quot;` |  || `reason` | string | *nothing* | An optional user-facing message to describe why this dependency is required, or why it is incompatible. | `reason=&quot;integration&quot;` |  || `versionRange` | string | `&quot;&quot;` | The acceptable version range of the language loader, expressed as a [Maven Version Range](https://maven.apache.org/enforcer/enforcer-rules/versionRanges.html). An empty string matches any version. | `versionRange=&quot;[1, 2)&quot;` |  || `ordering` | string | `&quot;NONE&quot;` | Defines if the mod must load before (`&quot;BEFORE&quot;`) or after (`&quot;AFTER&quot;`) this dependency. If the ordering does not matter, return `&quot;NONE&quot;` | `ordering=&quot;AFTER&quot;` |  || `side` | string | `&quot;BOTH&quot;` | The [physical side](/docs/1.21.1/concepts/sides) the dependency must be present on: `&quot;CLIENT&quot;`, `&quot;SERVER&quot;`, or `&quot;BOTH&quot;`. | `side=&quot;CLIENT&quot;` |  || `referralUrl` | string | *nothing* | A URL to the download page of the dependency. Currently unused. | `referralUrl=&quot;https://library.example.com/&quot;` |  |
danger
The `ordering` of two mods may cause a crash due to a cyclic dependency, for example if mod A must load `&quot;BEFORE&quot;` mod B and at the same time, mod B must load `&quot;BEFORE&quot;` mod A.

## Mod Entrypoints[​](#mod-entrypoints)

Now that the `neoforge.mods.toml` is filled out, we need to provide an entrypoint for the mod. Entrypoints are essentially the starting point for executing the mod. The entrypoint itself is determined by the language loader used in the `neoforge.mods.toml`.

### `javafml` and `@Mod`[​](#javafml-and-mod)

`javafml` is a language loader provided by NeoForge for the Java programming language. The entrypoint is defined using a public class with the `@Mod` annotation. The value of `@Mod` must contain one of the mod ids specified within the `neoforge.mods.toml`. From there, all initialization logic (e.g. [registering events](/docs/1.21.1/concepts/events) or [adding `DeferredRegister`s](/docs/1.21.1/concepts/registries#deferredregister)) can be specified within the constructor of the class.

The main mod class must only have one public constructor; otherwise a `RuntimeException` will be thrown. The constructor may have **any** of the following arguments in **any** order; none of them are explicitly required. However, no duplicate parameters are allowed.

| Argument Type | Description |  || `IEventBus` | The [mod-specific event bus](/docs/1.21.1/concepts/events#event-buses) (needed for registration, events, etc.) |  || `ModContainer` | The abstract container holding this mod&#x27;s metadata |  || `FMLModContainer` | The actual container as defined by `javafml` holding this mod&#x27;s metadata; an extension of `ModContainer` |  || `Dist` | The [physical side](/docs/1.21.1/concepts/sides) this mod is loading on |  |

```
@Mod(&quot;examplemod&quot;) // Must match a mod id in the neoforge.mods.tomlpublic class ExampleMod {    // Valid constructor, only uses two of the available argument types    public ExampleMod(IEventBus modBus, ModContainer container) {        // Initialize logic here    }}
```

By default, a `@Mod` annotation is loaded on both [sides](/docs/1.21.1/concepts/sides). This can be changed by specifying the `dist` parameter:

```
// Must match a mod id in the neoforge.mods.toml// This mod class will only be loaded on the physical client@Mod(value = &quot;examplemod&quot;, dist = Dist.CLIENT) public class ExampleModClient {    // Valid constructor    public ExampleModClient(FMLModContainer container, IEventBus modBus, Dist dist) {        // Initialize client-only logic here    }}
```

note
An entry in `neoforge.mods.toml` does not need a corresponding `@Mod` annotation. Likewise, an entry in the `neoforge.mods.toml` can have multiple `@Mod` annotations, for example if you want to separate common logic and client only logic.

### `lowcodefml`[​](#lowcodefml)

`lowcodefml` is a language loader used as a way to distribute datapacks and resource packs as mods without the need of an in-code entrypoint. It is specified as `lowcodefml` rather than `nocodefml` for minor additions in the future that might require minimal coding.
