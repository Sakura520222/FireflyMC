package firefly520.fireflymc.ai;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * AI 函数工具的执行上下文。
 * <p>
 * 统一「玩家触发」与「服务器控制台触发」两条调用路径：
 * 控制台触发时 {@link #player()} 为 {@code null}，并视为最高权限（4 级 OP）。
 * <p>
 * 工具实现只从本上下文获取服务器/玩家，不再各自实现 player/console 两个重载。
 *
 * @param server Minecraft 服务器实例（永不为 null）
 * @param player 触发工具的玩家；控制台调用时为 null
 */
public record ToolContext(MinecraftServer server, ServerPlayer player) {

    /**
     * 是否由服务器控制台触发。
     */
    public boolean isConsole() {
        return player == null;
    }

    /**
     * 权限检查：控制台始终通过；玩家通过其 CommandSourceStack 校验。
     *
     * @param level 所需 OP 权限等级（0-4）
     * @return 是否具备权限
     */
    public boolean hasPermission(int level) {
        if (player == null) {
            return true;
        }
        return player.createCommandSourceStack().hasPermission(level);
    }
}
