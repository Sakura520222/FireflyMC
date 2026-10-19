package firefly520.fireflymc.story.action;

import com.mojang.logging.LogUtils;
import firefly520.fireflymc.story.condition.StoryCondition;
import firefly520.fireflymc.story.engine.StorySignal;
import firefly520.fireflymc.story.progress.StoryProgress;
import firefly520.fireflymc.story.progress.WorldStoryState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Executes built-in story actions on the server main thread.
 *
 * <p>{@link Effects} is the complete side-effect boundary for progress, world state, inventory,
 * presentation, commands, and teleportation. Implementations must perform every effect
 * synchronously on the calling server thread; this executor never dispatches async work.</p>
 */
public final class ActionExecutor {
    public static final int COMMAND_PERMISSION_LEVEL = 2;

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Injected story/effect context. The generic player type keeps the pure contract testable
     * without manufacturing a {@link ServerPlayer}.
     *
     * <p>Implementations must not perform blocking IO or schedule asynchronous side effects.</p>
     */
    public interface Effects<P> {
        boolean isServerThread(P player);

        boolean setFlag(
            P player,
            StoryCondition.Scope scope,
            String flag,
            boolean value
        );

        boolean setPlayerVar(
            P player,
            String var,
            ActionTypes.SetVarAction.Operation operation,
            int amount,
            @Nullable Integer min,
            @Nullable Integer max
        );

        void unlockQuest(P player, ResourceLocation quest);

        ItemStack addToInventory(P player, ResourceLocation item, int count);

        void dropAtFeet(P player, ItemStack remaining);

        void playSound(P player, ResourceLocation sound, float volume, float pitch);

        void showTitle(
            P player,
            Component title,
            @Nullable Component subtitle,
            int fadeIn,
            int stay,
            int fadeOut
        );

        void sendMessage(P player, Component message);

        void executeCommand(P player, String command);

        void teleport(P player, ResourceLocation dimension, double x, double y, double z);
    }

    /**
     * Creates the NeoForge/Minecraft effect adapter using already-resolved production state.
     *
     * <p>The command adapter resolves {@code @s} from the exact player passed to
     * {@link #execute(StoryAction, Object, Effects)} and always overrides the command source's
     * permission level to {@value #COMMAND_PERMISSION_LEVEL}.</p>
     */
    public static Effects<ServerPlayer> minecraftEffects(
        StoryProgress progress,
        WorldStoryState world
    ) {
        return new MinecraftEffects(progress, world);
    }

    /**
     * Executes one action synchronously. Failures propagate to the caller so {@link #executeAll}
     * or a later transition transaction can isolate them.
     */
    public <P> List<StorySignal> execute(
        StoryAction action,
        P player,
        Effects<? super P> effects
    ) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(effects, "effects");
        requireServerThread(player, effects);

