package firefly520.fireflymc.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.locating.IModFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 服务端语言加载工具类
 * 适配 NeoForge 1.21.1，通过 ModList 直接访问 Mod JAR 包中的语言文件
 */
public class ServerLanguageLoader {
    public static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new Gson();
    private static final Map<String, String> TRANSLATION_MAP = new HashMap<>();
    private static boolean isLoaded = false;

    /**
     * 加载中文语言文件
     */
    public static void loadZhCnLanguage() {
        if (isLoaded) {
            return;
        }

        TRANSLATION_MAP.clear();

        try {
            // 1. 首先从 classpath 加载模组内嵌的 Minecraft 原版翻译
            loadFromClasspath("/assets/minecraft/lang/zh_cn.json");

            // 2. 遍历所有已加载的 Mod，加载各自的翻译
            for (IModFileInfo fileInfo : ModList.get().getModFiles()) {
                IModFile modFile = fileInfo.getFile();

                // 获取该文件中的第一个 Mod 的 ID
                IModInfo modInfo = fileInfo.getMods().get(0);
                String modId = modInfo.getModId();

                // 查找该 Mod 的语言文件路径
                Path langPath = modFile.findResource("assets", modId, "lang", "zh_cn.json");

                if (Files.exists(langPath)) {
                    try {
                        String content = Files.readString(langPath, StandardCharsets.UTF_8);
                        JsonObject jsonObject = GSON.fromJson(content, JsonObject.class);

                        int count = 0;
                        for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
                            if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
                                TRANSLATION_MAP.put(entry.getKey(), entry.getValue().getAsString());
                                count++;
                            }
                        }

                        LOGGER.info("[FireflyMC] 成功加载 Mod [{}] 的中文语言文件，共 {} 个翻译键", modId, count);
                    } catch (Exception e) {
                        LOGGER.error("[FireflyMC] 加载 Mod [{}] 的中文语言文件失败", modId, e);
                    }
                }
            }

            isLoaded = true;
            LOGGER.info("[FireflyMC] 服务端中文语言文件加载完成，共加载 {} 个翻译键", TRANSLATION_MAP.size());
        } catch (Exception e) {
            LOGGER.error("[FireflyMC] 服务端中文语言文件加载失败", e);
        }
    }

    /**
     * 从 classpath 加载语言文件
     * @param resourcePath 资源路径（如 /assets/minecraft/lang/zh_cn.json）
     */
    private static void loadFromClasspath(String resourcePath) {
        try (InputStream is = ServerLanguageLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOGGER.warn("[FireflyMC] 未找到内嵌语言文件: {}", resourcePath);
                return;
            }

            JsonObject jsonObject = GSON.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), JsonObject.class);

            int count = 0;
            for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
                    TRANSLATION_MAP.put(entry.getKey(), entry.getValue().getAsString());
                    count++;
                }
            }

            LOGGER.info("[FireflyMC] 成功加载内嵌中文语言文件 {}，共 {} 个翻译键", resourcePath, count);
        } catch (IOException e) {
            LOGGER.error("[FireflyMC] 加载内嵌语言文件失败: {}", resourcePath, e);
        }
    }

    /**
     * 获取翻译键对应的中文文本
     * @param translationKey 翻译键（如 advancements.story.mine_stone.title）
     * @return 中文文本，无对应翻译时返回翻译键本身
     */
    public static String getTranslation(String translationKey) {
        return TRANSLATION_MAP.getOrDefault(translationKey, translationKey);
    }

    /**
     * 清除加载的语言数据，服务端关闭时调用
     */
    public static void clear() {
        TRANSLATION_MAP.clear();
        isLoaded = false;
    }

    public static boolean isLoaded() {
        return isLoaded;
    }
}
