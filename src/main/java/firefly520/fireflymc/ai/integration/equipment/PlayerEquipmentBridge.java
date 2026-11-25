package firefly520.fireflymc.ai.integration.equipment;

import static firefly520.fireflymc.ai.integration.equipment.ReadOnlyNativeAccess.require;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerOffhandInvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerArmorInvWrapper;

/** Own equipment observations only; no equip/unequip callbacks, extraction, initialization, or model calls. */
public final class PlayerEquipmentBridge {
    private static final String CU = "top.theillusivec4.curios.";
    private static final String AC = "io.wispforest.accessories.";
    private static final String CORE = "net.p3pp3rf1y.sophisticatedcore.";
    private static final int MAX_GROUPS = 32;
    private static final int MAX_SLOTS = 256;
    private static final List<EquipmentSlot> VANILLA_SLOTS = List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
    private final OwnedPlayerAccess access;
    private final ReadOnlyNativeAccess nativeAccess = new ReadOnlyNativeAccess();

    public record SlotView(String source, String slotName, int index, boolean active, String hostId,
                           String contentsBackendId, String hostState,
                           ReadOnlyNativeAccess.ItemObservation item) {
        public boolean consumptionAuthorized() { return false; }
        public boolean nativeUnequipCheckRequired() { return true; }
    }
    public record BackpackAliases(String contentsBackendId, List<String> hostIds) {
        public BackpackAliases { hostIds = List.copyOf(hostIds); }
        public boolean contentsInspected() { return false; }
    }
    public record Snapshot(UUID owner, String reason, List<SlotView> slots,
                           List<BackpackAliases> backpacks, List<ReadOnlyNativeAccess.AdapterStatus> adapters) {
        public Snapshot {
            slots = List.copyOf(slots);
            backpacks = List.copyOf(backpacks);
            adapters = List.copyOf(adapters);
        }
        public boolean behaviorVerified() { return false; }
        public boolean consumptionAuthorized() { return false; }
    }
    record Alias(String backend, String host) {}
    /** Native references stay on the server thread and must never be serialized to the client/model. */
    public record HostSlot(String locator, String source, String slotName, int index, boolean active,
                           IItemHandler inventory, int nativeSlot) {
        public ItemStack current() { return inventory.getStackInSlot(nativeSlot); }
    }

    /** Bounded own-host discovery. Reading equipment does not authorize extracting it or its contents. */
    public List<HostSlot> hostSlots(ServerPlayer player, UUID workspaceOwner) {
        access.require(player, workspaceOwner);
        List<HostSlot> hosts = new ArrayList<>();
        IItemHandler main = new PlayerMainInvWrapper(player.getInventory());
        for (int i = 0; i < main.getSlots(); i++)
            hosts.add(new HostSlot("inventory/" + i, "minecraft", "inventory", i, true, main, i));
        IItemHandler offhand = new PlayerOffhandInvWrapper(player.getInventory());
        hosts.add(new HostSlot("offhand/0", "minecraft", "offhand", 0, true, offhand, 0));
        IItemHandler armour = new PlayerArmorInvWrapper(player.getInventory());
        for (int i = 0; i < armour.getSlots(); i++)
            hosts.add(new HostSlot("armor/" + i, "minecraft_equipment", "armor", i, true, armour, i));
        List<ReadOnlyNativeAccess.AdapterStatus> statuses = new ArrayList<>();
        readCurios(player, null, statuses, hosts);
        readAccessories(player, null, statuses, hosts);
        return List.copyOf(hosts);
    }

    public HostSlot requireHost(ServerPlayer player, UUID owner, String locator) {
        return hostSlots(player, owner).stream().filter(slot -> slot.locator().equals(locator) && slot.active())
                .findFirst().orElseThrow(() -> new ReadOnlyNativeAccess.ReadRefused("EQUIPMENT_HOST_UNAVAILABLE"));
    }

    public PlayerEquipmentBridge(MinecraftServer server, Predicate<ServerPlayer> authenticated) {
        this.access = new OwnedPlayerAccess(server, authenticated);
    }

