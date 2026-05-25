package firefly520.fireflymc.client.eventws;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import firefly520.fireflymc.FireflyMCMod;
import firefly520.fireflymc.util.ServerLanguageLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 客户端玩家事件通知消息。
 */
public final class ClientEventNotificationMessage {
    private static final Gson GSON = new Gson();

    private final JsonObject json;

    private ClientEventNotificationMessage(String type) {
        this.json = new JsonObject();
        this.json.addProperty("type", type);
        this.json.addProperty("eventId", UUID.randomUUID().toString());
        this.json.addProperty("timestamp", System.currentTimeMillis());
        this.json.addProperty("modVersion", FireflyMCMod.VERSION);
        this.json.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().getId());
    }

    public static ClientEventNotificationMessage create(String type) {
        return new ClientEventNotificationMessage(type);
    }

    public static ClientEventNotificationMessage heartbeat() {
        return create("heartbeat");
    }

    public static ClientEventNotificationMessage multiplayerJoin(Minecraft minecraft, LocalPlayer player) {
        return create("multiplayer_join")
            .addPlayer(player)
            .addWorldContext(minecraft, "multiplayer");
    }

    public static ClientEventNotificationMessage singleplayerEnter(Minecraft minecraft, LocalPlayer player, String worldName) {
        return create("singleplayer_enter")
            .addPlayer(player)
            .addWorldContext(minecraft, "singleplayer")
            .add("worldName", worldName);
    }

    public static ClientEventNotificationMessage playerDeath(Minecraft minecraft, LocalPlayer player, Component message) {
        return create("player_death")
            .addPlayer(player)
            .addWorldContext(minecraft, resolveWorldType(minecraft))
            .add("deathMessage", componentToString(message));
    }

    public static ClientEventNotificationMessage advancementEarned(Minecraft minecraft, LocalPlayer player, String advancementId,
                                                                    Component title, Component description) {
        return create("advancement_earned")
            .addPlayer(player)
            .addWorldContext(minecraft, resolveWorldType(minecraft))
            .add("advancementId", advancementId)
            .add("advancementTitle", componentToString(title))
            .add("advancementDescription", componentToString(description));
    }

    public ClientEventNotificationMessage add(String key, String value) {
        if (value != null && !value.isBlank()) {
            this.json.addProperty(key, value);
        }
        return this;
    }

    public ClientEventNotificationMessage add(String key, Number value) {
        if (value != null) {
            this.json.addProperty(key, value);
        }
        return this;
    }

    public ClientEventNotificationMessage add(String key, Boolean value) {
        if (value != null) {
            this.json.addProperty(key, value);
        }
        return this;
    }

    public String type() {
        return this.json.get("type").getAsString();
    }

    public String toJson() {
        return GSON.toJson(this.json);
    }

    private ClientEventNotificationMessage addPlayer(LocalPlayer player) {
        if (player == null) {
            return this;
        }
        return add("playerName", player.getGameProfile().getName())
            .add("playerUuid", player.getGameProfile().getId().toString());
    }

    private ClientEventNotificationMessage addWorldContext(Minecraft minecraft, String worldType) {
        add("worldType", worldType);
        if (minecraft == null) {
            return this;
        }

        ServerData serverData = minecraft.getCurrentServer();
        if (serverData != null) {
            add("serverName", serverData.name);
            add("serverAddress", serverData.ip);
            add("serverType", serverData.type().name().toLowerCase());
        }

        if (minecraft.getSingleplayerServer() != null) {
            try {
                add("worldName", minecraft.getSingleplayerServer().getWorldData().getLevelName());
            } catch (Exception ignored) {
                // 使用已有上下文信息
            }
        }
        return this;
    }

    private static String resolveWorldType(Minecraft minecraft) {
        if (minecraft != null && minecraft.getSingleplayerServer() != null) {
            return "singleplayer";
        }
        return "multiplayer";
    }

    private static String componentToString(Component component) {
        return ServerLanguageLoader.translateComponent(component);
    }
}
