# Block Entities

Version: 1.21 - 1.21.1
# Block Entities

Block entities allow the storage of data on [blocks](/docs/1.21.1/blocks/) in cases where [block states](/docs/1.21.1/blocks/states) are not suitable. This is especially the case for data with a non-finite amount of options, such as inventories. Block entities are stationary and bound to a block, but otherwise share many similarities with entities, hence the name.

note
If you have a finite and reasonably small amount (= a few hundred at most) of possible states for your block, you might want to consider using [block states](/docs/1.21.1/blocks/states) instead.

## Creating and Registering Block Entities[​](#creating-and-registering-block-entities)

Like entities and unlike blocks, the `BlockEntity` class represents the block entity instance, not the [registered](/docs/1.21.1/concepts/registries#methods-for-registering) singleton object. The singleton is expressed through the `BlockEntityType&lt;?&gt;` class instead. We will need both to create a new block entity.

Let&#x27;s begin by creating our block entity class:

```
public class MyBlockEntity extends BlockEntity {    public MyBlockEntity(BlockPos pos, BlockState state) {        super(type, pos, state);    }}
```

As you may have noticed, we pass an undefined variable `type` to the super constructor. Let&#x27;s leave that undefined variable there for a moment and instead move to registration.

Registration happens in a similar fashion to entities. We create an instance of the associated singleton class `BlockEntityType&lt;?&gt;` and register it to the block entity type registry, like so:

```
public static final DeferredRegister&lt;BlockEntityType&lt;?&gt;&gt; BLOCK_ENTITY_TYPES =        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ExampleMod.MOD_ID);public static final Supplier&lt;BlockEntityType&lt;MyBlockEntity&gt;&gt; MY_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(        &quot;my_block_entity&quot;,        // The block entity type, created using a builder.        () -&gt; BlockEntityType.Builder.of(                // The supplier to use for constructing the block entity instances.                MyBlockEntity::new,                // A vararg of blocks that can have this block entity.                // This assumes the existence of the referenced blocks as DeferredBlock&lt;Block&gt;s.                MyBlocks.MY_BLOCK_1.get(), MyBlocks.MY_BLOCK_2.get()        )        // Build using null; vanilla does some datafixer shenanigans with the parameter that we don&#x27;t need.        .build(null));
```

Now that we have our block entity type, we can use it in place of the `type` variable we left earlier:

```
public class MyBlockEntity extends BlockEntity {    public MyBlockEntity(BlockPos pos, BlockState state) {        super(MY_BLOCK_ENTITY.get(), pos, state);    }}
```

info
The reason for this rather confusing setup process is that `BlockEntityType.Builder#of` expects a `BlockEntityType.BlockEntitySupplier&lt;T extends BlockEntity&gt;`, which is basically a `BiFunction&lt;BlockPos, BlockState, T extends BlockEntity&gt;`. As such, having a constructor we can directly reference using `::new` is highly beneficial. However, we also need to provide the constructed block entity type to the default and only constructor of `BlockEntity`, so we need to pass references around a bit.

Finally, we need to modify the block class associated with the block entity. This means that we will not be able to attach block entities to simple instances of `Block`, instead, we need a subclass:

```
// The important part is implementing the EntityBlock interface and overriding the #newBlockEntity method.public class MyEntityBlock extends Block implements EntityBlock {    // Constructor deferring to super.    public MyEntityBlock(BlockBehaviour.Properties properties) {        super(properties);    }    // Return a new instance of our block entity here.    @Override    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {        return new MyBlockEntity(pos, state);    }}
```

And then, you of course need to use this class as the type in your block registration:

```
public static final DeferredBlock&lt;MyEntityBlock&gt; MY_BLOCK_1 =        BLOCKS.register(&quot;my_block_1&quot;, () -&gt; new MyEntityBlock( /* ... */ ));public static final DeferredBlock&lt;MyEntityBlock&gt; MY_BLOCK_2 =        BLOCKS.register(&quot;my_block_2&quot;, () -&gt; new MyEntityBlock( /* ... */ ));
```

## Storing Data[​](#storing-data)

One of the main purposes of `BlockEntity`s is to store data. Data storage on block entities can happen in two ways: directly reading and writing [NBT](/docs/1.21.1/datastorage/nbt), or using [data attachments](/docs/1.21.1/datastorage/attachments). This section will cover reading and writing NBT directly; for data attachments, please refer to the linked article.

info
The main purpose of data attachments is, as the name suggests, attaching data to existing block entities, such as those provided by vanilla or other mods. For your own mod&#x27;s block entities, saving and loading directly to and from NBT is preferred.

Data can be read from and written to a `CompoundTag` using the `#loadAdditional` and `#saveAdditional` methods, respectively. These methods are called when the block entity is synced to disk or over the network.

```
public class MyBlockEntity extends BlockEntity {    // This can be any value of any type you want, so long as you can somehow serialize it to NBT.    // We will use an int for the sake of example.    private int value;    public MyBlockEntity(BlockPos pos, BlockState state) {        super(MY_BLOCK_ENTITY.get(), pos, state);    }    // Read values from the passed CompoundTag here.    @Override    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {        super.loadAdditional(tag, registries);        // Will default to 0 if absent. See the NBT article for more information.        this.value = tag.getInt(&quot;value&quot;);    }    // Save values into the passed CompoundTag here.    @Override    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {        super.saveAdditional(tag, registries);        tag.putInt(&quot;value&quot;, this.value);    }}
```

In both methods, it is important that you call super, as that adds basic information such as the position. The tag names `id`, `x`, `y`, `z`, `NeoForgeData` and `neoforge:attachments` are reserved by the super methods, and as such, you should not use them yourself.

Of course, you will want to set other values and not just work with defaults. You can do so freely, like with any other field. However, if you want the game to save those changes, you must call `#setChanged()` afterward, which marks the block entity&#x27;s chunk as dirty (= in need of being saved). If you do not call that method, the block entity might get skipped during saving, as Minecraft&#x27;s saving system only saves chunks that have been marked as dirty.

## Tickers[​](#tickers)

Another very common use of block entities, often in combination with some stored data, is ticking. Ticking means executing some code every game tick. This is done by overriding `EntityBlock#getTicker` and returning a `BlockEntityTicker`, which is basically a consumer with four arguments (level, position, blockstate and block entity), like so:

```
// Note: The ticker is defined in the block, not the block entity. However, it is good practice to// keep the ticking logic in the block entity in some way, for example by defining a static #tick method.public class MyEntityBlock extends Block implements EntityBlock {    // other stuff here    // We use a second method here due to generic conversions    // If extending `BaseEntityBlock`, this method is also available there as a protected static method    private static &lt;E extends BlockEntity, A extends BlockEntity&gt; @Nullable BlockEntityTicker&lt;A&gt; createTickerHelper(        BlockEntityType&lt;A&gt; type, BlockEntityType&lt;E&gt; checkedType, BlockEntityTicker&lt;? super E&gt; ticker    ) {        return checkedType == type ? (BlockEntityTicker&lt;A&gt;) ticker : null;    }    @Override    public &lt;T extends BlockEntity&gt; BlockEntityTicker&lt;T&gt; getTicker(Level level, BlockState state, BlockEntityType&lt;T&gt; type) {        // You can return different tickers here, depending on whatever factors you want. A common use case would be        // to return different tickers on the client or server, only tick one side to begin with,        // or only return a ticker for some blockstates (e.g. when using a &quot;my machine is working&quot; blockstate property).        return createTickerHelper(type, MY_BLOCK_ENTITY.get(), MyBlockEntity::tick);    }}public class MyBlockEntity extends BlockEntity {    // other stuff here    // The signature of this method matches the signature of the BlockEntityTicker functional interface.    public static void tick(Level level, BlockPos pos, BlockState state, MyBlockEntity blockEntity) {        // Whatever you want to do during ticking.        // For example, you could change a crafting progress value or consume power here.    }}
```

Be aware that the `#tick` method is actually called every tick. Due to this, you should avoid doing a lot of complex calculations in here if you can, for example by only calculating things every X ticks, or by caching the results.

## Syncing[​](#syncing)

Block entity logic is usually run on the server. As such, we need to tell the client what we are doing. There are three ways to do just that: on chunk load, on block update, or by using a custom packet. You should generally only sync information when it is necessary, to not needlessly clog up the network.

### Syncing on Chunk Load[​](#syncing-on-chunk-load)

A chunk is loaded (and by extension, this method is utilized) each time it is read from either network or disk. To send your data here, you need to override the following methods:

```
public class MyBlockEntity extends BlockEntity {    // ...    // Create an update tag here. For block entities with only a few fields, this can just call #saveAdditional.    @Override    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {        CompoundTag tag = new CompoundTag();        saveAdditional(tag, registries);        return tag;    }    // Handle a received update tag here. The default implementation calls #loadAdditional here,    // so you do not need to override this method if you don&#x27;t plan to do anything beyond that.    @Override    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {        super.handleUpdateTag(tag, registries);    }}
```

### Syncing on Block Update[​](#syncing-on-block-update)

This method is used whenever a block update occurs. Block updates must be triggered manually, but are generally processed faster than chunk syncing.

```
public class MyBlockEntity extends BlockEntity {    // ...    // Create an update tag here, like above.    @Override    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {        CompoundTag tag = new CompoundTag();        saveAdditional(tag, registries);        return tag;    }    // Return our packet here. This method returning a non-null result tells the game to use this packet for syncing.    @Override    public Packet&lt;ClientGamePacketListener&gt; getUpdatePacket() {        // The packet uses the CompoundTag returned by #getUpdateTag. An alternative overload of #create exists        // that allows you to specify a custom update tag, including the ability to omit data the client might not need.        return ClientboundBlockEntityDataPacket.create(this);    }    // Optionally: Run some custom logic when the packet is received.    // The super/default implementation forwards to #loadAdditional.    @Override    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {        super.onDataPacket(connection, packet, registries);        // Do whatever you need to do here.    }}
```

To actually send the packet, an update notification must be triggered on the server by calling `Level#sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags)`. The position should be the block entity&#x27;s position, obtainable via `BlockEntity#getBlockPos`. Both blockstate parameters can be the blockstate at the block entity&#x27;s position, obtainable via `BlockEntity#getBlockState`. Finally, the `flags` parameter is an update mask, as used in [`Level#setBlock`](/docs/1.21.1/blocks/states#levelsetblock).

### Using a Custom Packet[​](#using-a-custom-packet)

By using a dedicated update packet, you can send packets yourself whenever you need to. This is the most versatile, but also the most complex variant, as it requires setting up a network handler. You can send a packet to all players tracking the block entity by using `PacketDistrubtor#sendToPlayersTrackingChunk`. Please see the [Networking](/docs/1.21.1/networking/) section for more information.

caution
It is important that you do safety checks, as the `BlockEntity` might already be destroyed/replaced when the message arrives at the player. You should also check if the chunk is loaded via `Level#hasChunkAt`.
