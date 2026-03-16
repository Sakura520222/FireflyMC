package firefly520.fireflymc.client;

import java.util.List;

/**
 * 服务器公告内容数据结构
 * 从网络加载的公告内容
 */
public record RulesContent(
    String version,        // 版本号，如 "V2.1"
    String updateDate,     // 更新日期
    String website,        // 官网URL
    String description,    // 说明文字
    List<Section> sections, // 章节列表
    String contact         // 联系方式
) {
    /**
     * 公告章节
     */
    public record Section(
        String title,      // 章节标题
        List<String> lines // 章节内容行
    ) {}
}
