# Structuring Your Mod

Version: 1.21 - 1.21.1
# Structuring Your Mod

Structured mods are beneficial for maintenance, making contributions, and providing a clearer understanding of the underlying codebase. Some of the recommendations from Java, Minecraft, and NeoForge are listed below.

note
You do not have to follow the advice below; you can structure your mod any way you see fit. However, it is still highly recommended to do so.

## Packaging[​](#packaging)

When structuring your mod, pick a unique, top-level package structure. Many programmers will use the same name for different classes, interfaces, etc. Java allows classes to have the same name as long as they are in different packages. As such, if two classes have the same package with the same name, only one would be loaded, most likely causing the game to crash.

```
a.jar    - com.example.ExampleClassb.jar    - com.example.ExampleClass // This class will not normally be loaded
```

This is even more relevant when it comes to loading modules. If there are class files in two packages under the same name in separate modules, this will cause the mod loader to crash on startup since mod modules are exported to the game and other mods.

```
module A    - package X        - class I        - class Jmodule B    - package X // This package will cause the mod loader to crash, as there already is a module with package X being exported        - class R        - class S        - class T
```

As such, your top level package should be something that you own: a domain, email address, a (subdomain of a) website, etc. It can even be your name or username as long as you can guarantee that it will be uniquely identifiable within the expected target. Furthermore, the top-level package should also match your [group id](/docs/1.21.1/gettingstarted/#the-group-id).

| Type | Value | Top-Level Package |  || Domain | example.com | `com.example` |  || Subdomain | example.github.io | `io.github.example` |  || Email | [[email&#160;protected]](/cdn-cgi/l/email-protection#385d40595548545d785f55595154165b5755) | `com.gmail.example` |  |

The next level package should then be your mod&#x27;s id (e.g. `com.example.examplemod` where `examplemod` is the mod id). This will guarantee that, unless you have two mods with the same id (which should never be the case), your packages should not have any issues loading.

You can find some additional naming conventions on [Oracle&#x27;s tutorial page](https://docs.oracle.com/javase/tutorial/java/package/namingpkgs.html).

### Sub-package Organization[​](#sub-package-organization)

In addition to the top-level package, it is highly recommend to break your mod&#x27;s classes between subpackages. There are two major methods on how to do so:

- **Group By Function**: Make subpackages for classes with a common purpose. For example, blocks can be under `block`, items under `item`, entities under `entity`, etc. Minecraft itself uses a similar structure (with some exceptions).

- **Group By Logic**: Make subpackages for classes with a common logic. For example, if you were creating a new type of crafting table, you would put its block, menu, item, and more under `feature.crafting_table`.

#### Client, Server, and Data Packages[​](#client-server-and-data-packages)

In general, code only for a given side or runtime should be isolated from the other classes in a separate subpackage. For example, code related to [data generation](/docs/1.21.1/resources/#data-generation) should go in a `data` package, and code only on the dedicated server should go in a `server` package.

It is highly recommended that [client-only code](/docs/1.21.1/concepts/sides) should be isolated in a `client` subpackage. This is because dedicated servers have no access to any of the client-only packages in Minecraft and will crash if your mod tries to access them anyway. As such, having a dedicated package provides a decent sanity check to verify you are not reaching across sides within your mod.

## Class Naming Schemes[​](#class-naming-schemes)

A common class naming scheme makes it easier to decipher the purpose of the class or to easily locate specific classes.

Classes are commonly suffixed with its type, for example:

- An `Item` called `PowerRing` -&gt; `PowerRingItem`.

- A `Block` called `NotDirt` -&gt; `NotDirtBlock`.

- A menu for an `Oven` -&gt; `OvenMenu`.

tip
Mojang typically follows a similar structure for all classes except entities. Those are represented by just their names (e.g. `Pig`, `Zombie`, etc.).

## Choose One Method from Many[​](#choose-one-method-from-many)

There are many methods for performing a certain task: registering an object, listening for events, etc. It&#x27;s generally recommended to be consistent by using a single method to accomplish a given task. This improves readability and avoids weird interactions or redundancies that may occur (e.g. your event listener running twice).
