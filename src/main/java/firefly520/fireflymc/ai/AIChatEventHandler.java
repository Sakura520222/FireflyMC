package firefly520.fireflymc.ai;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.contents.TranslatableContents;

import firefly520.fireflymc.event.websocket.PlayerEventWebSocketClient;
import firefly520.fireflymc.event.websocket.PlayerEventMessage;
import firefly520.fireflymc.util.ServerLanguageLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI聊天事件处理器
 *
 * 使用 /ai <消息> 命令与AI对话
 *
 * ⚠️ 规范说明：
 * - value = Dist.DEDICATED_SERVER 确保仅在服务端加载
 * - 监听 ServerStoppingEvent 清理资源，防止内存泄漏
 */
@EventBusSubscriber(modid = "fireflymc", value = Dist.DEDICATED_SERVER)
public class AIChatEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AIChatEventHandler.class);

    // 每个服务器实例一个历史管理器
    private static final ConcurrentHashMap<MinecraftServer, ChatHistoryManager> HISTORY_MANAGERS = new ConcurrentHashMap<>();

    // 玩家冷却时间记录 (UUID -> 上次触发时间的毫秒时间戳)
    private static final ConcurrentHashMap<UUID, Long> PLAYER_COOLDOWNS = new ConcurrentHashMap<>();

    // 消息计数器（每服务器实例）
    private static final ConcurrentHashMap<MinecraftServer, AtomicInteger> MESSAGE_COUNTERS = new ConcurrentHashMap<>();

    // 命令补全建议
    private static final SuggestionProvider<CommandSourceStack> AI_SUGGESTIONS = (context, builder) -> {
        String[] suggestions = {
            "你好",
            "我们在玩什么",
            "讲个笑话",
            "介绍一下你自己",
            "服务器有多少人在线"
        };
        for (String s : suggestions) {
            builder.suggest(s);
        }
        return builder.buildFuture();
    };

    /**
     * 注册AI聊天命令
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // 注册 /ai 命令
        dispatcher.register(Commands.literal("ai")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .suggests(AI_SUGGESTIONS)
                        .executes(AIChatEventHandler::handleAiCommand))
                .executes(AIChatEventHandler::handleAiCommandHelp));
    }

    /**
     * 记录玩家聊天消息到AI历史上下文
     * 此方法由 ServerChatMixin 调用
     */
    public static void recordPlayerChat(ServerPlayer player, String message) {
        if (!AIConfig.getEnabled()) {
            return;
        }

        var server = player.getServer();
        if (!isMultiplayerServer(server)) {
            return;
        }

        var historyManager = getHistoryManager(server);

        // 记录玩家聊天消息到AI历史
        historyManager.addMessage(new ChatMessage(
                player.getGameProfile().getName(),
                message,
                MessageType.PLAYER
        ));

        // 发送WebSocket广播
        PlayerEventWebSocketClient.sendEvent(
                PlayerEventMessage.playerChat(player.getGameProfile().getName(), message)
        );

        // 检测唤醒词：消息包含"小樱"时自动触发AI回复
        if (message.contains("小樱") && !isOnCooldown(player)) {
            recordTrigger(player);
            callAIAsync(server, player, historyManager, message, false);
            return; // 唤醒词触发后跳过主动回复判断
        }

        // 主动回复判断（与唤醒词互斥）
        checkProactiveReply(server, player, historyManager);
    }

    /**
     * 处理AI命令
     */
    private static int handleAiCommand(CommandContext<CommandSourceStack> context) {
        // 检查配置是否启用
        if (!AIConfig.getEnabled()) {
            context.getSource().sendFailure(Component.literal("§cAI聊天功能未启用"));
            return 0;
        }

        var source = context.getSource();
        var player = source.getPlayer();

        if (player == null) {
            source.sendFailure(Component.literal("§c该命令只能由玩家执行"));
            return 0;
        }

        // 检查是否多人服务器
        if (!isMultiplayerServer(player.getServer())) {
            source.sendFailure(Component.literal("§cAI聊天仅在多人服务器可用"));
            return 0;
        }

        var server = player.getServer();
        var historyManager = getHistoryManager(server);

        // 获取命令参数（消息内容）
        String prompt = StringArgumentType.getString(context, "message");

        if (prompt.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§e用法: /ai <消息> - 向AI发送消息"), false);
            return 1;
        }

        // 检查冷却时间
        if (isOnCooldown(player)) {
            long elapsed = (System.currentTimeMillis() -
                    PLAYER_COOLDOWNS.get(player.getUUID())) / 1000;
            long remaining = AIConfig.getCooldownSeconds() - elapsed;

            player.sendSystemMessage(Component.literal(
                    "§c请等待 " + remaining + " 秒后再试~"
            ));
            return 0;
        }

        // 记录触发时间
        recordTrigger(player);

        // 记录玩家消息到历史
        historyManager.addMessage(new ChatMessage(
                player.getName().getString(),
                prompt,
                MessageType.PLAYER
        ));

        // 广播玩家消息到聊天区（与普通聊天格式一致）
        broadcastPlayerMessage(server, player.getName().getString(), prompt);

        // 发送WebSocket广播（玩家聊天消息）
        PlayerEventWebSocketClient.sendEvent(
                PlayerEventMessage.playerChat(player.getName().getString(), prompt)
        );

        // 异步调用AI
        callAIAsync(server, player, historyManager, prompt, false);

        return 1;
    }

    /**
     * 处理AI命令帮助（无参数）
     */
    private static int handleAiCommandHelp(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
                "§e用法: /ai <消息> - 向AI发送消息"
        ), false);
        return 1;
    }

    /**
     * 获取或创建历史管理器
     */
    private static ChatHistoryManager getHistoryManager(MinecraftServer server) {
        return HISTORY_MANAGERS.computeIfAbsent(server, s -> new ChatHistoryManager(
                AIConfig.getMaxHistorySize()
        ));
    }

    /**
     * 检查玩家是否在冷却中
     */
    private static boolean isOnCooldown(ServerPlayer player) {
        int cooldownSeconds = AIConfig.getCooldownSeconds();
        if (cooldownSeconds <= 0) {
            return false;
        }

        UUID playerId = player.getUUID();
        Long lastTriggerTime = PLAYER_COOLDOWNS.get(playerId);

        if (lastTriggerTime == null) {
            return false;
        }

        long elapsed = (System.currentTimeMillis() - lastTriggerTime) / 1000;
        return elapsed < cooldownSeconds;
    }

    /**
     * 记录玩家触发时间
     */
    private static void recordTrigger(ServerPlayer player) {
        PLAYER_COOLDOWNS.put(player.getUUID(), System.currentTimeMillis());
    }

    /**
     * 检查是否需要主动回复
     */
    private static void checkProactiveReply(MinecraftServer server,
                                            ServerPlayer player,
                                            ChatHistoryManager historyManager) {
        if (!AIConfig.getProactiveEnabled()) {
            return;
        }

        AtomicInteger counter = MESSAGE_COUNTERS.computeIfAbsent(
            server, s -> new AtomicInteger(0)
        );
        int count = counter.incrementAndGet();

        int interval = AIConfig.getProactiveInterval();
        if (count < interval) {
            return;
        }

        counter.set(0);

        if (isOnCooldown(player)) {
            return;
        }

        shouldReplyAsync(server, player, historyManager);
    }

    /**
     * 异步判断是否应该回复，如果是则调用AI
     */
    private static void shouldReplyAsync(MinecraftServer server,
                                         ServerPlayer player,
                                         ChatHistoryManager historyManager) {
        CompletableFuture.supplyAsync(() -> {
            var history = List.copyOf(historyManager.getHistory());
            return AIApiClient.shouldReply(history);
        }).thenAccept(response -> {
            server.execute(() -> {
                if (response.shouldReply()) {
                    recordTrigger(player);
                    String prompt = "[主动回复] " + response.reason();
                    callAIAsync(server, player, historyManager, prompt, false);
                }
            });
        });
    }

    /**
     * 监听玩家加入事件
     */
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!AIConfig.getEnabled()) {
            return;
        }

        var server = event.getEntity().getServer();
        if (isMultiplayerServer(server)) {
            var historyManager = getHistoryManager(server);
            historyManager.addMessage(new ChatMessage(
                    "Server",
                    event.getEntity().getName().getString() + " 加入了游戏",
                    MessageType.SYSTEM
            ));

            // 发送WebSocket广播
            PlayerEventWebSocketClient.sendEvent(PlayerEventMessage.join(event.getEntity().getName().getString()));
        }
    }

    /**
     * 监听玩家离开事件
     */
    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!AIConfig.getEnabled()) {
            return;
        }

        var server = event.getEntity().getServer();
        if (isMultiplayerServer(server)) {
            var historyManager = getHistoryManager(server);
            historyManager.addMessage(new ChatMessage(
                    "Server",
                    event.getEntity().getName().getString() + " 离开了游戏",
                    MessageType.SYSTEM
            ));
            // 清理该玩家的冷却记录
            PLAYER_COOLDOWNS.remove(event.getEntity().getUUID());

            // 发送WebSocket广播
            PlayerEventWebSocketClient.sendEvent(PlayerEventMessage.leave(event.getEntity().getName().getString()));
        }
    }

    /**
     * 监听玩家死亡事件
     */
    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!AIConfig.getEnabled()) {
            return;
        }

        // 只处理玩家死亡
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        var server = player.getServer();
        if (!isMultiplayerServer(server)) {
            return;
        }

        // 获取死亡消息
        var source = event.getSource();
        var deathComponent = source.getLocalizedDeathMessage(event.getEntity());

        // 尝试获取中文翻译
        String deathMessage;
        if (deathComponent.getContents() instanceof TranslatableContents translatableContents) {
            String translationKey = translatableContents.getKey();
            // 获取翻译模板并替换参数
            String template = ServerLanguageLoader.getTranslation(translationKey);
            Object[] args = translatableContents.getArgs();

            // 简单替换 %s 占位符
            deathMessage = formatTranslatedMessage(template, args);
        } else {
            deathMessage = deathComponent.getString();
        }

        // 记录到 AI 历史（系统消息）
        var historyManager = getHistoryManager(server);
        historyManager.addMessage(new ChatMessage(
                "Server",
                player.getGameProfile().getName() + " " + deathMessage,
                MessageType.SYSTEM
        ));

        // 发送WebSocket广播
        PlayerEventWebSocketClient.sendEvent(
                PlayerEventMessage.death(player.getGameProfile().getName(), deathMessage)
        );
    }

    /**
     * 格式化翻译消息（替换 %1$s、%2$s 占位符）
     */
    private static String formatTranslatedMessage(String template, Object[] args) {
        if (args == null || args.length == 0) {
            return template;
        }

        String result = template;
        for (int i = 0; i < args.length; i++) {
            String argStr;
            if (args[i] instanceof Component component) {
                // 参数是 Component，尝试翻译
                if (component.getContents() instanceof TranslatableContents translatableContents) {
                    argStr = ServerLanguageLoader.getTranslation(translatableContents.getKey());
                } else {
                    argStr = component.getString();
                }
            } else {
                argStr = String.valueOf(args[i]);
            }
            // 替换 %1$s、%2$s 等占位符
            result = result.replace("%" + (i + 1) + "$s", argStr);
        }
        return result;
    }

    /**
     * 监听玩家解锁成就事件
     */
    @SubscribeEvent
    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (!AIConfig.getEnabled()) {
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        var server = player.getServer();
        if (!isMultiplayerServer(server)) {
            return;
        }

        var advancementHolder = event.getAdvancement();
        if (advancementHolder == null) {
            return;
        }

        // 从AdvancementHolder获取Advancement实例（1.20.5+ 必须步骤）
        Advancement advancement = advancementHolder.value();

        // 获取DisplayInfo，必须做Optional判空
        Optional<DisplayInfo> displayOptional = advancement.display();
        if (displayOptional.isEmpty()) {
            // 无显示信息的成就（如配方解锁、内部隐藏成就），直接跳过
            return;
        }

        DisplayInfo display = displayOptional.get();
        Component titleComponent = display.getTitle();

        // 尝试获取中文翻译
        String advancementTitle;
        if (titleComponent.getContents() instanceof TranslatableContents translatableContents) {
            // 提取翻译键，使用ServerLanguageLoader获取中文文本
            String translationKey = translatableContents.getKey();
            advancementTitle = ServerLanguageLoader.getTranslation(translationKey);
        } else {
            // 回退使用原始文本（如纯文本成就）
            advancementTitle = titleComponent.getString();
        }

        // 记录到 AI 历史（系统消息）
        var historyManager = getHistoryManager(server);
        historyManager.addMessage(new ChatMessage(
                "Server",
                player.getGameProfile().getName() + " 解锁了成就: " + advancementTitle,
                MessageType.SYSTEM
        ));

        // 发送WebSocket广播
        PlayerEventWebSocketClient.sendEvent(
                PlayerEventMessage.advancement(player.getGameProfile().getName(), advancementTitle)
        );
    }

    /**
     * 监听服务器关闭事件
     *
     * ⚠️ 规范：必须清理以MinecraftServer为key的资源，防止内存泄漏
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        HISTORY_MANAGERS.remove(server);
        PLAYER_COOLDOWNS.clear();
        MESSAGE_COUNTERS.remove(server);

        // 关闭WebSocket连接
        PlayerEventWebSocketClient.shutdown();
    }

    /**
     * 检查是否是多人服务器
     */
    private static boolean isMultiplayerServer(MinecraftServer server) {
        if (server == null) {
            return false;
        }
        // 严谨判断：单人模式、局域网都排除
        return server.isDedicatedServer() ||
               (!server.isSingleplayer() && server.getPlayerList().getPlayerCount() > 1);
    }

    /**
     * 异步调用AI API
     *
     * ⚠️ 关键：网络请求必须在异步线程执行，否则服务器会卡死
     * 广播消息必须回到主线程执行，否则线程不安全
     *
     * @param hasExecutedFunction 当前轮次是否已执行过函数调用
     */
    private static void callAIAsync(MinecraftServer server, ServerPlayer player,
                                     ChatHistoryManager historyManager, String prompt,
                                     boolean hasExecutedFunction) {
        String playerName = player.getName().getString();

        CompletableFuture.supplyAsync(() -> {
            // 异步线程：执行网络请求
            var history = List.copyOf(historyManager.getHistory());
            // 获取启用的函数工具
            List<AIFunctionTool> tools = AIConfig.getFunctionsEnabled()
                    ? new java.util.ArrayList<>(FunctionToolRegistry.getAllTools())
                    : null;
            return AIApiClient.callAIWithFunctions(history, prompt, playerName, tools);
        }).thenAccept(response -> {
            // 回到主线程：发送游戏消息
            server.execute(() -> {
                if (!response.isSuccess()) {
                    // 发送错误提示
                    player.sendSystemMessage(AIApiClient.getErrorComponent(response.errorType()));
                    return;
                }

                if (response.hasToolCalls()) {
                    if (hasExecutedFunction) {
                        // 已经执行过函数调用，不再执行，要求AI用文字回复
                        callAIAsync(server, player, historyManager,
                                   "[请直接用文字回复玩家，不要再调用函数]", true);
                    } else {
                        // 首次执行函数调用
                        handleToolCalls(server, player, historyManager, response.toolCalls());
                    }
                } else if (response.content() != null && !response.content().isEmpty()) {
                    // 成功获取回复
                    broadcastReply(server, player, response.content());

                    // 添加AI回复到历史
                    historyManager.addMessage(new ChatMessage(
                            AIConfig.getAiNamePlain(),
                            response.content(),
                            MessageType.ASSISTANT
                    ));
                }
            });
        });
    }

    /**
     * 处理AI返回的工具调用
     * 每次只执行第一个函数调用
     */
    private static void handleToolCalls(MinecraftServer server, ServerPlayer player,
                                        ChatHistoryManager historyManager,
                                        java.util.List<FunctionCallRequest> toolCalls) {
        if (toolCalls.isEmpty()) {
            return;
        }

        // 只执行第一个函数调用
        var call = toolCalls.get(0);

        // 验证权限
        if (!FunctionToolRegistry.hasPermissionForTool(player, call.name())) {
            // 权限不足，通知AI
            sendToolCallResultToAI(server, player, historyManager, call.id(),
                    FunctionCallResult.failure(FunctionCallResult.ErrorType.PERMISSION_DENIED,
                            "权限不足：需要" + FunctionToolRegistry.getRequiredPermissionLevel(call.name()) + "级OP权限"));
        } else {
            // 执行函数
            FunctionToolRegistry.getTool(call.name()).ifPresent(tool -> {
                FunctionCallResult result = tool.execute(player, call.arguments());
                sendToolCallResultToAI(server, player, historyManager, call.id(), result);
            });
        }

        // 继续调用AI获取最终回复
        callAIAsync(server, player, historyManager,
                   "[函数调用已完成，请根据结果回复玩家]", true);
    }

    /**
     * 将工具执行结果发送给AI
     * 通过添加一条系统消息到历史记录中
     */
    private static void sendToolCallResultToAI(MinecraftServer server, ServerPlayer player,
                                               ChatHistoryManager historyManager,
                                               String callId, FunctionCallResult result) {
        // 将工具调用结果添加到历史记录
        String resultMessage;
        if (result.isSuccess()) {
            resultMessage = "工具调用成功：" + result.getMessage();
        } else {
            resultMessage = "工具调用失败：" + result.getMessage();
        }

        // 添加为系统消息（工具结果），避免最后一条是assistant角色
        historyManager.addMessage(new ChatMessage(
                "System",
                resultMessage,
                MessageType.SYSTEM
        ));
    }

    /**
     * 广播玩家消息（与普通聊天格式一致）
     */
    private static void broadcastPlayerMessage(MinecraftServer server, String playerName, String message) {
        Component playerMessage = Component.literal("<")
            .append(Component.literal(playerName))
            .append("> ")
            .append(Component.literal(message));

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.displayClientMessage(playerMessage, false);
        }
    }

    /**
     * 广播AI回复（玩家样式聊天消息，非系统消息）
     *
     * 使用 displayClientMessage() 发送，避免 UUID 验证问题
     * 手动构建 <名称> 消息 格式，与玩家聊天一致
     */
    private static void broadcastReply(MinecraftServer server, ServerPlayer triggerPlayer, String reply) {
        // 樱花粉颜色 #FFB7C5
        final TextColor SAKURA_PINK = TextColor.fromRgb(0xFFB7C5);

        // 构建AI名称组件（带交互效果）
        Component aiNameComponent = Component.literal(AIConfig.getAiNamePlain())
            .withStyle(style -> style
                .withColor(SAKURA_PINK)
                // 悬浮显示AI信息
                .withHoverEvent(new HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    Component.literal(AIConfig.getAiNamePlain())
                        .withStyle(s -> s.withColor(SAKURA_PINK))
                        .append(Component.literal("\n类型: FireflyMC-AI助手")
                            .withStyle(ChatFormatting.GRAY))
                ))
                // 点击名称自动填充私聊命令
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/msg " + AIConfig.getAiNamePlain() + " "))
            );

        // 拼接完整聊天消息，匹配原版 <玩家名> 消息 格式
        Component fullChatMessage = Component.literal("<")
            .append(aiNameComponent)
            .append("> ")
            .append(Component.literal(reply));

        // 发送给目标玩家（overlay=false 表示进入聊天窗口，不是动作栏）
        if (AIConfig.getBroadcastToAll()) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.displayClientMessage(fullChatMessage, false);
            }
        } else {
            triggerPlayer.displayClientMessage(fullChatMessage, false);
        }

        // 发送WebSocket广播（AI聊天消息）
        PlayerEventWebSocketClient.sendEvent(PlayerEventMessage.aiChat(reply));
    }

    // ========== WebSocket消息处理 ==========

    /**
     * 记录WebSocket消息到AI上下文
     *
     * @param server Minecraft服务器实例
     * @param message WebSocket消息
     */
    public static void recordWebSocketMessage(MinecraftServer server, firefly520.fireflymc.event.websocket.ServerMessage message) {
        if (!AIConfig.getEnabled()) {
            return;
        }

        if (!isMultiplayerServer(server)) {
            return;
        }

        var historyManager = getHistoryManager(server);
        historyManager.addMessage(new ChatMessage(
                message.getSenderOrDefault(),
                message.getMessage(),
                MessageType.PLAYER
        ));
    }

    /**
     * 触发AI回复（无触发玩家，用于WebSocket消息）
     *
     * @param server Minecraft服务器实例
     * @param prompt 提示词
     */
    public static void triggerAIReplyNoPlayer(MinecraftServer server, String prompt) {
        if (!AIConfig.getEnabled()) {
            return;
        }

        if (!isMultiplayerServer(server)) {
            return;
        }

        var historyManager = getHistoryManager(server);
        callAIAsyncNoPlayer(server, historyManager, prompt);
    }

    /**
     * 异步调用AI API（无触发玩家版本）
     */
    private static void callAIAsyncNoPlayer(MinecraftServer server,
                                             ChatHistoryManager historyManager,
                                             String prompt) {
        CompletableFuture.supplyAsync(() -> {
            // 异步线程：执行网络请求
            var history = List.copyOf(historyManager.getHistory());
            return AIApiClient.callAI(history, prompt, "Server");
        }).thenAccept(response -> {
            // 回到主线程：发送游戏消息
            server.execute(() -> {
                if (response.isSuccess()) {
                    // 成功获取回复
                    broadcastReplyNoPlayer(server, response.content());

                    // 添加AI回复到历史
                    historyManager.addMessage(new ChatMessage(
                            AIConfig.getAiNamePlain(),
                            response.content(),
                            MessageType.ASSISTANT
                    ));
                } else {
                    // 无触发玩家，无法发送错误提示，仅记录日志
                    LOGGER.error("[FireflyMC] AI回复失败 (WebSocket触发): {}", response.errorType());
                }
            });
        });
    }

    /**
     * 广播AI回复（无触发玩家版本）
     */
    private static void broadcastReplyNoPlayer(MinecraftServer server, String reply) {
        // 樱花粉颜色 #FFB7C5
        final TextColor SAKURA_PINK = TextColor.fromRgb(0xFFB7C5);

        // 构建AI名称组件（带交互效果）
        Component aiNameComponent = Component.literal(AIConfig.getAiNamePlain())
            .withStyle(style -> style
                .withColor(SAKURA_PINK)
                // 悬浮显示AI信息
                .withHoverEvent(new HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    Component.literal(AIConfig.getAiNamePlain())
                        .withStyle(s -> s.withColor(SAKURA_PINK))
                        .append(Component.literal("\n类型: FireflyMC-AI助手")
                            .withStyle(ChatFormatting.GRAY))
                ))
                // 点击名称自动填充私聊命令
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/msg " + AIConfig.getAiNamePlain() + " "))
            );

        // 拼接完整聊天消息，匹配原版 <玩家名> 消息 格式
        Component fullChatMessage = Component.literal("<")
            .append(aiNameComponent)
            .append("> ")
            .append(Component.literal(reply));

        // 广播给所有在线玩家
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.displayClientMessage(fullChatMessage, false);
        }

        // 发送WebSocket广播（AI聊天消息）
        PlayerEventWebSocketClient.sendEvent(PlayerEventMessage.aiChat(reply));
    }
}
