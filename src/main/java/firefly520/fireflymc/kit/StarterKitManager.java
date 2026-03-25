package firefly520.fireflymc.kit;

import firefly520.fireflymc.ServerConfig;
import firefly520.fireflymc.event.websocket.StarterKitWebSocketManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 新手福利包管理器
 * 负责给首次加入的玩家发放福利物品
 */
public class StarterKitManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(StarterKitManager.class);

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

    private static final Component SERVICE_UNAVAILABLE_MESSAGE = Component.literal(
        "§c服务暂时不可用，请稍后再试"
    );

    /**
     * 给予玩家新手福利包（如果尚未领取）
     */
    public static void giveStarterKit(ServerPlayer player) {
        // 检查配置是否启用
        if (!ServerConfig.SERVER.enableStarterKit.get()) {
            return;
        }

        // 使用 WebSocket 异步检查
        boolean requestSent = StarterKitWebSocketManager.getInstance()
            .checkClaimedAsync(player, claimed -> {
                // 回调：检查玩家是否仍在服务器
                ServerPlayer onlinePlayer = player.server.getPlayerList().getPlayer(player.getUUID());
                if (onlinePlayer == null) {
                    LOGGER.debug("[FireflyMC] 玩家已下线，跳过福利包给予: {}", player.getUUID());
                    return;
                }

                if (claimed) {
                    // 已领取
                    onlinePlayer.sendSystemMessage(ALREADY_CLAIMED_MESSAGE);
                } else {
                    // 未领取，给予物品
                    giveItems(onlinePlayer);
                    // 标记已领取
                    StarterKitWebSocketManager.getInstance().markClaimed(
                        onlinePlayer,
                        () -> onlinePlayer.sendSystemMessage(WELCOME_MESSAGE),
                        () -> LOGGER.warn("[FireflyMC] 标记福利包领取失败: {}", onlinePlayer.getUUID())
                    );
                }
            });

        // WebSocket 未连接
        if (!requestSent) {
            LOGGER.warn("[FireflyMC] WebSocket未连接，无法给予福利包: {}", player.getGameProfile().getName());
            player.sendSystemMessage(SERVICE_UNAVAILABLE_MESSAGE);
        }
    }

    /**
     * 创建新手福利物品列表
     */
    private static List<ItemStack> createStarterItems() {
        List<ItemStack> items = new ArrayList<>();

        // 石工具套装
        items.add(new ItemStack(Items.STONE_SWORD));
        items.add(new ItemStack(Items.STONE_PICKAXE));
        items.add(new ItemStack(Items.STONE_AXE));
        items.add(new ItemStack(Items.STONE_SHOVEL));

        // 食物
        items.add(new ItemStack(Items.BREAD, 32));

        // 火把
        items.add(new ItemStack(Items.TORCH, 64));

        // 建材
        items.add(new ItemStack(Items.OAK_PLANKS, 64));

        // 床
        items.add(new ItemStack(Items.RED_BED));

        // 箱子
        items.add(new ItemStack(Items.CHEST));

        return items;
    }

    /**
     * 给予物品
     */
    private static void giveItems(ServerPlayer player) {
        List<ItemStack> items = createStarterItems();
        List<ItemStack> droppedItems = new ArrayList<>();

        // 尝试添加到背包
        for (ItemStack item : items) {
            boolean added = player.getInventory().add(item);
            if (!added) {
                droppedItems.add(item);
            }
        }

        // 掉落未添加的物品
        for (ItemStack item : droppedItems) {
            player.spawnAtLocation(item);
        }

        // 发送背包满警告
        if (!droppedItems.isEmpty()) {
            player.sendSystemMessage(INVENTORY_FULL_WARNING);
        }
    }
}