        if (action instanceof ActionTypes.SetFlagAction setFlag) {
            boolean changed = effects.setFlag(
                player, setFlag.scope(), setFlag.flag(), setFlag.value());
            return changed
                ? List.of(new StorySignal.FlagChanged(setFlag.flag(), setFlag.scope()))
                : List.of();
        }
        if (action instanceof ActionTypes.SetVarAction setVar) {
            boolean changed = effects.setPlayerVar(
                player,
                setVar.var(),
                setVar.operation(),
                setVar.amount(),
                setVar.min(),
                setVar.max()
            );
            return changed
                ? List.of(new StorySignal.VarChanged(
                    setVar.var(),
                    StoryCondition.Scope.PLAYER))
                : List.of();
        }
        if (action instanceof ActionTypes.UnlockQuestAction unlockQuest) {
            effects.unlockQuest(player, unlockQuest.quest());
            return List.of();
        }
        if (action instanceof ActionTypes.GiveItemAction giveItem) {
            ItemStack remaining = Objects.requireNonNull(
                effects.addToInventory(player, giveItem.item(), giveItem.count()),
                "effect returned a null inventory remainder"
            );
            if (!remaining.isEmpty()) {
                effects.dropAtFeet(player, remaining);
            }
            return List.of(new StorySignal.InventoryChanged());
        }
        if (action instanceof ActionTypes.PlaySoundAction playSound) {
            effects.playSound(
                player, playSound.sound(), playSound.volume(), playSound.pitch());
            return List.of();
        }
        if (action instanceof ActionTypes.ShowTitleAction showTitle) {
            effects.showTitle(
                player,
                showTitle.title(),
                showTitle.subtitle(),
                showTitle.fadeIn(),
                showTitle.stay(),
                showTitle.fadeOut()
            );
            return List.of();
        }
        if (action instanceof ActionTypes.SendMessageAction sendMessage) {
            effects.sendMessage(player, sendMessage.text());
            return List.of();
        }
        if (action instanceof ActionTypes.ExecuteCommandAction executeCommand) {
            // Command text is trusted static datapack content. Do not interpolate any context.
            effects.executeCommand(player, executeCommand.command());
            return List.of();
        }
        if (action instanceof ActionTypes.TeleportAction teleport) {
            effects.teleport(
                player, teleport.dimension(), teleport.x(), teleport.y(), teleport.z());
            return List.of(new StorySignal.LocationChanged());
        }

