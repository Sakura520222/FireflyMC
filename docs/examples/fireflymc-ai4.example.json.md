// ============================================================================
// FireflyMC AI 4.0 配置模板 —— 复制到 <服务端 game 目录>/config/fireflymc-ai4.json 后填写
// 仅专用服务端会读取该配置（修改后需重启服务器生效）。
// 注意：实际加载用严格模式 JSON 解析（不支持注释），请把注释留在本模板文件里，
// 复制到 config 时删除全部 // 与 /* */ 注释行。
// 所有字段均可省略并回落到内置默认值（下文以"默认"标注）；数值超出允许范围时
// 服务器启动会拒绝整个配置（INVALID_AI_LIMIT），AI 模块保持 NOT_READY。
// 硬性限制：配置文件不得超过 64KB，且不能是符号链接。
// ============================================================================
{
  // 配置结构版本：当前仅支持 1
  "schemaVersion": 1,
  // AI 模块总开关；false 时整个模块标记为 DISABLED（可省略其余全部配置）
  "enabled": false,

  // ---------------- 模型接入（enabled=true 时 endpoint/apiKey/name/protocol 必填） ----------------
  "model": {
    // 对话协议："chat_completions"（OpenAI 兼容）或 "responses"
    "protocol": "chat_completions",
    // API 基础地址：必须为 https，且不得含用户信息、查询串（?…）或片段（#…）
    "endpoint": "",
    // API 密钥（仅服务端持有，不会同步给客户端，日志中自动脱敏）
    "apiKey": "",
    // 模型名称
    "name": "",
    // 模型是否支持工具调用（function calling）；默认 true
    "supportsTools": true,
    // 是否使用流式输出；默认 true
    "streaming": true,
    // 是否允许模型一次并行发起多个工具调用；默认 false
    "parallelToolCalls": false,
    // 单次模型请求超时（秒）；默认 90，范围 [1, 300]
    "requestTimeoutSeconds": 90,
    // 模型上下文窗口（token）；范围 [4096, 1000000]，默认 32768
    "contextWindow": 32768,
    // 每次回复最大输出 token；范围 [128, 16384]，默认 2048
    "outputTokens": 2048,
    // 压缩上下文时为工具结果保留的空间（token）；范围 [0, 65536]，默认 4096
    "toolReserveTokens": 4096,
    // 压缩上下文时的安全余量（token）；范围 [0, 65536]，默认 1024
    "safetyReserveTokens": 1024,
    // 上下文占用达到该比例（相对 contextWindow）时触发压缩；默认 0.75
    "compactionThreshold": 0.75,
    // 压缩时完整保留的最近对话轮数；范围 [1, 10]，默认 2
    "keepRecentTurns": 2
  },

  // ---------------- Agent 运行限制 ----------------
  "runtime": {
    // 任务调度器最大并发任务数；范围 [1, 8]，默认 2
    "concurrency": 2,
    // 单个任务最大推理步数；范围 [1, 128]，默认 32
    "maxSteps": 32,
    // 单个任务最大工具调用次数；范围 [1, 512]，默认 128
    "maxToolCalls": 128,
    // 单个任务最长运行时间（秒）；范围 [5, 7200]，默认 600
    "durationSeconds": 600,
    // 单个任务累计 token 预算；范围 [1024, 2000000]，默认 250000
    "tokenBudget": 250000,
    // 需要禁用的工具注册名列表（如 "web_search"、"web_open"、"mod_config_summary"）；空数组 = 全部启用
    "disabledTools": []
  },

  // ---------------- 内容公开分享渠道（跨服聊天 / QQ 群） ----------------
  "construction": {
    // 超出玩家原生触及距离的虚拟施工代理权限基线，默认 false；FTB Ranks fireflymc.ai.remote_build 可逐玩家覆盖。
    // 即使允许，仍须本人批准固定蓝图/材料/区域，每次原生放置重查领地；OP 不自动获得此权限。
    "allowVirtualProxy": false
  },

  // ---------------- 内容公开分享渠道（跨服聊天 / QQ 群） ----------------
  "sharing": {
    // 是否允许把 AI 内容分享到跨服聊天渠道；true 时下方两项必填，
    // 且 endpoint 必须是 ws:// 或 wss://（不得含查询串/片段），audience 非空且 ≤512 字
    "crossChatEnabled": false,
    // 分享中继地址（WebSocket）
    "crossChatEndpoint": "",
    // 分享目标受众标识（如 QQ 群号）
    "crossChatAudience": ""
  },

  // ---------------- AI 联网搜索/网页读取（web_search 工具的搜索源） ----------------
  "web": {
    // 搜索提供方："disabled"（禁用搜索）/ "searxng"（自建实例，需配 endpoint）/
    // "brave"（需配 apiKey）/ "public_html"（DuckDuckGo 网页版，无需凭据）
    "provider": "disabled",
    // searxng 实例地址（仅 provider="searxng" 时使用）
    "endpoint": "",
    // Brave Search API Key（仅 provider="brave" 时使用，参与日志脱敏）
    "apiKey": ""
  }

  // ---------------- 可选：mod_config_summary 工具可读取的服务端配置白名单 ----------------
  // 省略时使用默认白名单（仅下列两项）；最多 32 项；source 可为 "fireflymc_api"（服务端 TOML 配置）、
  // "global_file" 或 "world_file"（配合 file/scope 字段读取指定 TOML 文件，不会读取 fireflymc-server.toml）：
  // "publicConfigFields": [
  //   {"source": "fireflymc_api", "key": "server.storyEnabled"},
  //   {"source": "fireflymc_api", "key": "server.enableItemCleanup"}
  // ]
}
