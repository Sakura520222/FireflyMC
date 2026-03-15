package firefly520.fireflymc.client;

import java.util.List;

/**
 * 服务器规则内容数据结构
 * 从网络加载的规则内容
 */
public record RulesContent(
    String version,        // 版本号，如 "V2.1"
    String updateDate,     // 更新日期
    String description,    // 说明文字
    List<Section> sections, // 章节列表
    String contact         // 联系方式
) {
    /**
     * 规则章节
     */
    public record Section(
        String title,      // 章节标题
        List<String> lines // 章节内容行
    ) {}
}
