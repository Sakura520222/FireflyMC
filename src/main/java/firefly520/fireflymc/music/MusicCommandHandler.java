package firefly520.fireflymc.music;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import firefly520.fireflymc.network.MusicQueueSyncPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * 点歌命令。注册到 NeoForge GAME 总线。
 * 不限定 Dist：单人/LAN/专服都要工作（不复制 SpawnAllCommandHandler 的 DEDICATED_SERVER 限定坑）。
 *
 * /点歌 <歌名>
 * /fireflymc music request <歌名> | queue | skip | stop
 */
@EventBusSubscriber
public class MusicCommandHandler {

    private static final int MAX_KEYWORD_CHARS = 256;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // 中文顶层别名
        dispatcher.register(Commands.literal("点歌")
                .then(Commands.argument("keyword", StringArgumentType.greedyString())
                        .executes(ctx -> requestSong(ctx.getSource(), StringArgumentType.getString(ctx, "keyword")))));

        // 英文路径（沿用 /fireflymc 子命令树风格）
        dispatcher.register(Commands.literal("fireflymc")
                .then(Commands.literal("music")
                        .then(Commands.literal("request")
                                .then(Commands.argument("keyword", StringArgumentType.greedyString())
                                        .executes(ctx -> requestSong(ctx.getSource(), StringArgumentType.getString(ctx, "keyword")))))
                        .then(Commands.literal("queue").executes(ctx -> showQueue(ctx.getSource())))
                        .then(Commands.literal("skip").executes(ctx -> privilegedAction(ctx.getSource(), true)))
                        .then(Commands.literal("stop").executes(ctx -> privilegedAction(ctx.getSource(), false)))));
    }

    /** 点歌：权限/锁检查（服务端线程）→ 虚拟线程搜索+时长探测 → server.execute 回状态机 */
    private static int requestSong(CommandSourceStack source, String keyword) {
        if (keyword.length() > MAX_KEYWORD_CHARS) {
            source.sendFailure(Component.translatable("fireflymc.music.error.keyword_too_long"));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("fireflymc.music.error.player_only"));
            return 0;
        }
        MusicQueueManager manager = MusicServerBridge.manager();
        if (manager == null) {
            return 0;
        }
        MinecraftServer server = player.server;
        boolean privileged = server.isSingleplayerOwner(player.getGameProfile())
                || source.hasPermission(2);

        MusicQueueManager.BeginResult begin = manager.tryBeginRequest(player.getUUID(), privileged);
        switch (begin) {
            case LOCKED -> {
                source.sendFailure(Component.translatable("fireflymc.music.error.locked"));
                return 0;
            }
            case PENDING -> {
                source.sendFailure(Component.translatable("fireflymc.music.error.pending"));
                return 0;
            }
            case QUEUE_FULL -> {
                source.sendFailure(Component.translatable("fireflymc.music.error.queue_full",
                        MusicQueueManager.MAX_QUEUE_SIZE));
                return 0;
            }
        }
        source.sendSuccess(() -> Component.translatable("fireflymc.music.searching", keyword), false);

        // 虚拟线程：搜索 + 时长探测（两个 IO 串行）
        Thread.ofVirtual().name("fireflymc-music-search").start(() -> {
            MusicApiClient.SongInfo found;
            try {
                found = MusicApiClient.search(keyword).join();
            } catch (Exception e) {
                found = null;
            }
            final MusicApiClient.SongInfo info = found;
            if (info == null) {
                server.execute(() -> {
                    manager.failRequest(player.getUUID());
                    if (!player.hasDisconnected()) {
                        player.sendSystemMessage(Component.translatable("fireflymc.music.error.not_found", keyword));
                    }
                });
                return;
            }
            long durationMs = MusicApiClient.probeDurationMs(info.songId()).join();
            QueuedSong song = new QueuedSong(info.songId(), info.title(), info.author(),
                    info.lrc(), player.getGameProfile().getName(), player.getUUID(), durationMs);
            server.execute(() -> {
                boolean accepted = manager.completeRequest(player.getUUID(), song);
                if (!player.hasDisconnected()) {
                    if (accepted) {
                        player.sendSystemMessage(Component.translatable(
                                "fireflymc.music.queued", info.title(), info.author()));
                        // 全服播报
                        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                            if (p != player) {
                                p.sendSystemMessage(Component.translatable(
                                        "fireflymc.music.announce", player.getGameProfile().getName(),
                                        info.title(), info.author()));
                            }
                        }
                    } else {
                        player.sendSystemMessage(Component.translatable("fireflymc.music.error.queue_full",
                                MusicQueueManager.MAX_QUEUE_SIZE));
                    }
                }
            });
        });
        return 1;
    }

    /** /fireflymc music queue：聊天栏输出当前曲+完整队列 */
    private static int showQueue(CommandSourceStack source) {
        MusicQueueManager manager = MusicServerBridge.manager();
        if (manager == null) {
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("fireflymc.music.queue.header"), false);
        List<MusicQueueSyncPayload.SongSummary> queue = manager.queueSummaries();
        if (queue.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("fireflymc.music.queue.empty"), false);
        } else {
            for (int i = 0; i < queue.size(); i++) {
                MusicQueueSyncPayload.SongSummary s = queue.get(i);
                int idx = i + 1;
                source.sendSuccess(() -> Component.translatable(
                        "fireflymc.music.queue.entry", idx, s.title(), s.author(), s.requesterName()), false);
            }
        }
        return 1;
    }

    /** skip（true）/ stop（false）：仅特权（单人 owner / LAN 房主 / OP） */
    private static int privilegedAction(CommandSourceStack source, boolean skip) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        MinecraftServer server = player.server;
        boolean privileged = server.isSingleplayerOwner(player.getGameProfile())
                || source.hasPermission(2);
        if (!privileged) {
            source.sendFailure(Component.translatable("fireflymc.music.error.no_permission"));
            return 0;
        }
        MusicQueueManager manager = MusicServerBridge.manager();
        if (manager == null) {
            return 0;
        }
        if (skip) {
            manager.skip();
        } else {
            manager.stopAll();
        }
        source.sendSuccess(() -> Component.translatable(
                skip ? "fireflymc.music.skipped" : "fireflymc.music.stopped"), false);
        return 1;
    }
}
