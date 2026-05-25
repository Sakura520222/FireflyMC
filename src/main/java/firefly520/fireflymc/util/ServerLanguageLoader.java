package firefly520.fireflymc.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务端语言加载工具类
 * 适配 NeoForge 1.21.1，通过 ModList 直接访问 Mod JAR 包中的语言文件
 */
public class ServerLanguageLoader {
    public static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new Gson();
    private static final int MAX_COMPONENT_DEPTH = 32;
    private static final Pattern TRANSLATION_FORMAT_PATTERN = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");
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

                for (IModInfo modInfo : fileInfo.getMods()) {
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
     * 将聊天组件按已加载的中文语言表解析为纯文本。
     * @param component 需要解析的组件
     * @return 解析后的纯文本
     */
    public static String translateComponent(Component component) {
        if (component == null) {
            return null;
        }
        if (!isLoaded) {
            loadZhCnLanguage();
        }
        try {
            return resolveComponent(component, 0);
        } catch (Exception e) {
            LOGGER.warn("[FireflyMC] 解析翻译组件失败，使用原始文本", e);
            return component.getString();
        }
    }

    private static String resolveComponent(Component component, int depth) {
        if (depth > MAX_COMPONENT_DEPTH) {
            String collapsed = component.tryCollapseToString();
            return collapsed == null ? "" : collapsed;
        }

        StringBuilder builder = new StringBuilder(resolveContents(component, depth));
        for (Component sibling : component.getSiblings()) {
            builder.append(resolveComponent(sibling, depth + 1));
        }
        return builder.toString();
    }

    private static String resolveContents(Component component, int depth) {
        if (component.getContents() instanceof PlainTextContents plainTextContents) {
            return plainTextContents.text();
        }
        if (component.getContents() instanceof TranslatableContents translatableContents) {
            String template = getTranslation(translatableContents.getKey());
            if (template.equals(translatableContents.getKey()) && translatableContents.getFallback() != null) {
                template = translatableContents.getFallback();
            }
            return formatTranslationTemplate(template, translatableContents.getArgs(), depth);
        }
        return component.plainCopy().getString();
    }

    private static String formatTranslationTemplate(String template, Object[] args, int depth) {
        if (template == null || template.isEmpty()) {
            return template;
        }

        StringBuilder result = new StringBuilder();
        Matcher matcher = TRANSLATION_FORMAT_PATTERN.matcher(template);
        int implicitArgIndex = 0;
        int lastEnd = 0;

        while (matcher.find()) {
            result.append(template, lastEnd, matcher.start());
            String placeholder = matcher.group();
            String type = matcher.group(2);

            if ("%".equals(type) && "%%".equals(placeholder)) {
                result.append('%');
            } else if ("s".equals(type)) {
                String explicitIndex = matcher.group(1);
                int argIndex = explicitIndex == null ? implicitArgIndex++ : Integer.parseInt(explicitIndex) - 1;
                result.append(resolveTranslationArgument(args, argIndex, placeholder, depth + 1));
            } else {
                result.append(placeholder);
            }

            lastEnd = matcher.end();
        }

        result.append(template.substring(lastEnd));
        return result.toString();
    }

    private static String resolveTranslationArgument(Object[] args, int index, String fallback, int depth) {
        if (args == null || index < 0 || index >= args.length) {
            return fallback;
        }

        Object arg = args[index];
        if (arg instanceof Component component) {
            return resolveComponent(component, depth);
        }
        return arg == null ? "null" : String.valueOf(arg);
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
