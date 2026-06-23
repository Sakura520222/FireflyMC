package firefly520.fireflymc.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 自动建造锚点管理器。
 * <p>
 * 建造工具的相对坐标不能直接绑定玩家实时位置：玩家在 AI 多轮建造或后续追加需求时移动，
 * 后续 fill/place/get 会整体错位。因此每个目标玩家维护一个持久建造锚点：
 * <ul>
 *   <li>首次建造时自动锁定为目标玩家当时脚下位置；</li>
 *   <li>后续建造/查看/修补继续使用同一锚点，即使玩家移动；</li>
 *   <li>需要换地方时，由 AI 调用 set_build_anchor 显式重置。</li>
 * </ul>
 */
public final class BuildAnchorManager {
    private static final Map<UUID, BuildAnchor> ANCHORS = new ConcurrentHashMap<>();

    private BuildAnchorManager() {
    }

    public static BuildAnchor getOrCreate(ServerPlayer player) {
        return ANCHORS.computeIfAbsent(player.getUUID(), ignored -> current(player));
    }

    public static BuildAnchor setTo(ServerPlayer player, BlockPos pos) {
        BuildAnchor anchor = new BuildAnchor(player.serverLevel().dimension(), pos.immutable());
        ANCHORS.put(player.getUUID(), anchor);
        return anchor;
    }

    public static BuildAnchor resetToCurrent(ServerPlayer player) {
        return setTo(player, player.blockPosition());
    }

    /**
     * 清空所有建造锚点（服务器关闭时调用，防止静态 Map 长期累积）。
     */
    public static void clear() {
        ANCHORS.clear();
    }

    private static BuildAnchor current(ServerPlayer player) {
        return new BuildAnchor(player.serverLevel().dimension(), player.blockPosition().immutable());
    }

    public record BuildAnchor(ResourceKey<Level> dimension, BlockPos pos) {
        public Optional<ServerLevel> level(MinecraftServer server) {
            return Optional.ofNullable(server.getLevel(dimension));
        }

        public String describe() {
            return dimension.location() + " " + pos.toShortString();
        }
    }
}
