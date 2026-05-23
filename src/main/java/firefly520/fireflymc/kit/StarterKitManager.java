package firefly520.fireflymc.kit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import firefly520.fireflymc.ServerConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 新手福利包管理器（本地存储版本）
 */
public class StarterKitManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(StarterKitManager.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<Map<String, ClaimRecord>>() {}.getType();

    private Map<String, ClaimRecord> claimedMap = new ConcurrentHashMap<>();
    private Path dataFile;

    private static final Component WELCOME_MESSAGE = Component.literal(
        "§a§l欢迎来到FireflyMC！§r\n" +
        "§e你收到了一份新手福利包，快看看背包吧！"
    );

    private static final Component INVENTORY_FULL_WARNING = Component.literal(
        "§c警告：你的背包已满，部分物品掉落在地上！"
    );

    private static final Component ALREADY_CLAIMED_MESSAGE = Component.literal(
        "§a§l欢迎来到FireflyMC！"
    );

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * 确保数据文件已加载（懒加载，首次调用时按需初始化）
     */
    private synchronized void ensureLoaded(MinecraftServer server) {
        if (dataFile == null) {
            dataFile = server.getServerDirectory().resolve("fireflymc_starter_kit.json");
            if (Files.exists(dataFile)) {
                try {
                    String json = Files.readString(dataFile);
                    Map<String, ClaimRecord> loaded = GSON.fromJson(json, DATA_TYPE);
                    if (loaded != null) {
                        this.claimedMap = new ConcurrentHashMap<>(loaded);
                    }
                } catch (Exception e) {
                    LOGGER.error("[FireflyMC] 加载福利包领取记录失败", e);
                }
            }
        }
    }

    private synchronized void saveData() {
        if (dataFile == null) return;
        try {
            Files.writeString(dataFile, GSON.toJson(claimedMap));
        } catch (Exception e) {
            LOGGER.error("[FireflyMC] 保存福利包领取记录失败", e);
        }
    }

    /**
     * 给予玩家新手福利包（如果尚未领取）
     */
    public static void giveStarterKit(ServerPlayer player) {
        if (!ServerConfig.SERVER.enableStarterKit.get()) {
            return;
        }
        giveStarterKitInternal(player);
    }

    private static final StarterKitManager INSTANCE = new StarterKitManager();
    public static StarterKitManager getInstance() { return INSTANCE; }

    /**
     * 内部实现：检查领取状态并发放
     */
    private static void giveStarterKitInternal(ServerPlayer player) {
        StarterKitManager mgr = INSTANCE;
        mgr.ensureLoaded(player.server);

        String key = normalizeName(player.getGameProfile().getName());
        if (mgr.claimedMap.containsKey(key)) {
            player.sendSystemMessage(ALREADY_CLAIMED_MESSAGE);
            return;
        }

        // 未领取，给予物品
        giveItems(player);
        // 标记已领取
        ClaimRecord record = new ClaimRecord(
                player.getUUID().toString(),
                player.getGameProfile().getName(),
                java.time.Instant.now().toString()
        );
        mgr.claimedMap.put(key, record);
        mgr.saveData();
        player.sendSystemMessage(WELCOME_MESSAGE);
    }

    /**
     * 创建新手福利物品列表
     */
    private static List<ItemStack> createStarterItems() {
        List<ItemStack> items = new ArrayList<>();

        items.add(new ItemStack(Items.STONE_SWORD));
        items.add(new ItemStack(Items.STONE_PICKAXE));
        items.add(new ItemStack(Items.STONE_AXE));
        items.add(new ItemStack(Items.STONE_SHOVEL));
        items.add(new ItemStack(Items.BREAD, 32));
        items.add(new ItemStack(Items.TORCH, 64));
        items.add(new ItemStack(Items.OAK_PLANKS, 64));
        items.add(new ItemStack(Items.RED_BED));
        items.add(new ItemStack(Items.CHEST));

        return items;
    }

    /**
     * 给予物品
     */
    private static void giveItems(ServerPlayer player) {
        List<ItemStack> items = createStarterItems();
        List<ItemStack> droppedItems = new ArrayList<>();

        for (ItemStack item : items) {
            boolean added = player.getInventory().add(item);
            if (!added) {
                droppedItems.add(item);
            }
        }

        for (ItemStack item : droppedItems) {
            player.spawnAtLocation(item);
        }

        if (!droppedItems.isEmpty()) {
            player.sendSystemMessage(INVENTORY_FULL_WARNING);
        }
    }

    /**
     * 领取记录
     */
    private static class ClaimRecord {
        String uuid;
        String playerName;
        String claimedAt;

        @SuppressWarnings("unused")
        public ClaimRecord() {}

        public ClaimRecord(String uuid, String playerName, String claimedAt) {
            this.uuid = uuid;
            this.playerName = playerName;
            this.claimedAt = claimedAt;
        }
    }
}
