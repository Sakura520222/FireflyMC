package firefly520.fireflymc.client;

import firefly520.fireflymc.FireflyMCMod;

/**
 * Mod更新检查器
 * 游戏启动时检查是否有新版本可用
 */
public class UpdateChecker {
    private static boolean checked = false;

    /**
     * 检查更新（在客户端启动时调用）
     */
    public static void checkForUpdate() {
        if (checked) return;
        checked = true;

        // 异步检查更新
        Thread.startVirtualThread(() -> {
            try {
                RulesContent rules = RulesLoader.loadRules();
                if (rules != null && rules.modUpdateUrl() != null && !rules.modUpdateUrl().isEmpty()) {
                    String latestVersion = extractVersionFromUrl(rules.modUpdateUrl());
                    System.out.println("[FireflyMC] Checking for updates: current=" + FireflyMCMod.VERSION + ", latest=" + latestVersion);
                    if (isNewerVersion(latestVersion)) {
                        ClientState.hasUpdateAvailable = true;
                        ClientState.updateVersion = latestVersion;
                        ClientState.updateUrl = rules.modUpdateUrl();
                        System.out.println("[FireflyMC] Update available: " + latestVersion);
                    } else {
                        System.out.println("[FireflyMC] No update available");
                    }
                }
            } catch (Exception e) {
                System.out.println("[FireflyMC] Update check failed: " + e.getMessage());
            }
        });
    }

    /**
     * 从URL中提取版本号
     * 例如: https://mc.firefly520.top/ireflymc-2.2.0.jar -> 2.2.0
     */
    private static String extractVersionFromUrl(String url) {
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < url.length() - 1) {
            String filename = url.substring(lastSlash + 1);
            // 移除 .jar 扩展名和前缀
            String version = filename.replace(".jar", "").replace("fireflymc-", "").replace("ireflymc-", "");
            return version;
        }
        return null;
    }

    /**
     * 比较版本号，判断是否有更新
     * 返回 true 如果 latestVersion 比 currentVersion 新
     */
    private static boolean isNewerVersion(String latestVersion) {
        if (latestVersion == null || latestVersion.isEmpty()) return false;

        try {
            String[] latestParts = latestVersion.split("\\.");
            String[] currentParts = FireflyMCMod.VERSION.split("\\.");

            int length = Math.max(latestParts.length, currentParts.length);
            for (int i = 0; i < length; i++) {
                int latest = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;
                int current = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;

                if (latest > current) return true;
                if (latest < current) return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }

        return false;
    }
}
