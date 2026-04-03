# Resources

Version: 1.21 - 1.21.1
# Resources

Resources are external files that are used by the game, but are not code. The most prominent kinds of resources are textures, however, many other types of resources exist in the Minecraft ecosystem. Of course, all these resources require a consumer on the code side, so the consuming systems are grouped in this section as well.

Minecraft generally has two kinds of resources: resources for the [logical client](/docs/1.21.1/concepts/sides#the-logical-side), known as assets, and resources for the [logical server](/docs/1.21.1/concepts/sides#the-logical-side), known as data. Assets are mostly display-only information, for example textures, display models, translations, or sounds, while data includes various things that affect gameplay, such as loot tables, recipes, or worldgen information. They are loaded from resource packs and data packs, respectively. NeoForge generates a built-in resource and data pack for every mod.

Both resource and data packs normally require a [`pack.mcmeta` file](#packmcmeta); however, modern NeoForge generates these at runtime for you, so you don&#x27;t need to worry about it.

If you are confused about the format of something, have a look at the vanilla resources. Your NeoForge development environment not only contains vanilla code, but also vanilla resources. They can be found in the External Resources section (IntelliJ)/Project Libraries section (Eclipse), under the name `ng_dummy_ng.net.minecraft:client:client-extra:&lt;minecraft_version&gt;` (for Minecraft resources) or `ng_dummy_ng.net.neoforged:neoforge:&lt;neoforge_version&gt;` (for NeoForge resources).

## Assets[​](#assets)

*See also: [Resource Packs](https://minecraft.wiki/w/Resource_pack) on the [Minecraft Wiki](https://minecraft.wiki)*

Assets, or client-side resources, are all resources that are only relevant on the [client](/docs/1.21.1/concepts/sides). They are loaded from resource packs, sometimes also known by the old term texture packs (stemming from old versions when they could only affect textures). A resource pack is basically an `assets` folder. The `assets` folder contains subfolders for the various namespaces the resource pack includes; every namespace is one subfolder. For example, a resource pack for a mod with the id `coolmod` will probably contain a `coolmod` namespace, but may additionally include other namespaces, such as `minecraft`.

NeoForge automatically collects all mod resource packs into the `Mod resources` pack, which sits at the bottom of the Selected Packs side in the resource packs menu. It is currently not possible to disable the `Mod resources` pack. However, resource packs that sit above the `Mod resources` pack override resources defined in a resource pack below them. This mechanic allows resource pack makers to override your mod&#x27;s resources, and also allows mod developers to override Minecraft resources if needed.

Resource packs can contain [models](/docs/1.21.1/resources/client/models/), [blockstate files](/docs/1.21.1/resources/client/models/#blockstate-files), [textures](/docs/1.21.1/resources/client/textures), [sounds](/docs/1.21.1/resources/client/sounds), [particle definitions](/docs/1.21.1/resources/client/particles) and [translation files](/docs/1.21.1/resources/client/i18n#language-files).

## Data[​](#data)

*See also: [Data Packs](https://minecraft.wiki/w/Data_pack) on the [Minecraft Wiki](https://minecraft.wiki)*

In contrast to assets, data is the term for all [server](/docs/1.21.1/concepts/sides) resources. Similar to resource packs, data is loaded through data packs (or datapacks). Like a resource pack, a data pack consists of a [`pack.mcmeta` file](#packmcmeta) and a root folder, named `data`. Then, again like with resource packs, that `data` folder contains subfolders for the various namespaces the resource pack includes; every namespace is one subfolder. For example, a data pack for a mod with the id `coolmod` will probably contain a `coolmod` namespace, but may additionally include other namespaces, such as `minecraft`.

NeoForge automatically applies all mod data packs to a new world upon creation. It is currently not possible to disable mod data packs. However, most data files can be overridden (and thus be removed by replacing them with an empty file) by a data pack with a higher priority. Additional data packs can be enabled or disabled by placing them in a world&#x27;s `datapacks` subfolder and then enabling or disabling them through the [`/datapack`](https://minecraft.wiki/w/Commands/datapack) command.

info
There is currently no built-in way to apply a set of custom data packs to every world. However, there are a number of mods that achieve this.

Data packs may contain folders with files affecting the following things:

| Folder name | Contents |  || `advancement` | [Advancements](/docs/1.21.1/resources/server/advancements) |  || `damage_type` | [Damage types](/docs/1.21.1/resources/server/damagetypes) |  || `loot_table` | [Loot tables](/docs/1.21.1/resources/server/loottables/) |  || `recipe` | [Recipes](/docs/1.21.1/resources/server/recipes/) |  || `tags` | [Tags](/docs/1.21.1/resources/server/tags) |  || `neoforge/data_maps` | [Data maps](/docs/1.21.1/resources/server/datamaps/) |  || `neoforge/loot_modifiers` | [Global loot modifiers](/docs/1.21.1/resources/server/loottables/glm) |  || `dimension`, `dimension_type`, `structure`, `worldgen`, `neoforge/biome_modifier` | Worldgen files |  |

Additionally, they may also contain subfolders for some systems that integrate with commands. These systems are rarely used in conjunction with mods, but worth mentioning regardless:

| Folder name | Contents |  || `chat_type` | [Chat types](https://minecraft.wiki/w/Chat_type) |  || `function` | [Functions](https://minecraft.wiki/w/Function_(Java_Edition)) |  || `item_modifier` | [Item modifiers](https://minecraft.wiki/w/Item_modifier) |  || `predicate` | [Predicates](https://minecraft.wiki/w/Predicate) |  |

## `pack.mcmeta`[​](#packmcmeta)

*See also: [`pack.mcmeta` (Resource Pack)](https://minecraft.wiki/w/Resource_pack#Contents) and [`pack.mcmeta` (Data Pack)](https://minecraft.wiki/w/Data_pack#pack.mcmeta) on the [Minecraft Wiki](https://minecraft.wiki)*

`pack.mcmeta` files hold the metadata of a resource or data pack. For mods, NeoForge makes this file obsolete, as the `pack.mcmeta` is generated synthetically. In case you still need a `pack.mcmeta` file, the full specification can be found in the linked Minecraft Wiki articles.

## Data Generation[​](#data-generation)

Data generation, colloquially known as datagen, is a way to programmatically generate JSON resource files, in order to avoid the tedious and error-prone process of writing them by hand. The name is a bit misleading, as it works for assets as well as data.

Datagen is run through the Data run configuration, which is generated for you alongside the Client and Server run configurations. The data run configuration follows the [mod lifecycle](/docs/1.21.1/concepts/events#the-mod-lifecycle) until after the registry events are fired. It then fires the [`GatherDataEvent`](/docs/1.21.1/concepts/events), in which you can register your to-be-generated objects in the form of data providers, writes said objects to disk, and ends the process.

All data providers extend the `DataProvider` interface and usually require one method to be overridden. The following is a list of noteworthy data generators Minecraft and NeoForge offer (the linked articles add further information, such as helper methods):

| Class | Method | Generates | Side | Notes |  || [`BlockStateProvider`](/docs/1.21.1/resources/client/models/datagen#block-model-datagen) | `registerStatesAndModels()` | Blockstate files, block models | Client |  |  || [`ItemModelProvider`](/docs/1.21.1/resources/client/models/datagen#item-model-datagen) | `registerModels()` | Item models | Client |  |  || [`LanguageProvider`](/docs/1.21.1/resources/client/i18n#datagen) | `addTranslations()` | Translations | Client | Also requires passing the language in the constructor. |  || [`ParticleDescriptionProvider`](/docs/1.21.1/resources/client/particles#datagen) | `addDescriptions()` | Particle definitions | Client |  |  || [`SoundDefinitionsProvider`](/docs/1.21.1/resources/client/sounds#datagen) | `registerSounds()` | Sound definitions | Client |  |  || `SpriteSourceProvider` | `gather()` | Sprite sources / atlases | Client |  |  || [`AdvancementProvider`](/docs/1.21.1/resources/server/advancements#data-generation) | `generate()` | Advancements | Server | Make sure to use the NeoForge variant, not the Minecraft one. |  || [`LootTableProvider`](/docs/1.21.1/resources/server/loottables/#datagen) | `generate()` | Loot tables | Server | Requires extra methods and classes to work properly, see linked article for details. |  || [`RecipeProvider`](/docs/1.21.1/resources/server/recipes/#data-generation) | `buildRecipes(RecipeOutput)` | Recipes | Server |  |  || [Various subclasses of `TagsProvider`](/docs/1.21.1/resources/server/tags#datagen) | `addTags(HolderLookup.Provider)` | Tags | Server | Several specialized subclasses exist, see linked article for details. |  || [`DataMapProvider`](/docs/1.21.1/resources/server/datamaps/#data-generation) | `gather()` | Data map entries | Server |  |  || [`GlobalLootModifierProvider`](/docs/1.21.1/resources/server/loottables/glm#datagen) | `start()` | Global loot modifiers | Server |  |  || [`DatapackBuiltinEntriesProvider`](/docs/1.21.1/concepts/registries#data-generation-for-datapack-registries) | N/A | Datapack builtin entries, e.g. worldgen and [damage types](/docs/1.21.1/resources/server/damagetypes) | Server | No method overriding, instead entries are added in a lambda in the constructor. See linked article for details. |  || `JsonCodecProvider` (abstract class) | `gather()` | Objects with a codec | Both | This can be extended for use with any object that has a [codec](/docs/1.21.1/datastorage/codecs) to encode data to. |  |

All of these providers follow the same pattern. First, you create a subclass and add your own resources to be generated. Then, you add the provider to the event in an [event handler](/docs/1.21.1/concepts/events#registering-an-event-handler). An example using a `RecipeProvider`:

```
public class MyRecipeProvider extends RecipeProvider {    public MyRecipeProvider(PackOutput output, CompletableFuture&lt;HolderLookup.Provider&gt; lookupProvider) {        super(output, lookupProvider);    }    @Override    protected void buildRecipes(RecipeOutput output) {        // Register your recipes here.    }}// In some event handler class@SubscribeEvent // on the mod event buspublic static void gatherData(GatherDataEvent event) {    // Data generators may require some of these as constructor parameters.    // See below for more details on each of these.    DataGenerator generator = event.getGenerator();    PackOutput output = generator.getPackOutput();    ExistingFileHelper existingFileHelper = event.getExistingFileHelper();    CompletableFuture&lt;HolderLookup.Provider&gt; lookupProvider = event.getLookupProvider();    // Register the provider.    generator.addProvider(            // A boolean that determines whether the data should actually be generated.            // The event provides methods that determine this:            // event.includeClient(), event.includeServer(),            // event.includeDev() and event.includeReports().            // Since recipes are server data, we only run them in a server datagen.            event.includeServer(),            // Our provider.            new MyRecipeProvider(output, lookupProvider)    );    // Other data providers here.}
```

The event offers some context for you to use:

- `event.getGenerator()` returns the `DataGenerator` that you register the providers to.

- `event.getPackOutput()` returns a `PackOutput` that is used by some providers to determine their file output location.

- `event.getExistingFileHelper()` returns an `ExistingFileHelper` that is used by providers for things that can reference other files (for example block models, which can specify a parent file).

- `event.getLookupProvider()` returns a `CompletableFuture&lt;HolderLookup.Provider&gt;` that is mainly used by tags and datagen registries to reference other, potentially not yet existing elements.

- `event.includeClient()`, `event.includeServer()`, `event.includeDev()` and `event.includeReports()` are `boolean` methods that allow you to check whether specific command line arguments (see below) are enabled.

### Command Line Arguments[​](#command-line-arguments)

The data generator can accept several command line arguments:

- `--mod examplemod`: Tells the data generator to run datagen for this mod. Automatically added by NeoGradle for the owning mod id, add this if you e.g. have multiple mods in one project.

- `--output path/to/folder`: Tells the data generator to output into the given folder. It is recommended to use Gradle&#x27;s `file(...).getAbsolutePath()` to generate an absolute path for you (with a path relative to the project root directory). Defaults to `file(&#x27;src/generated/resources&#x27;).getAbsolutePath()`.

- `--existing path/to/folder`: Tells the data generator to consider the given folder when checking for existing files. Like with the output, it is recommended to use Gradle&#x27;s `file(...).getAbsolutePath()`.

- `--existing-mod examplemod`: Tells the data generator to consider the resources in the given mod&#x27;s JAR file when checking for existing files.
Generator modes (all of these are boolean arguments and do not need any additional arguments):

- `--includeClient`: Whether to generate client resources (assets). Check at runtime with `GatherDataEvent#includeClient()`.

- `--includeServer`: Whether to generate server resources (data). Check at runtime with `GatherDataEvent#includeServer()`.

- `--includeDev`: Whether to run dev tools. Generally shouldn&#x27;t be used by mods. Check at runtime with `GatherDataEvent#includeDev()`.

- `--includeReports`: Whether to dump a list of registered objects. Check at runtime with `GatherDataEvent#includeReports()`.

- `--all`: Enable all generator modes.

All arguments can be added to the run configurations by adding the following to your `build.gradle`:

```
runs {    // other run configurations here    data {        programArguments.addAll &#x27;--arg1&#x27;, &#x27;value1&#x27;, &#x27;--arg2&#x27;, &#x27;value2&#x27;, &#x27;--all&#x27; // boolean args have no value    }}
```

For example, to replicate the default arguments, you could specify the following:

```
runs {    // other run configurations here    data {        programArguments.addAll &#x27;--mod&#x27;, &#x27;examplemod&#x27;, // insert your own mod id                &#x27;--output&#x27;, file(&#x27;src/generated/resources&#x27;).getAbsolutePath(),                &#x27;--includeClient&#x27;,                &#x27;--includeServer&#x27;    }}
```
