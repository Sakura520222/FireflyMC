package firefly520.fireflymc.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import firefly520.fireflymc.ServerConfig;
import firefly520.fireflymc.network.PasswordPromptPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * 本地玩家密码管理器
 * <p>
 * 按规范化玩家名绑定密码记录，用于离线模式防顶号。
 * 密码以 salted SHA-256 哈希存储，不保存明文。
 */
public class PlayerPasswordManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerPasswordManager.class);
    private static final PlayerPasswordManager INSTANCE = new PlayerPasswordManager();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<Map<String, PasswordRecord>>() {}.getType();
    private static final int SALT_BYTES = 32;

    private Map<String, PasswordRecord> passwords = new ConcurrentHashMap<>();
    private Path dataFile;
    private boolean loaded = false;

    // 待验证玩家会话：UUID -> 会话状态
    private final Map<UUID, AuthSession> pendingSessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FireflyMC-PasswordAuth-Timeout");
        t.setDaemon(true);
        return t;
    });

    public static PlayerPasswordManager getInstance() {
        return INSTANCE;
    }

    // ========== 数据持久化 ==========

    public synchronized void load(MinecraftServer server) {
        this.dataFile = server.getServerDirectory().resolve("fireflymc_passwords.json");
        if (Files.exists(dataFile)) {
            try {
                String json = Files.readString(dataFile);
                Map<String, PasswordRecord> loaded = GSON.fromJson(json, DATA_TYPE);
                if (loaded != null) {
                    this.passwords = new ConcurrentHashMap<>(loaded);
                }
                LOGGER.info("[FireflyMC] 已加载 {} 条密码记录", passwords.size());
            } catch (Exception e) {
                LOGGER.error("[FireflyMC] 加载密码记录失败", e);
            }
        }
        this.loaded = true;
    }

    private synchronized void saveData() {
        if (dataFile == null) return;
        try {
            String json = GSON.toJson(passwords);
            Files.writeString(dataFile, json);
        } catch (Exception e) {
            LOGGER.error("[FireflyMC] 保存密码记录失败", e);
        }
    }

    // ========== 密码操作 ==========

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String generateSalt() {
        byte[] salt = new byte[SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        return HexFormat.of().formatHex(salt);
    }

    private static String hashPassword(String salt, String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((salt + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public boolean hasPassword(ServerPlayer player) {
        return passwords.containsKey(normalizeName(player.getGameProfile().getName()));
    }

    public boolean hasPasswordByName(String playerName) {
        return passwords.containsKey(normalizeName(playerName));
    }

    public void setPassword(ServerPlayer player, String rawPassword) {
        String key = normalizeName(player.getGameProfile().getName());
        String salt = generateSalt();
        String hash = hashPassword(salt, rawPassword);
        PasswordRecord record = new PasswordRecord(
                hash, salt,
                Instant.now().toString(),
                Instant.now().toString(),
                player.getUUID().toString(),
                player.getGameProfile().getName()
        );
        passwords.put(key, record);
        saveData();
    }

    public boolean verifyPassword(ServerPlayer player, String rawPassword) {
        String key = normalizeName(player.getGameProfile().getName());
        PasswordRecord record = passwords.get(key);
        if (record == null) return false;
        String hash = hashPassword(record.salt, rawPassword);
        if (hash.equals(record.passwordHash)) {
            record.lastVerifiedAt = Instant.now().toString();
            record.lastSeenUuid = player.getUUID().toString();
            record.lastSeenName = player.getGameProfile().getName();
            saveData();
            return true;
        }
        return false;
    }

    public boolean resetPasswordByName(String playerName) {
        String key = normalizeName(playerName);
        PasswordRecord removed = passwords.remove(key);
        if (removed != null) {
            saveData();
            return true;
        }
        return false;
    }

    // ========== 验证会话管理 ==========

    /**
     * 开始密码验证流程：将玩家设为待验证状态并发送密码弹窗
     */
    public void startVerification(ServerPlayer player) {
        if (!ServerConfig.SERVER.playerAuthEnabled.get()) {
            return;
        }

        UUID uuid = player.getUUID();
        boolean isFirstTime = !hasPassword(player);
        int maxAttempts = ServerConfig.SERVER.playerAuthMaxAttempts.get();
        String message = isFirstTime ? "请设置你的服务器密码（至少6位纯数字）" : "请输入你的服务器密码";

        AuthSession session = new AuthSession(isFirstTime, maxAttempts);
        pendingSessions.put(uuid, session);

        // 发送密码弹窗
        PacketDistributor.sendToPlayer(player, new PasswordPromptPayload(
                isFirstTime, message, maxAttempts
        ));

        // 设置验证超时
        int timeout = ServerConfig.SERVER.playerAuthTimeoutSeconds.get();
        ScheduledFuture<?> timeoutTask = timeoutExecutor.schedule(() -> {
            player.server.execute(() -> {
                if (pendingSessions.containsKey(uuid)) {
                    String kickMsg = ServerConfig.SERVER.playerAuthKickMessageTimeout.get();
                    player.connection.disconnect(Component.literal(kickMsg));
                    pendingSessions.remove(uuid);
                }
            });
        }, timeout, TimeUnit.SECONDS);
        session.timeoutFuture = timeoutTask;
    }

    /**
     * 处理密码提交
     * @return true 表示验证流程已结束（成功或踢出），false 表示需要继续尝试
     */
    public boolean handlePasswordSubmit(ServerPlayer player, String rawPassword) {
        UUID uuid = player.getUUID();
        AuthSession session = pendingSessions.get(uuid);
        if (session == null) {
            // 无待验证会话，忽略
            return true;
        }

        // 客户端已做过格式校验，这里再做一次防御性检查
        if (!isValidPassword(rawPassword)) {
            sendRetryPrompt(player, session, "密码格式不正确，请输入至少6位纯数字");
            return false;
        }

        if (session.firstTime) {
            // 首次设置密码
            setPassword(player, rawPassword);
            completeVerification(player);
            player.sendSystemMessage(Component.literal("§a[FireflyMC] 密码设置成功！"));
            // 通知客户端关闭密码弹窗
            PacketDistributor.sendToPlayer(player, new PasswordPromptPayload(false, "", -1));
            return true;
        } else {
            // 验证密码
            if (verifyPassword(player, rawPassword)) {
                completeVerification(player);
                // 通知客户端关闭密码弹窗
                PacketDistributor.sendToPlayer(player, new PasswordPromptPayload(false, "", -1));
                return true;
            } else {
                session.remainingAttempts--;
                if (session.remainingAttempts <= 0) {
                    // 超过最大尝试次数，踢出
                    String kickMsg = ServerConfig.SERVER.playerAuthKickMessageFailed.get();
                    player.connection.disconnect(Component.literal(kickMsg));
                    pendingSessions.remove(uuid);
                    return true;
                } else {
                    sendRetryPrompt(player, session, "密码错误，请重新输入");
                    return false;
                }
            }
        }
    }

    private void sendRetryPrompt(ServerPlayer player, AuthSession session, String errorMessage) {
        // 先发送错误提示
        player.sendSystemMessage(Component.literal("§c" + errorMessage));
        // 再发送新的密码弹窗
        PacketDistributor.sendToPlayer(player, new PasswordPromptPayload(
                session.firstTime, errorMessage + "（剩余 " + session.remainingAttempts + " 次）",
                session.remainingAttempts
        ));
    }

    private void completeVerification(ServerPlayer player) {
        UUID uuid = player.getUUID();
        AuthSession session = pendingSessions.remove(uuid);
        if (session != null && session.timeoutFuture != null) {
            session.timeoutFuture.cancel(false);
        }
    }

    public boolean isPendingVerification(UUID uuid) {
        return pendingSessions.containsKey(uuid);
    }

    public boolean isVerified(UUID uuid) {
        return !pendingSessions.containsKey(uuid);
    }

    public void cleanupPlayer(UUID uuid) {
        AuthSession session = pendingSessions.remove(uuid);
        if (session != null && session.timeoutFuture != null) {
            session.timeoutFuture.cancel(false);
        }
    }

    public void shutdown() {
        timeoutExecutor.shutdownNow();
        pendingSessions.clear();
    }

    private static boolean isValidPassword(String password) {
        if (password == null || password.length() < 6) return false;
        for (char c : password.toCharArray()) {
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    // ========== 内部数据类 ==========

    private static class PasswordRecord {
        String passwordHash;
        String salt;
        String createdAt;
        String lastVerifiedAt;
        String lastSeenUuid;
        String lastSeenName;

        @SuppressWarnings("unused") // Gson deserialization
        public PasswordRecord() {}

        public PasswordRecord(String passwordHash, String salt, String createdAt,
                              String lastVerifiedAt, String lastSeenUuid, String lastSeenName) {
            this.passwordHash = passwordHash;
            this.salt = salt;
            this.createdAt = createdAt;
            this.lastVerifiedAt = lastVerifiedAt;
            this.lastSeenUuid = lastSeenUuid;
            this.lastSeenName = lastSeenName;
        }
    }

    private static class AuthSession {
        final boolean firstTime;
        int remainingAttempts;
        ScheduledFuture<?> timeoutFuture;

        AuthSession(boolean firstTime, int remainingAttempts) {
            this.firstTime = firstTime;
            this.remainingAttempts = remainingAttempts;
        }
    }
}