        throw new IllegalArgumentException(
            "Unsupported story action: " + action.getClass().getName());
    }

    /**
     * Package-private convenience for focused tests. Production callers must use the quest-aware
     * overload so every action failure has an explicit quest ID.
     */
    <P> List<StorySignal> executeAll(
        List<StoryAction> actions,
        P player,
        Effects<? super P> effects
    ) {
        return executeAllInternal(null, actions, player, effects);
    }

    /**
     * Executes a quest's action list and includes the quest ID in per-action error logs.
     *
     * <p>Each action is isolated with a {@link RuntimeException} catch. {@link Error} and other VM
     * failures are allowed to propagate because they are not bounded action failures.</p>
     */
    public <P> List<StorySignal> executeAll(
        ResourceLocation questId,
        List<StoryAction> actions,
        P player,
        Effects<? super P> effects
    ) {
        return executeAllInternal(
            Objects.requireNonNull(questId, "questId"),
            actions,
            player,
            effects
        );
    }

    private <P> List<StorySignal> executeAllInternal(
        @Nullable ResourceLocation questId,
        List<StoryAction> actions,
        P player,
        Effects<? super P> effects
    ) {
        Objects.requireNonNull(actions, "actions");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(effects, "effects");
        requireServerThread(player, effects);

        List<StorySignal> signals = new ArrayList<>();
        for (int index = 0; index < actions.size(); index++) {
            StoryAction action = actions.get(index);
            try {
                signals.addAll(execute(action, player, effects));
            } catch (RuntimeException exception) {
                String actionType = action == null
                    ? "null"
                    : action.getClass().getName();
                LOGGER.error(
                    "Story action failed; continuing: quest={} actionIndex={} "
                        + "actionType={} player={}",
                    questId == null ? "<unspecified>" : questId,
                    index,
                    actionType,
                    safePlayerId(player),
                    exception
                );
            }
        }
        return List.copyOf(signals);
    }

    /**
     * Production command source helper. The source is the quest player's own source with its
     * permission level replaced by the fixed story-action permission.
     */
    static net.minecraft.commands.CommandSourceStack commandSource(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return player.createCommandSourceStack().withPermission(COMMAND_PERMISSION_LEVEL);
    }

    private static <P> void requireServerThread(P player, Effects<? super P> effects) {
        if (!effects.isServerThread(player)) {
            throw new IllegalThreadStateException(
                "Story actions must execute synchronously on the server thread");
        }
    }

    static String safePlayerId(Object player) {
        if (player instanceof ServerPlayer serverPlayer) {
            return serverPlayer.getUUID().toString();
        }
        return player == null ? "<null>" : player.getClass().getSimpleName();
    }

    private static final class MinecraftEffects implements Effects<ServerPlayer> {
        private final StoryProgress progress;
        private final WorldStoryState world;

        private MinecraftEffects(StoryProgress progress, WorldStoryState world) {
            this.progress = Objects.requireNonNull(progress, "progress");
            this.world = Objects.requireNonNull(world, "world");
        }

        @Override
        public boolean isServerThread(ServerPlayer player) {
            return player.server.isSameThread();
        }

        @Override
        public boolean setFlag(
            ServerPlayer player,
            StoryCondition.Scope scope,
            String flag,
            boolean value
        ) {
            return switch (scope) {
                case PLAYER -> progress.setFlag(flag, value);
                case WORLD -> world.setFlag(flag, value);
            };
        }

        @Override
        public boolean setPlayerVar(
            ServerPlayer player,
            String var,
            ActionTypes.SetVarAction.Operation operation,
            int amount,
            @Nullable Integer min,
            @Nullable Integer max
        ) {
            int lowerBound = min == null ? Integer.MIN_VALUE : min;
            int upperBound = max == null ? Integer.MAX_VALUE : max;
            return switch (operation) {
                case SET -> progress.setVar(var, amount, lowerBound, upperBound);
                case ADD -> progress.addVar(var, amount, lowerBound, upperBound);
            };
        }

        @Override
        public void unlockQuest(ServerPlayer player, ResourceLocation quest) {
            progress.activate(quest);
        }

        @Override
        public ItemStack addToInventory(ServerPlayer player, ResourceLocation itemId, int count) {
            Item item = BuiltInRegistries.ITEM.getHolder(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown item: " + itemId))
                .value();
            ItemStack remaining = new ItemStack(item, count);
            player.getInventory().add(remaining);
            return remaining;
        }

        @Override
        public void dropAtFeet(ServerPlayer player, ItemStack remaining) {
            ServerLevel level = player.serverLevel();
            ItemEntity itemEntity = new ItemEntity(
                level,
                player.getX(),
                player.getY(),
                player.getZ(),
                remaining
            );
            itemEntity.setDefaultPickUpDelay();
            if (!level.addFreshEntity(itemEntity)) {
                throw new IllegalStateException(
                    "Failed to spawn story item remainder "
                        + remaining.getItem() + " x" + remaining.getCount()
                );
            }
        }

        @Override
        public void playSound(
            ServerPlayer player,
            ResourceLocation soundId,
            float volume,
            float pitch
        ) {
            SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getHolder(soundId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown sound: " + soundId))
                .value();
            player.playNotifySound(sound, SoundSource.PLAYERS, volume, pitch);
        }

        @Override
        public void showTitle(
            ServerPlayer player,
            Component title,
            @Nullable Component subtitle,
            int fadeIn,
            int stay,
            int fadeOut
        ) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
            player.connection.send(new ClientboundSetSubtitleTextPacket(
                subtitle == null ? Component.empty() : subtitle
            ));
            player.connection.send(new ClientboundSetTitleTextPacket(title));
        }

        @Override
        public void sendMessage(ServerPlayer player, Component message) {
            player.sendSystemMessage(message);
        }

        @Override
        public void executeCommand(ServerPlayer player, String command) {
            player.server.getCommands()
                .performPrefixedCommand(commandSource(player), command);
        }

        @Override
        public void teleport(
            ServerPlayer player,
            ResourceLocation dimensionId,
            double x,
            double y,
            double z
        ) {
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
            ServerLevel level = player.server.getLevel(dimension);
            if (level == null) {
                throw new IllegalArgumentException("Unknown dimension: " + dimensionId);
            }
            player.teleportTo(level, x, y, z, player.getYRot(), player.getXRot());
        }
    }
}