    /** The workspace owner is supplied by the trusted session, never selected from tool arguments. */
    public Snapshot inspect(ServerPlayer player, UUID workspaceOwner) {
        String refusal = access.refusal(player, workspaceOwner);
        if (refusal != null) return new Snapshot(workspaceOwner, refusal, List.of(), List.of(), List.of());
        List<SlotView> rows = new ArrayList<>();
        List<ReadOnlyNativeAccess.AdapterStatus> statuses = new ArrayList<>();
        try {
            for (EquipmentSlot slot : VANILLA_SLOTS)
                rows.add(row(player, "minecraft", slot.getName(), 0, true, player.getItemBySlot(slot)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return new Snapshot(workspaceOwner, "EQUIPMENT_READ_FAILED", List.of(), List.of(), List.of());
        }
        readCurios(player, rows, statuses, null);
        readAccessories(player, rows, statuses, null);
        List<Alias> aliases = rows.stream().filter(row -> !row.contentsBackendId().isEmpty())
                .map(row -> new Alias(row.contentsBackendId(), row.hostId())).toList();
        boolean partial = statuses.stream().anyMatch(status -> status.state() != ReadOnlyNativeAccess.State.OBSERVED
                && status.state() != ReadOnlyNativeAccess.State.ABSENT);
        return new Snapshot(workspaceOwner, partial ? "PARTIAL_EQUIPMENT_OBSERVATION" : "OWN_EQUIPMENT_OBSERVED",
                rows, groupAliases(aliases), statuses);
    }

    private void readCurios(ServerPlayer player, List<SlotView> rows,
                            List<ReadOnlyNativeAccess.AdapterStatus> statuses, List<HostSlot> hosts) {
        var status = nativeAccess.version("curios", "9.5.1+1.21.1", null,
                "ev_4ea882bc896193802931e18573f4128b1c155556822970af1004ff70035d1bfa");
        if (status.state() != ReadOnlyNativeAccess.State.STATIC_API_MATCH) { statuses.add(status); return; }
        List<SlotView> candidate = new ArrayList<>();
        List<HostSlot> foundHosts = new ArrayList<>();
        try {
            require(!nativeAccess.loaded("cclayer"), "UNREVIEWED_EQUIPMENT_BRIDGE");
            Object supplier = nativeAccess.field(null, CU + "common.CuriosRegistry", "INVENTORY",
                    Supplier.class, Modifier.PUBLIC);
            require(supplier instanceof Supplier<?>, "CURIOS_ATTACHMENT_SIGNATURE");
            Object attachment = ((Supplier<?>) supplier).get();
            require(attachment instanceof AttachmentType<?>, "CURIOS_ATTACHMENT_SIGNATURE");
            Object inventory = player.getExistingData((AttachmentType<?>) attachment).orElse(null);
            require(inventory != null, "CURIOS_NOT_INITIALIZED");
            require(inventory.getClass().getName().equals(CU + "common.capability.CurioInventory"),
                    "UNKNOWN_CURIOS_INVENTORY");
            require(!(boolean) nativeAccess.field(inventory, CU + "common.capability.CurioInventory",
                    "markDeserialized", boolean.class, 0), "CURIOS_DECODE_PENDING");
            Object itemHandler = nativeAccess.field(inventory, CU + "common.capability.CurioInventory",
                    "curiosItemHandler", nativeAccess.type(CU + "api.type.capability.ICuriosItemHandler"), 0);
            require(itemHandler != null, "CURIOS_NOT_INITIALIZED");
            require(nativeAccess.query(itemHandler, CU + "api.type.capability.ICuriosItemHandler",
                    "getWearer", LivingEntity.class) == player, "CURIOS_WEARER_MISMATCH");
            Object rawMap = nativeAccess.query(inventory, CU + "common.capability.CurioInventory", "asMap", Map.class);
            Map<?, ?> groups = boundedMap(rawMap);
            for (Map.Entry<?, ?> entry : groups.entrySet()) {
                require(entry.getKey() instanceof String, "CURIOS_SLOT_NAME_INVALID");
                String name = (String) entry.getKey();
                Object group = entry.getValue();
                require(group != null && group.getClass().getName().equals(CU + "common.inventory.CurioStacksHandler"),
                        "UNKNOWN_CURIOS_SLOT_HANDLER");
                require(!(boolean) nativeAccess.field(group, CU + "common.inventory.CurioStacksHandler",
                        "update", boolean.class, Modifier.PRIVATE), "CURIOS_SLOT_REFRESH_PENDING");
                require(name.equals(nativeAccess.query(group, CU + "api.type.inventory.ICurioStacksHandler",
                        "getIdentifier", String.class)), "CURIOS_SLOT_IDENTITY_MISMATCH");
                // These getters call update(); the pinned false flag above proves its immediate no-op path.
                Object handler = nativeAccess.query(group, CU + "api.type.inventory.ICurioStacksHandler",
                        "getStacks", nativeAccess.type(CU + "api.type.inventory.IDynamicStackHandler"));
                Object states = nativeAccess.query(group, CU + "api.type.inventory.ICurioStacksHandler",
                        "getActiveStates", NonNullList.class);
                require(handler instanceof IItemHandler && states instanceof List<?>, "CURIOS_SLOT_SIGNATURE");
                IItemHandler stacks = (IItemHandler) handler;
                List<?> active = (List<?>) states;
                int count = boundedCount(stacks.getSlots(), foundHosts.size() + (rows == null ? hosts.size() : rows.size()));
                require(active.size() == count, "CURIOS_ACTIVE_STATE_LAYOUT_CHANGED");
                for (int index = 0; index < count; index++) {
                    require(active.get(index) instanceof Boolean, "CURIOS_ACTIVE_STATE_INVALID");
                    boolean enabled = Boolean.TRUE.equals(active.get(index));
                    if (rows != null) candidate.add(row(player, "curios", name, index, enabled, stacks.getStackInSlot(index)));
                    foundHosts.add(new HostSlot(hostLocator("curios", name, index), "curios", name, index,
                            enabled, stacks, index));
                }
            }
            if (rows != null) rows.addAll(candidate);
            if (hosts != null) hosts.addAll(foundHosts);
            statuses.add(status.with(ReadOnlyNativeAccess.State.OBSERVED, "EXISTING_CURIOS_DATA_OBSERVED"));
        } catch (ReadOnlyNativeAccess.ReadRefused failure) {
            statuses.add(status.with(ReadOnlyNativeAccess.State.NOT_READY, failure.getMessage()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            statuses.add(status.with(ReadOnlyNativeAccess.State.API_MISMATCH, "CURIOS_READ_API_MISMATCH"));
        }
    }

    private void readAccessories(ServerPlayer player, List<SlotView> rows,
                                 List<ReadOnlyNativeAccess.AdapterStatus> statuses, List<HostSlot> hosts) {
        var status = nativeAccess.version("accessories", "1.1.0-beta.48+1.21.1", null,
                "ev_6a8e8bc24798de1c163e635b71bb8c6f150b69eeb90d374c5e95321c3e9be07c");
        if (status.state() != ReadOnlyNativeAccess.State.STATIC_API_MATCH) { statuses.add(status); return; }
        List<SlotView> candidate = new ArrayList<>();
        List<HostSlot> foundHosts = new ArrayList<>();
        try {
            require(!nativeAccess.loaded("cclayer"), "UNREVIEWED_EQUIPMENT_BRIDGE");
            Object attachment = nativeAccess.field(null, AC + "neoforge.AccessoriesForge", "HOLDER_ATTACHMENT_TYPE",
                    AttachmentType.class, Modifier.PUBLIC);
            require(attachment instanceof AttachmentType<?>, "ACCESSORIES_ATTACHMENT_SIGNATURE");
            Object holder = player.getExistingData((AttachmentType<?>) attachment).orElse(null);
            require(holder != null, "ACCESSORIES_NOT_INITIALIZED");
            require(holder.getClass().getName().equals(AC + "impl.AccessoriesHolderImpl"), "UNKNOWN_ACCESSORIES_HOLDER");
            require(!(boolean) nativeAccess.field(holder, AC + "impl.AccessoriesHolderImpl",
                    "loadedFromTag", boolean.class, Modifier.PROTECTED), "ACCESSORIES_DECODE_PENDING");
            // Unlike capability.getContainers(), this existing holder getter cannot initialize/reset a holder.
            Map<?, ?> containers = boundedMap(nativeAccess.query(holder, AC + "impl.AccessoriesHolderImpl",
                    "getSlotContainers", Map.class));
            for (Map.Entry<?, ?> entry : containers.entrySet()) {
                require(entry.getKey() instanceof String, "ACCESSORIES_SLOT_NAME_INVALID");
                String name = (String) entry.getKey();
                Object container = entry.getValue();
                require(container != null && container.getClass().getName().equals(AC + "impl.AccessoriesContainerImpl"),
                        "UNKNOWN_ACCESSORIES_CONTAINER");
                // hasChanged() is the actual pending flag. resizingUpdate records the kind of
                // the last markChanged call and remains true after update() has completed.
                require(!(boolean) nativeAccess.query(container, AC + "api.AccessoriesContainer",
                        "hasChanged", boolean.class), "ACCESSORIES_SLOT_REFRESH_PENDING");
                Object capability = nativeAccess.query(container, AC + "api.AccessoriesContainer",
                        "capability", nativeAccess.type(AC + "api.AccessoriesCapability"));
                require(capability != null && nativeAccess.query(capability, AC + "api.AccessoriesCapability",
                        "entity", LivingEntity.class) == player, "ACCESSORIES_WEARER_MISMATCH");
                require(name.equals(nativeAccess.query(container, AC + "api.AccessoriesContainer",
                        "getSlotName", String.class)), "ACCESSORIES_SLOT_IDENTITY_MISMATCH");
                // getAccessories() calls update(), already proved a no-op for this same-thread observation.
                Object inventory = nativeAccess.query(container, AC + "api.AccessoriesContainer", "getAccessories",
                        nativeAccess.type(AC + "impl.ExpandedSimpleContainer"));
                require(inventory instanceof Container, "ACCESSORIES_INVENTORY_SIGNATURE");
                Container stacks = (Container) inventory;
                int count = boundedCount(stacks.getContainerSize(), foundHosts.size() + (rows == null ? hosts.size() : rows.size()));
                for (int index = 0; index < count; index++) {
                    if (rows != null) candidate.add(row(player, "accessories", name, index, true, stacks.getItem(index)));
                    foundHosts.add(new HostSlot(hostLocator("accessories", name, index), "accessories", name,
                            index, true, new InvWrapper(stacks), index));
                }
            }
            if (rows != null) rows.addAll(candidate);
            if (hosts != null) hosts.addAll(foundHosts);
            statuses.add(status.with(ReadOnlyNativeAccess.State.OBSERVED, "EXISTING_ACCESSORIES_DATA_OBSERVED"));
        } catch (ReadOnlyNativeAccess.ReadRefused failure) {
            statuses.add(status.with(ReadOnlyNativeAccess.State.NOT_READY, failure.getMessage()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            statuses.add(status.with(ReadOnlyNativeAccess.State.API_MISMATCH, "ACCESSORIES_READ_API_MISMATCH"));
        }
    }

    private SlotView row(ServerPlayer player, String source, String slot, int index, boolean active, ItemStack stack)
            throws ReflectiveOperationException {
        require(slot != null && slot.length() <= 128 && index >= 0 && stack != null, "EQUIPMENT_SLOT_INVALID");
        String backend = "";
        String hostState = "EQUIPMENT_ONLY";
        if (!stack.isEmpty() && stack.getItem().getClass().getName()
                .equals("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem")) {
            hostState = "BACKPACK_CONTENTS_NOT_AUTHORIZED";
            if (stack.getCount() == 1 && !stack.has(DataComponents.CUSTOM_DATA)
                    && !stack.has(DataComponents.CONTAINER)
                    && nativeAccess.version("sophisticatedcore", "1.4.77", "1.21.1-1.4.77.2173", "").state()
                    == ReadOnlyNativeAccess.State.STATIC_API_MATCH
                    && nativeAccess.version("sophisticatedbackpacks", "3.25.71", "1.21.1-3.25.71.1997", "").state()
                    == ReadOnlyNativeAccess.State.STATIC_API_MATCH) {
                Object supplier = nativeAccess.field(null, CORE + "init.ModCoreDataComponents", "STORAGE_UUID",
                        Supplier.class, Modifier.PUBLIC);
                require(supplier instanceof Supplier<?>, "BACKPACK_UUID_SIGNATURE");
                Object component = ((Supplier<?>) supplier).get();
                require(component instanceof DataComponentType<?>, "BACKPACK_UUID_SIGNATURE");
                Object uuid = stack.get((DataComponentType<?>) component);
                if (uuid instanceof UUID id) backend = "sophisticatedbackpacks/contents/" + id;
                else hostState = "BACKPACK_NOT_INITIALIZED";
            } else {
                hostState = "BACKPACK_REQUIRES_STORAGE_PROBE";
            }
        }
        return new SlotView(source, slot, index, active,
                ReadOnlyNativeAccess.ownSlotId(player.getUUID(), source, slot, index),
                backend, hostState, ReadOnlyNativeAccess.item(stack));
    }

    static List<BackpackAliases> groupAliases(List<Alias> aliases) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        Set<String> seenHosts = new java.util.HashSet<>();
        for (Alias alias : aliases) {
            Objects.requireNonNull(alias.backend());
            Objects.requireNonNull(alias.host());
            require(!alias.backend().isEmpty() && seenHosts.add(alias.host()), "AMBIGUOUS_EQUIPMENT_HOST");
            groups.computeIfAbsent(alias.backend(), ignored -> new ArrayList<>()).add(alias.host());
        }
        return groups.entrySet().stream().map(entry -> new BackpackAliases(entry.getKey(), entry.getValue())).toList();
    }

    private static Map<?, ?> boundedMap(Object value) {
        require(value instanceof Map<?, ?> && ((Map<?, ?>) value).size() <= MAX_GROUPS, "EQUIPMENT_GROUP_LIMIT");
        return new LinkedHashMap<>((Map<?, ?>) value);
    }

    static int boundedCount(int count, int existing) {
        require(count >= 0 && existing >= 0 && count <= MAX_SLOTS - existing, "EQUIPMENT_SLOT_LIMIT");
        return count;
    }

    static String hostLocator(String source, String slot, int index) {
        require(source.matches("[a-z_]{1,32}") && slot.matches("[a-zA-Z0-9_.:-]{1,128}")
                && index >= 0 && index < MAX_SLOTS, "EQUIPMENT_HOST_INVALID");
        return source + "/" + slot + "/" + index;
    }
}
