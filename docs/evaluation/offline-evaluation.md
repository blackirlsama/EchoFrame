# 反馈报告离线评测

本轮把反馈报告的离线评测工具整理为可审阅、可复跑的工程差集。它覆盖报告参数前置校验、评测材料冻结、费用账本、付费出口守卫、受控等待补偿、停发语义和可移植入口。所有示例默认只使用本机回环替身和合成数据，不读取真实供应商凭据。

## 能做什么

- 在 `create_feedback_report` 处理器执行前拒绝混填旧 `evidence_id` 与逐来源编号、空白编号、缺少或选错来源；进入现有有限纠错，重复错误安全停止。
- 用规范化行摘要绑定历史账本：旧行被改写（即使总额不变）时 Java 和 Python 两侧都拒绝开账。
- 在评测启动前拒绝未覆盖的付费出口（独立 ASR、embedding、rerank、Milvus）；主控、分析、视觉出口走发送前预算和传输守卫。
- 用同一入口分别启动 A/B；正常成功、参数拒绝、停发/迟发、批准、账本承接和证据根占用都有独立命令或回归。
- Canon 两进程演练需要显式冻结材料、冻结帧、24 行账本和批准收据；它们不从默认 CI 自动读取。

## 快速复跑（合成数据）

在仓库根目录：

```powershell
pwsh ./scripts/run_eval_offline.ps1 -Mode SyntheticPair
```

脚本会先校验输入、批准和契约，再在 `target/evaluation/offline-entry/` 下创建一个全新的空证据根；证据根必须位于当前工作树内，已有同名目录直接拒绝，不删除、不递归清理。A 进程在批准通过后把仓库内合成 12 帧复制到该根并核验逐帧摘要，随后启动 A 和 B。成功时退出 0，并留下：

- `run-summary.json`：两个进程的退出码、报告 SHA/字节、状态。
- `empty-batch.json` / `restart-only-b.json`：开账、A 完成记录、B 只续跑。
- `a-report.html`、`b-report.html`、`a-dispatches.json`、`b-dispatches.json`、`batch-cumulative.json`。
- `completed/controller-a-repeat-1.json` 与 B 记录：状态、PID、账本前缀摘要、报告摘要、基线、契约、材料、帧和来源账本摘要。

其他合成模式：

```powershell
pwsh ./scripts/run_eval_offline.ps1 -Mode ReportArguments   # 混填/空白/选择不符、纠正与停止
pwsh ./scripts/run_eval_offline.ps1 -Mode Ledger            # 770 承接、同额改写拒绝、10,000 分线
pwsh ./scripts/run_eval_offline.ps1 -Mode Fee               # 费用闸门与未覆盖出口拒启
pwsh ./scripts/run_eval_offline.ps1 -Mode Approval          # 缺失/草案/旧基线/多次运行/自动重试
pwsh ./scripts/run_eval_offline.ps1 -Mode Stop              # 等待中断、取消未生效、未取消迟发、无法确认停止
pwsh ./scripts/run_eval_offline.ps1 -Mode AStopPair         # 实际 A 在途取消→确认停发证明→B 接续
pwsh ./scripts/run_eval_offline.ps1 -Mode CanonStop         # Canon 停发语义
```

每个模式都要求新的空证据根；脚本发现目标已存在会直接拒绝，不会覆盖旧证据。也可直接跑 Maven 定向类，例如：

```powershell
mvn -B -ntp -f agent/pom.xml test -Dtest=EvaluationReportArgumentsTest
mvn -B -ntp -f agent/pom.xml test -Dtest=EvaluationSyntheticLedgerTest
```

## Canon 两进程 opt-in

Canon 演练不作为默认 CI 输入。需要显式指定冻结材料配置、24 行账本及其独立可信摘要、帧目录、契约文件和批准收据：

```powershell
pwsh ./scripts/run_eval_offline.ps1 -Mode CanonPair `
  -EvidenceRoot <NEW_EVIDENCE_ROOT> `
  -Ledger <PATH_TO_24_ROW_LEDGER> `
  -LedgerSha256 <TRUSTED_LEDGER_SHA256> `
  -MaterialConfig <PATH_TO_CANON_MATERIAL_CONFIG> `
  -FramesSource <PATH_TO_FROZEN_FRAMES_DIR> `
  -ContractFile <PATH_TO_CONTRACT_JSON> `
  -ApprovalFile <PATH_TO_APPROVAL_RECEIPT>
