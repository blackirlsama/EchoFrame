"""Check the repository documentation inventory and module-owned numeric policies.

Run with --write to regenerate the catalog after an intentional policy change.
This checks declared defaults/consumer references, not production effective values.
"""
from pathlib import Path
import argparse
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "agent/docs"
JAVA = ROOT / "agent/src/main/java/com/tianmu/agent"
POLICIES = (
    "feedback/FeedbackWarmProperties.java",
    "feedback/FeedbackTieredProperties.java",
    "feedback/FeedbackBucketProperties.java",
    "feedback/FeedbackCompressionProperties.java",
    "memory/MemoryRecallPolicy.java", "memory/MemorySemanticPolicy.java", "memory/CompactionProperties.java", "memory/MemoryRuntimeProperties.java", "memory/MemoryPolicyProperties.java",
    "feedback/FeedbackRuntimeProperties.java", "video/VideoRuntimeProperties.java", "video/content/TranscriptQueryProperties.java", "video/content/BailianAsrProperties.java",
    "video/VideoUnderstandingPolicy.java", "video/VideoReusePolicy.java", "adapters/storage/StorageRuntimeProperties.java", "harness/trace/TraceDeliveryProperties.java",
    "harness/hook/HookPolicy.java",
    "runtime/OperationPolicy.java",
)
CATALOG = DOCS / "配置目录.md"


