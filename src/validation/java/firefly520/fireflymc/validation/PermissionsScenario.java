package firefly520.fireflymc.validation;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.storage.AiJson;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import java.util.*;

/** Native OPAC/FTB fixtures only in the isolated validation world/config, never player instances. */
public final class PermissionsScenario implements AutoCloseable {
    private final MinecraftServer server;
    private final InstalledPermissionAdapters adapters;
    private final JsonArray checks = new JsonArray();
    private final JsonObject evidence = new JsonObject();
    private final long started = System.nanoTime();
    private int phase;
    private String error = "";
    public PermissionsScenario(MinecraftServer server) { this.server = server; adapters = new InstalledPermissionAdapters(server); }
    public JsonObject tick() {
        try {
            if (phase++ == 0) { claims(); return null; }
            if (phase == 2) { ranks(); return null; }
        } catch (Exception | LinkageError failure) { failure.printStackTrace(); error = failure.getClass().getSimpleName() + ": " + failure.getMessage(); }
        return report();
    }
    private void claims() throws Exception {
        require(ModList.get().isLoaded("openpartiesandclaims"), "OPAC_PROFILE_REQUIRED");
        try (var owner = new Session(); var stranger = new Session()) {
            var snapshot = adapters.probe(); require(snapshot.protection().callable(), "OPAC_INTERFACE_NOT_CALLABLE:" + snapshot.protection());
            var level = owner.player.serverLevel(); var dimension = level.dimension().location();
            BlockPos pos = new BlockPos(1024 + Math.floorMod(owner.player.getUUID().hashCode(), 500) * 16, 82, 1024);
            level.getChunkAt(pos); var previous = level.getBlockState(pos); level.setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
            Class<?> api = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI"), contract = Class.forName("xaero.pac.common.server.claims.api.IServerClaimsManagerAPI");
            Object opac = api.getMethod("get", MinecraftServer.class).invoke(null, server), claims = api.getMethod("getServerClaimsManager").invoke(opac);
            int x = pos.getX() >> 4, z = pos.getZ() >> 4;
            var claim = contract.getMethod("claim", ResourceLocation.class, UUID.class, int.class, int.class, int.class, boolean.class);
            var unclaim = contract.getMethod("unclaim", ResourceLocation.class, int.class, int.class);
            require(contract.getMethod("get", ResourceLocation.class, BlockPos.class).invoke(claims, dimension, pos) == null, "FIXTURE_CHUNK_ALREADY_CLAIMED");
            try {
                require(adapters.checkPlace(stranger.player, level, pos.above()).allowed(), "UNCLAIMED_BASELINE_DENIED");
                require(claim.invoke(claims, dimension, owner.player.getUUID(), 0, x, z, false) != null, "NATIVE_CLAIM_NOT_CREATED");
                require(adapters.checkPlace(owner.player, level, pos.above()).allowed(), "OWNER_NATIVE_PLACE_DENIED"); check("native_owner_claim_allows_own_survival_placement");
                var place = adapters.checkPlace(stranger.player, level, pos.above());
                require(!place.allowed() && place.origin() == InstalledPermissionAdapters.Origin.PROTECTION_API, "FOREIGN_NATIVE_PLACE_ALLOWED");
                require(!adapters.checkBreak(stranger.player, level, pos, Direction.UP).allowed(), "FOREIGN_NATIVE_BREAK_ALLOWED");
                require(!adapters.checkContainer(stranger.player, level, pos, Direction.UP).allowed(), "FOREIGN_NATIVE_CONTAINER_ALLOWED");
                check("native_true_means_deny_for_place_break_and_container"); evidence.add("foreignPlace", AiJson.GSON.toJsonTree(place));
                unclaim.invoke(claims, dimension, x, z);
                require(adapters.checkPlace(stranger.player, level, pos.above()).allowed(), "UNCLAIM_DID_NOT_REVOKE_OLD_DENIAL");
                claim.invoke(claims, dimension, stranger.player.getUUID(), 0, x, z, false);
                require(!adapters.checkPlace(owner.player, level, pos.above()).allowed() && adapters.checkPlace(stranger.player, level, pos.above()).allowed(), "CLAIM_OWNER_CHANGE_STALE");
                check("unclaim_and_new_owner_are_rechecked_without_cached_allow");
                Class<?> dataApi = Class.forName("xaero.pac.common.server.player.data.api.ServerPlayerDataAPI");
                Object playerData = dataApi.getMethod("from", ServerPlayer.class).invoke(null, stranger.player);
                var setAdmin = Class.forName("xaero.pac.common.server.player.data.ServerPlayerData").getMethod("setClaimsAdminMode", boolean.class);
                boolean beforeAdmin = (boolean) dataApi.getMethod("isClaimsAdminMode").invoke(playerData);
                try {
                    setAdmin.invoke(playerData, true);
                    var refused = adapters.checkPlace(stranger.player, level, pos.above());
                    require(!refused.allowed() && refused.code().equals("CLAIMS_ADMIN_MODE_ACTIVE"), "ADMIN_MODE_BYPASS");
                    require((boolean) dataApi.getMethod("isClaimsAdminMode").invoke(playerData), "ADAPTER_CHANGED_PLAYER_ADMIN_MODE");
                    check("active_claim_admin_mode_refused_without_mutating_native_mode");
                } finally { setAdmin.invoke(playerData, beforeAdmin); }
                stranger.player.setGameMode(GameType.CREATIVE);
                require(!adapters.checkPlace(stranger.player, level, pos.above()).allowed(), "CREATIVE_BYPASS");
                check("creative_mode_does_not_gain_survival_construction_access");
            } finally { unclaim.invoke(claims, dimension, x, z); level.setBlockAndUpdate(pos, previous); }
        }
    }
    private void ranks() throws Exception {
        require(ModList.get().isLoaded("ftbranks"), "RANKS_PROFILE_REQUIRED");
        try (var owner = new Session(); var other = new Session()) {
            require(adapters.probe().ranks().callable(), "RANKS_INTERFACE_NOT_CALLABLE");
            String node = "fireflymc.ai.remote_build", quota = "fireflymc.ai.validation_quota";
            require(!adapters.checkBooleanPermission(owner.player, node).allowed(), "MISSING_NODE_GRANTED_PROXY");
            require(adapters.checkBooleanPermission(owner.player, node, true).allowed(), "EXPLICIT_BASELINE_NOT_APPLIED");
            check("missing_native_rank_node_uses_only_explicit_baseline");
            Class<?> api = Class.forName("dev.ftb.mods.ftbranks.api.FTBRanksAPI"), managerType = Class.forName("dev.ftb.mods.ftbranks.api.RankManager"),
                    rankType = Class.forName("dev.ftb.mods.ftbranks.api.Rank"), valueType = Class.forName("dev.ftb.mods.ftbranks.api.PermissionValue");
            Object manager = api.getMethod("manager").invoke(null);
            String rankName = "ai_validation_" + UUID.randomUUID().toString().replace("-", "");
            Object rank = managerType.getMethod("createRank", String.class, int.class, boolean.class).invoke(manager, rankName, 10000, false);
            var set = rankType.getMethod("setPermission", String.class, valueType); var parse = valueType.getMethod("parse", String.class);
            try {
                rankType.getMethod("add", GameProfile.class).invoke(rank, owner.player.getGameProfile());
                set.invoke(rank, node, parse.invoke(null, "true"));
                require(adapters.checkBooleanPermission(owner.player, node).allowed() && !adapters.checkBooleanPermission(other.player, node).allowed(), "RANK_MEMBERSHIP_ISOLATION");
                check("native_role_grant_is_bound_to_the_actual_player");
                set.invoke(rank, node, parse.invoke(null, "false"));
                require(!adapters.checkBooleanPermission(owner.player, node, true).allowed(), "EXPLICIT_FALSE_IGNORED");
                check("explicit_false_revokes_previous_allow_immediately");
                set.invoke(rank, node, parse.invoke(null, "7"));
                require(!adapters.checkBooleanPermission(owner.player, node, true).allowed(), "WRONG_PERMISSION_TYPE_GRANTED");
                check("numeric_permission_is_not_coerced_to_boolean");
                set.invoke(rank, quota, parse.invoke(null, "1000")); var bounded = adapters.checkQuotaPermission(owner.player, quota, 2, 32);
                require(bounded.allowed() && bounded.value() == 32, "NATIVE_QUOTA_NOT_BOUNDED");
                set.invoke(rank, quota, parse.invoke(null, "2.5")); require(!adapters.checkQuotaPermission(owner.player, quota, 2, 32).allowed(), "FRACTIONAL_QUOTA_ALLOWED");
                check("native_quota_is_integer_and_clamped_to_server_maximum"); evidence.add("quota", AiJson.GSON.toJsonTree(bounded));
                set.invoke(rank, node, parse.invoke(null, "true")); rankType.getMethod("remove", GameProfile.class).invoke(rank, owner.player.getGameProfile());
                require(!adapters.checkBooleanPermission(owner.player, node).allowed(), "REMOVED_MEMBERSHIP_STILL_ALLOWED");
                check("native_rank_removal_revokes_proxy_permission");
            } finally { rankType.getMethod("remove", GameProfile.class).invoke(rank, owner.player.getGameProfile()); managerType.getMethod("deleteRank", String.class).invoke(manager, rankName); }
        }
    }
    private void check(String label) { checks.add(label); }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
    private JsonObject report() {
        var report = new JsonObject(); report.addProperty("status", error.isEmpty() ? "PASSED" : "FAILED"); report.addProperty("error", error);
        report.add("checks", checks); report.add("nativeEvidence", evidence); report.add("adapters", AiJson.GSON.toJsonTree(adapters.snapshot()));
        report.addProperty("durationMillis", (System.nanoTime() - started) / 1_000_000);
        report.addProperty("scope", "Actual OPAC claims/player modes and FTB rank mutations in an isolated dedicated test world with mock network players; no graphical clients, arbitrary commands, provider requests or actual player data.");
        return report;
    }
    @Override public void close() { }
    private final class Session implements AutoCloseable {
        final ServerPlayer player; final EmbeddedChannel channel;
        Session() {
            UUID id = UUID.randomUUID(); var cookie = new CommonListenerCookie(new GameProfile(id, "perm-" + id.toString().substring(0, 8)),
                    0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); channel = new EmbeddedChannel(connection);
            NetworkRegistry.configureMockConnection(connection); server.getPlayerList().placeNewPlayer(connection, player, cookie);
            ValidationHandshake.reply(player); player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent();
        }
        @Override public void close() { server.getPlayerList().remove(player); net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete(); channel.finishAndReleaseAll(); }
    }
}