```

`-FramesSource` 必须包含 `frames-manifest.json` 和 12 张 `frames/frame-*.jpg`；`-Ledger` 必须仍是 24 行、770 分来源；`-MaterialConfig` 的 `materials[].binding` 必须与冻结评论/弹幕/视频摘要一致。缺任意一项、账本摘要不匹配或证据根已占用时拒绝启动。脚本不会自动生成批准收据；没有 `-ApprovalFile` 或 `-ContractFile` 时 Canon 模式直接拒绝。

契约文件从实际输入生成，供人工审阅后再写收据：

```powershell
pwsh ./scripts/run_eval_offline.ps1 -Mode Contract `
  -Ledger <PATH_TO_24_ROW_LEDGER> `
  -LedgerSha256 <TRUSTED_LEDGER_SHA256> `
  -MaterialConfig <PATH_TO_CANON_MATERIAL_CONFIG> `
  -FramesSource <PATH_TO_FROZEN_FRAMES_DIR> `
  -ContractOut <NEW_CONTRACT_JSON>
```

命令打印契约文件的 SHA-256；把该值写入批准收据的 `contract_sha256`。入口会重新读取契约并从实际材料、帧、账本、角色、基线、时间窗和预算档逐项复算，任何一项不一致都拒绝开账。

## 批准收据

入口读取 `approval.json`，字段为：

```json
{
  "state": "APPROVED",
  "approved": true,
  "scope": "canon-730013-pair",
  "baseline": "<40-hex upstream SHA>",
  "contract_sha256": "<declared contract digest>",
  "evidence_root": "<absolute or normalized evidence root>",
  "max_runs": 1,
  "automatic_retry": false,
  "budget_profile": "headroom-770",
  "pair_group": "canon-730013-pair"
}
```

批准收据必须引用契约文件的 SHA-256；只写占位字符串或只传当前 HEAD 不足以授权。`state=DRAFT_NOT_APPROVED`、`approved=false`、旧 baseline、不同 scope/contract/evidence root、`max_runs>1` 或 `automatic_retry=true` 都会被拒绝。没有收据时不写入任何帧、账本或报告。

## 证据与边界

- 脚本先校验证据根、输入、批准和契约，再创建一次空根；已有内容一律拒绝，不删除、不递归清理。帧复制和账本复制由 Java 在批准通过后执行。
- B 在首笔派发前验证 A 的终态、PID、报告字节、账本前缀摘要、A 收口、0 OPEN、基线、契约、材料、帧和来源账本；状态不明、旧记录、重复 B 或批准不匹配都会拒绝。
- 配对入口在等待、断言、报告或对账失败时走同一停发清理；只有终态、全部派发对账、0 OPEN 才卸载费用闸门，取消未生效或无法确认时保持 armed 并保留 reservation。
- 默认 CI 使用仓库合成材料（4 评论 / 6 弹幕）、合成 12 帧、合成账本；不需要外部素材目录、旧账本或真实媒体。
- 所有供应商请求只到 `127.0.0.1` 回环替身；真实模型请求、费用和收据为零。
- `batch-cumulative.json` 是本地模拟账本，不是供应商账单。
- Canon 原件、真实报告、Trace、收据和凭据只保留在本地授权目录，不进入本仓库。
- 报告入口仍要求登录鉴权；本工具不改变产品权限、reservation、事务、Delivery 或纠错次数。

## 审查清单

- 报告参数：正常单源/双源、混填、空白、缺来源、错来源、外轮证据、重复错误停止。
- 账本：770 只承接一次、旧行同额改写拒绝、10,000 分线、A→B 接续、0 OPEN。
- 出口：主控/分析/视觉发送前守卫；未覆盖出口启动前拒绝。
- 证据根：已有标记、空目录、输入/输出重叠、链接绕到旧树、重复运行。
- 接续：B 单次尝试、账本精确 A 收口、A 状态不明、报告字节变化、账本前缀同额改写、身份错配、重复 B、无批准。
- 停发证明：空 `{}`、缺失、错配、篡改、未确认、OPEN；实际 A 失败确认停发后 B 仅一次。
- 停发：等待中断、取消未生效、未取消未过期迟发、无法确认停止、OPEN reservation。
- 批准：缺失、草案、错配、旧基线、多次运行、自动重试、契约材料/帧/账本/角色错配。
- 公开性：示例配置无机器绝对路径，不包含真实媒体/正文/凭据。