def catalog():
    lines = ["# 配置目录", "", "状态：当前已实现配置声明；2026-11-07建立。",
             "", "本目录由 `python scripts/check_agent_governance.py --write` 生成。来源为模块的强类型声明，CI检查漂移；运行环境有效值仍需按运维手册单独核验。",
             "", "下列模块数值参数启动绑定，修改后重启生效；没有热更新。前台策略快照由独立开关控制，只冻结新Run的前台执行参数，不冻结本表所有后台/基础设施容量。键中的 millis/seconds/days/hours/bytes/tokens 表示单位，其余为数量。",
             "", "Spring标准优先级适用：命令行参数覆盖系统属性、环境变量和配置文件；profile覆盖基础文件。环境变量按Spring规则去掉属性名中的连字符，再把点换成下划线并大写。例如 `agent.video.runtime.index-workers` 对应 `AGENT_VIDEO_RUNTIME_INDEXWORKERS`。既有YAML显式别名另以YAML为准，不凭目录臆造别名。",
             "", "配置失败应阻止启动；预算、租约/心跳、连接池和重试关系由各record构造器校验。不得用调大容量代替故障定位。安全协议限制不在本表自动变成可调参数。",
             "", "前台Loop/Context/Preparation、现有FeedbackPolicy/VideoCachePolicy等既有配置见[统一技术设计](Agent架构与工程治理技术设计.md)第9、15.5、16节及application*.yml；本表列出新增模块数值声明。",
             "", "## agent.policy-snapshot", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
             "| `agent.policy-snapshot.enabled` | true | PolicySnapshotProperties → ExecutionPolicyFactory；YAML显式环境别名 `AGENT_POLICY_SNAPSHOT_ENABLED`。仅新Run创建时捕获，旧记录永不补造；关闭写者仍兼容读取已保存v1。回滚需使用支持v1的reader。 |", ""]
    lines += ["## 个人记忆与会话压缩", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.memory.enabled` | persistent 为 true；基础为 false | AGENT_MEMORY_ENABLED。启动生效，启用需要 MySQL；账号的读写模式和自动整理独立设置。 |",
              "| `agent.compaction.enabled` | true（仅 MySQL 生效） | AGENT_COMPACTION_ENABLED。启动生效，启用需要 MySQL；后台摘要生成需要记忆模型。 |",
              "| `agent.memory.embedding.enabled` | auto | AGENT_MEMORY_EMBEDDING_ENABLED。个人记忆开启且配置独立 endpoint、token-file、revision 后启动；缺少配置时使用关键词召回。显式 true/false 可覆盖。 |", ""]
    lines += ["## agent.context 投影顺序", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.context.tail-projection-enabled` | true | ContextPolicy → ExecutionPolicyFactory、DeepSeekClient；YAML别名 `AGENT_CONTEXT_TAIL_PROJECTION_ENABLED`。启动绑定。新Run使用v3，关闭后新Run使用v1；已保存快照继续使用其原投影。保留新版快照期间使用兼容二进制回退。 |", ""]
    lines += ["## agent.feedback.compression协议", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.feedback.compression.enabled` | auto | FeedbackCompressionProperties → TaskAssembler在受理时冻结feedback:compression资源标记，ToolDispatcher按任务标记选择三Tool；只影响新任务，旧任务不自动升级。显式别名FEEDBACK_COMPRESSION_ENABLED。同时启用 Embedding 和 Milvus 后自动开启。显式 true 要求两项服务，false 可关闭。 |",
              "| `agent.feedback.compression.min-similarity` | 0.90 | FeedbackCompression任务内星形聚类的余弦下限，finite且(0,1]；FEEDBACK_CLUSTER_MIN_SIMILARITY。进入材料/后台policyHash，重启生效，已封存assignment不重算。 |",
              "| `agent.feedback.compression.min-centrality` | 0.75 | FeedbackCompression代表准入的质心余弦阈值，finite且[-1,1]；FEEDBACK_MIN_CENTRALITY。缺来源代表明确失败，进入材料policyHash。 |",
              "| `agent.feedback.compression.near-duplicate-similarity` | 0.96 | FeedbackCompression可选代表的近重复门槛，finite且[min-similarity,1]；FEEDBACK_NEAR_DUPLICATE_SIMILARITY。来源mandatory优先，进入材料policyHash。 |", ""]
    lines += ["## agent.feedback.rerank", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.feedback.rerank.enabled` | auto | FEEDBACK_RERANK_ENABLED。报告压缩开启且配置精排端点和鉴权后自动开启。仅决定新任务的协议，已有任务继续使用持久协议。显式 true/false 可覆盖。 |",
              "| `agent.feedback.rerank.endpoint` | 空 | FEEDBACK_RERANK_ENDPOINT。兼容 HTTPS /reranks 或 DashScope 原生重排完整地址，启动绑定并进入冻结指纹。关闭准入时保留原端点供在途任务完成。 |",
              "| `agent.feedback.rerank.model` | qwen3-rerank | FEEDBACK_RERANK_MODEL。可选 qwen3.7-text-rerank，启动绑定并进入冻结指纹。 |",
              "| `agent.feedback.rerank.api-key` | 空 | FEEDBACK_RERANK_API_KEY。仅用于供应商请求鉴权。 |",
              "| `agent.feedback.rerank.api-key-file` | 空 | FEEDBACK_RERANK_API_KEY_FILE。配置后优先读取私有文件，最多 8192 字节，内容不进入 Trace、产物或指纹。 |", ""]
    lines += ["## agent.feedback.buckets相似度", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.feedback.buckets.min-similarity` | 0.65 | 最终成员到桶中心的余弦下限；finite且0..1，进入冻结revision，启动生效；需在真实语料校准。YAML别名 `FEEDBACK_BUCKETS_MIN_SIMILARITY`；其他字段别名依次为 `FEEDBACK_BUCKETS_MAX`、`FEEDBACK_BUCKETS_MIN_MEMBERS`、`FEEDBACK_BUCKETS_MAX_ITERATIONS`、`FEEDBACK_BUCKETS_CANDIDATE_SAMPLES`。 |", ""]
    lines += ["## agent.feedback.buckets任务中心化", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.feedback.buckets.centering-enabled` | true | 任务内均值中心化；YAML别名 FEEDBACK_BUCKETS_CENTERING_ENABLED，记录实际模式和回退原因，进入协议 revision。升级已启用代表报告的实例前先排空使用旧策略的在途任务。 |",
              "| `agent.feedback.buckets.center-min-rms` | 0.01 | 向量残差均方根低于此值时全任务回退原始余弦；别名 FEEDBACK_BUCKETS_CENTER_MIN_RMS。 |",
              "| `agent.feedback.buckets.center-min-residual` | 0.001 | 中心化后的单条残差范数下限；低于该值归未归类；别名 FEEDBACK_BUCKETS_CENTER_MIN_RESIDUAL。 |",
              "| `agent.feedback.buckets.center-max-excluded-fraction` | 0.20 | 近均值表达组或其原始成员的比例超过该值时整体回退原始余弦；别名 FEEDBACK_BUCKETS_CENTER_MAX_EXCLUDED_FRACTION。 |",
              "| `agent.feedback.buckets.centered-similarity` | 0.30 | 任务内中心化余弦门槛；别名 FEEDBACK_BUCKETS_CENTERED_SIMILARITY，与原始余弦门槛不能互换。 |", ""]
    lines += ["五桶模型候选数量使用 `agent.feedback.buckets.model-candidates-per-bucket`（默认8，上限8，别名 `FEEDBACK_BUCKETS_MODEL_CANDIDATES_PER_BUCKET`）；报告每桶最多展示3条。旧代表协议仍使用 compression 的每簇参数。", ""]
    lines += ["## agent.models", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.models.protocol` | auto | DeepSeekProperties → DeepSeekClient；枚举 auto/deepseek/siliconflow/none（其他值拒绝启动）。auto 只按官方域名分类（api.deepseek.com→deepseek、api.siliconflow.cn→siliconflow），其余域名一律 none、不发任何一家的思考字段；本机回环替身或镜像域名做排练时必须显式声明，排练报文才与其声明要演练的线上格式一致。启动绑定，进入 analysisProtocolRevision，变更需排空在途Run；把一家协议的字段发给另一家在真实调用上是400，不按域名猜测。 |",
              "| `agent.models.thinking-enabled` | true | DeepSeekProperties → DeepSeekClient；YAML环境别名 `AGENT_MODEL_THINKING_ENABLED`。启动绑定，主循环原生工具及后台反馈分析启用；短输出前置识别、记忆模型仍关闭。仅DeepSeek端点或deepseek模型名发送thinking协议。模式和协议版本进入模型revision，变更需排空在途Run；不是执行策略快照字段。旧Trace不补造思考。 |", ""]
    lines += ["## agent.models思考预算", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.models.thinking-effort` | high | DeepSeekClient主循环思考强度，low/high/max；显式别名 `AGENT_MODEL_THINKING_EFFORT`，启动生效，进入native-thinking-v2模型revision。 |",
              "| `agent.models.analysis-thinking-effort` | low | 后台分析思考强度，low/high/max；显式别名 `AGENT_ANALYSIS_THINKING_EFFORT`。思考保持开启，强度和模式进入两种反馈policyHash，变化前排空job。 |",
              "| `agent.models.analysis-max-tokens` | 16384 | 后台分析单次总输出预算，含思考及最终JSON；8192–32768 Token。显式别名 `AGENT_ANALYSIS_MAX_OUTPUT_TOKENS`。消费DeepSeekFeedbackModels及DeepSeekAudienceModel，预算随实际值预留；job总预算不变，变化进入反馈policyHash。 |", ""]
    lines += ["## agent.video视觉传输", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.video.vision-transport` | auto | VideoModelProperties → VideoModelClient；枚举 auto/vlm_dedicated/openai_chat_vision/generic_chat_vision（其他值拒绝启动）。auto 保持历史映射（minimax→专用 /v1/coding_plan/vlm、compatible→通用聊天视觉），既有配置行为逐字节不变；显式值必须与 provider 身份一致（minimax 配 vlm_dedicated 或 openai_chat_vision，compatible 配 generic_chat_vision），不一致拒绝启动。openai_chat_vision 是本切片唯一可计费图片出口（MiniMax-M3、detail=low、thinking=disabled，费账按其上限预留）；专用 /v1/coding_plan/vlm 与音频转写无已核计费单元，保持拒绝。启动绑定。 |", ""]
    lines += ["## agent.feedback思考调用时限", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.feedback.model-timeout` | 基础30s；deepseek profile 120s | FeedbackPolicy → FeedbackAnalysisService；显式环境别名 `FEEDBACK_MODEL_TIMEOUT`，启动生效，精确范围1–180秒。单次模型调用取此值与job剩余deadline较小者；工作租约为超时加model-lease-margin-seconds，取消、fence、调用与Token预算不变。修改前排空既有任务，不将新策略冒充旧job版本。 |", ""]
    lines += ["## agent.feedback.embedding", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.feedback.embedding.enabled` | auto | AGENT_FEEDBACK_EMBEDDING_ENABLED。提供 endpoint、token-file、revision 后自动开启。显式 true/false 可覆盖。 |",
              "| `agent.feedback.milvus.enabled` | auto | AGENT_FEEDBACK_MILVUS_ENABLED。提供 endpoint、token-file、collection 和正数 dimension 后自动开启。显式 true/false 可覆盖。 |",
              "| `agent.feedback.embedding.model` | qwen3.7-text-embedding | 环境变量 `AGENT_FEEDBACK_EMBEDDING_MODEL`；支持标准版及 flash，启动时绑定。模型与维数进入向量缓存命名空间及反馈策略版本；切换前排空未完成任务，旧模型向量不复用。 |",
              "| `agent.feedback.embedding.dimension` | 2560 | 环境变量 `AGENT_FEEDBACK_EMBEDDING_DIMENSION`；标准版支持 256、512、768、1024、1536、2048、2560，flash 支持至 1024。须与 Milvus 集合维数一致；站点 overlay 以 `FEEDBACK_EMBEDDING_DIMENSION` 同时设置两者，更换维数须事先创建独立集合并重建向量。 |", ""]
    lines += ["## 并行准备与向量来源（2026-11-08）", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.tools.parallel.enabled` | true | ToolParallelPolicy → ToolBatchExecutor；YAML别名 AGENT_TOOLS_PARALLEL_ENABLED。仅已准入的独立读取或宿主受限持久提交可并行，默认每Run 2路，范围1–4。启动生效；关闭回退同批串行，材料后台仍可在提交后推进。切换前排空在途任务。 |",
              "| `agent.feedback.milvus.provenance-enabled` | 按集合自动识别 | AGENT_FEEDBACK_MILVUS_PROVENANCE_ENABLED。首次读写向量时查询集合 schema，含 VarChar provenance 字段即启用。自动识别需要集合 DescribeCollection 权限。true/false 可显式指定。切换带来源字段的集合时，所有写者须统一升级。 |", ""]
    sources = {p: p.read_text(encoding="utf-8") for p in JAVA.rglob("*.java")}
    errors = []
    lines += ["## agent.video.notes.vision", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.video.notes.vision.model` | 空 | NotesVisionProperties → NotesConfiguration，仅笔记素材使用；三项全空时复用前台视觉适配器，设置时三项必须完整。环境变量 AGENT_VIDEO_NOTES_VISION_MODEL。 |",
              "| `agent.video.notes.vision.base-url` | 空 | 仅接受无凭据、query、fragment的HTTPS兼容端点；环境变量 AGENT_VIDEO_NOTES_VISION_BASEURL。 |",
              "| `agent.video.notes.vision.key-file` | 空 | 私有凭据文件，最大4096字节；环境变量 AGENT_VIDEO_NOTES_VISION_KEYFILE。启动读取，密钥不进入日志或文档。 |", "",
              "上述配置重启后影响后续实际视觉调用，前台配置和已保存素材不改写。来源结果摘要、采样时间和完整输入范围仍被校验；实际提供方和模型记入各次调用Trace。原始观察合同保持兼容时允许恢复任务继续处理缺口，不补造历史模型身份；若改变观察合同或采样要求，必须另增analysisVersion并新建计划。没有自动供应商回退或额外重试。", ""]
    for relative in POLICIES:
        path = JAVA / relative
        source = sources[path]
        prefix = re.search(r'@ConfigurationProperties\("([^"]+)"\)', source).group(1)
        fields = re.findall(r'@DefaultValue\("([^"\n]+)"\)\s+(?:int|double)\s+(\w+)', source)
        lines += [f"## {prefix}", "", f"声明与完整校验：[源码](../src/main/java/com/tianmu/agent/{relative})。", "",
                  "| 属性键 | 默认值 | 消费类 |", "|---|---:|---|"]
        if relative == "video/VideoUnderstandingPolicy.java":
            lines.append("| `agent.video.understanding.parallel` | false | VideoUnderstanding：启动生效；关闭为语音辅助视觉串行模式，开启为独立音画并行实验。切换后使用独立缓存策略，不混用两种素材。需真实模型质量和耗时对照后启用；关闭后恢复原模式及其缓存。 |")
        if relative == "video/VideoReusePolicy.java":
            lines.append("| `agent.video.reuse.enabled` | false | VideoAnalysisService、AnalyzeVideoContentTool：启动生效；仅CURRENT初始窗口可复用同主体、同视频版本和模型策略的完整旧片段，并处理缺失尾部；关闭恢复精确区间分析。索引仅进程内，重启丢失后正常重新分析；不共享账号、不扫描数据库JSON。 |")
        for default, name in fields:
            key = prefix + "." + re.sub(r"[A-Z]", lambda m: "-" + m[0].lower(), name)
            consumers = sorted(p.stem for p, text in sources.items() if p != path and
                               ((path.stem in text and re.search(r"\." + name + r"\(\)", text)) or "${" + key + ":" in text))
            if not consumers:
                errors.append(f"No consumer: {key}")
            lines.append(f"| `{key}` | {default} | {'、'.join(consumers) or '缺失'} |")
        lines.append("")
    return "\n".join(lines), errors


def check(write=False):
    content, errors = catalog()
    if write:
        CATALOG.write_text(content, encoding="utf-8", newline="\n")
    elif not CATALOG.exists() or CATALOG.read_text(encoding="utf-8") != content:
        errors.append("Configuration catalog drift; run python scripts/check_agent_governance.py --write")
    index = DOCS / "README.md"
    if not index.exists():
        errors.append("Missing documentation index")
    else:
        indexed = index.read_text(encoding="utf-8")
        for path in DOCS.glob("*.md"):
            if path != index and f"]({path.name})" not in indexed:
                errors.append(f"Unclassified document: {path.name}")
    current_pages = [DOCS / name for name in ("README.md", "配置目录.md", "开发与维护规范.md", "运维与验收手册.md",
                                               "Agent架构与工程治理技术设计.md", "项目进度.md")]
    current_pages.append(DOCS.parent / "README.md")
    for path in current_pages:
        name = path.name
        if not path.exists():
            errors.append(f"Missing current document: {name}")
            continue
        for target in re.findall(r"\]\(([^)]+)\)", path.read_text(encoding="utf-8")):
            if "://" in target or target.startswith("#"):
                continue
            target = target.split("#")[0]
            if not (path.parent / target).exists():
                errors.append(f"Broken local link in {name}: {target}")
    return errors


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    errors = check(parser.parse_args().write)
    for error in errors:
        print(error, file=sys.stderr)
    print(f"Agent governance: {'FAIL' if errors else 'PASS'}")
    sys.exit(bool(errors))
