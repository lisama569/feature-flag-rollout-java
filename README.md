# Feature Flag Rollout（运行期热更新的配置与特性开关）

在 Spring Boot 工程中实现的一套配置中心 + 特性开关能力：多来源配置分层合并与热更新、
按比例灰度放量、变更审计与回滚、来源异常降级；并在此之上提供**发布前试算、批次原子发布、
值班运行总览**。全部保证并发访问下的一致性与可解释性。

## 1. 配置分层与合并规则

配置按来源分层，每个来源有优先级数值（越大越优先）：

| 来源 | 优先级 | 说明 |
|------|--------|------|
| `defaults` | 0 | 内置默认值，保证任何 key 都有兜底 |
| `dynamic` | 100 | 运行期动态来源（可热更新），覆盖低优先级同名 key |

合并规则（`ConfigService.refresh()`）：

1. 按优先级从低到高逐层合并，**高优先级来源的同名 key 覆盖低优先级**；
2. 每次合并产出一个**不可变快照**（`ConfigSnapshot`），通过原子引用整体切换，
   读取方只会看到完整的旧快照或完整的新快照，**不会观察到半新半旧的中间状态**；
3. **非法项被拒绝，绝不静默覆盖已生效值**：每个来源的配置先经 `ConfigValidator` 校验，
   非法项被剔除并记录可区分的原因（`ViolationCode`）：
   - `EMPTY_KEY`：键为空白
   - `NULL_VALUE`：值为 null
   - `NOT_AN_INTEGER`：`*.percentage` 不是整数
   - `OUT_OF_RANGE`：`*.percentage` 不在 [0,100]
   - `NOT_A_BOOLEAN`：`*.enabled` 不是 true/false
4. 高优先级来源缺失或某项非法时，低优先级/当前生效值继续生效。

## 2. 灰度分流语义

配置约定：

- `feature.<name>.enabled`：总开关（缺省 false）
- `feature.<name>.percentage`：灰度比例 0–100（缺省 0）

判定规则（`FeatureFlagService.evaluate`）：

```
bucket = SHA-256(flagKey + ":" + subjectId) mod 100      // 稳定分桶
enabled = masterSwitch && bucket < percentage
```

- **稳定性**：桶号只依赖 flag 名与主体 ID，与配置版本无关。配置版本不变时，
  同一主体永远命中同一结果；比例从 20% 提到 40% 时，只有桶号落在 [20,40) 的主体迁移，
  已命中主体不会掉出。
- **一致性**：每次判定先一次性取出当前快照（原子引用），再基于该快照完成全部计算，
  配置切换过程中不会出现新旧分支混用。
- **可解释性**：判定返回 `Decision`，包含输入（flag、subject）、判定依据
  （配置版本、开关值、比例、桶号、文字说明）与最终结果，并输出结构化日志。

## 3. 发布前试算（Preflight，只读）与正式发布的边界

提交一组拟变更内容时，可先调用 `POST /api/releases/preflight` 试算。试算基于
**当前生效版本**给出：

- 合并后的**预计完整结果**（`projectedEntries`、`projectedVersion`）；
- 逐项的当前值 vs 预计值对比（`itemDiffs`：`ADDED/MODIFIED/UNCHANGED/REJECTED`），
  以及**每项被拒绝的具体原因**（`rejectionCode`/`rejectionMessage`）；
- **会受影响的开关及灰度变化**（`impactedFlags`：前后开关状态、比例与 `percentageDelta`）。

**试算与正式发布的边界（务必区分）：**

1. 试算是**只读**操作：不改动当前生效配置、不写可回滚历史、**不登记 changeId**，
   因此**不占用真实发布的幂等位置**——试算用过的 `changeId` 仍可用于真实发布。
2. 试算**不写审计**（它不是一次变更动作），但服务端会打印一条 `[preflight]` 结构化日志。
3. 试算 HTTP 始终返回 200，结论必须看响应体的 `status` / `wouldApply`，
   **不能把 `projected*` 预计字段当作已生效值**：
   - `APPLYABLE`：若立即发布，整批会生效（`wouldApply=true`）；
   - `STALE_BASE_VERSION`：**基准版本已过期**（`baseVersion ≠ 当前版本`），按此提交必被拒绝；
   - `DUPLICATE_CHANGE_ID`：该标识**此前已真实发布过**，再次出现按重复提交拒绝；
   - `INVALID_ENTRIES`：拟变更内容含非法项，逐项原因见 `itemDiffs`；
   - `EMPTY_BATCH`：没有提交任何配置项。
4. “过期”和“重复”是两种**可明确区分**的状态：即使基准版本恰好正确，重复的 `changeId`
   仍判定为 `DUPLICATE_CHANGE_ID`，不会被笼统报成过期。正式发布的拒绝返回 409 + `reason`。

## 4. 批次原子发布

一次可提交多个开关及其配置项（`BatchChangeRequest`：`changeId/source/operator/baseVersion/entries`）。

- **原子性**：整批在同一把写锁内完成“校验 → 合并 → 单次原子切换”，
  **要么整批生效，要么整批保持原样**。任一必要项非法、基准过期或重复提交时，
  **连批次中合法的部分也不会先生效**。
- **读一致性**：判定方在发布过程中只会看到完整的旧快照或完整的新快照，不会半新半旧。
- **单一版本**：一次成功的批次发布只形成**一个**可回滚版本（版本号 +1）。
- **审计完整**：审计记录包含整批 `changeId`、来源 `source`、操作者 `operator`、
  前后版本，以及 `batchSize/keys/entries` 明细。
- **整批回滚**：`rollback(toVersion)` 恢复的是目标版本的**整份内容**（该批次的所有开关一起），
  不会只回一部分；回滚本身版本号继续递增并记一条 `ROLLED_BACK`。
- **幂等 / 过期**：整批共享一个 `changeId`，重复出现整批拒绝（`DUPLICATE_CHANGE_ID`）；
  `baseVersion` 与当前版本不一致则整批拒绝（`STALE_BASE_VERSION`）。
- 并发的试算、发布、回滚、灰度判定混跑时，上述语义仍成立（见 `BatchConcurrencyTest`）。

## 5. 值班运行总览与诊断

`GET /api/operations/overview` 一次返回：

- 当前生效版本 `currentVersion`；
- 各来源健康/降级状态 `sources`（`OK/DEGRADED/NO_SNAPSHOT/NEVER_LOADED`、最近尝试时间、
  **最近一次可用时间**、最近可用快照条目数、最近错误）；
- 最近发布**成功 / 拒绝 / 回滚**摘要 `recentActivity`；
- **当前打开的开关**（`enabled=true`）及灰度比例 `openFlags`（`CANARY` 灰度中 / `FULL` 全量）。

**容量上限与筛选（不无界吃内存）：**

- 审计日志使用定长环形缓冲，默认保留最近 **1000** 条（`AuditLog.DEFAULT_CAPACITY`），
  更老记录被挤出并通过 `droppedTotal` 计数；历史可回滚版本默认保留最近 **1000** 个
  （`ConfigService.DEFAULT_HISTORY_LIMIT`），超出自动淘汰最旧版本。
- 总览活动默认返回最近 **20** 条（`limit` 可调），响应中带
  `activityReturned/activityTotalMatched/activityLimit/activityTruncated`，**截断有明确提示**。
- 支持筛选：
  - 按结果：`outcomes=APPLIED,REJECTED,ROLLED_BACK`（逗号分隔，可任意组合）；
  - 按时间：`since=<毫秒>`、`until=<毫秒>`（含边界）。
- 审计的独立查询接口 `GET /api/config/audit/query?outcomes=...&since=...&until=...&limit=...`
  返回 `AuditPage`（`records/returned/totalMatched/limit/truncated/droppedTotal/oldestSeqKept`）。

**降级状态解释：** 某个来源不可用时，**总览本身依然可用**（它不依赖该来源才能返回），
并显式置 `degraded=true`、给出 `statusBanner` 明确提示“当前为降级状态、正使用该来源
最近一次可用快照或跳过，展示值可能不是最新”。对应来源在 `sources` 中标为
`DEGRADED`（有历史快照可用）或 `NO_SNAPSHOT`（从未成功、合并时跳过）。

## 6. 变更审计、回滚与降级（既有能力）

### 变更提交（乐观并发 + 幂等）

`submitChange(ChangeRequest)` / 批次 `submitBatch(...)` 携带 `changeId`（幂等键）
与 `baseVersion`（基准版本）：

| 情况 | 结果 | 拒绝原因 |
|------|------|----------|
| `baseVersion` ≠ 当前版本 | 拒绝，当前值不变 | `STALE_BASE_VERSION` |
| `changeId` 已应用过 | 拒绝，当前值不变 | `DUPLICATE_CHANGE_ID` |
| 含非法配置项（批次为任一项非法） | 拒绝，当前值不变（整批不生效） | `INVALID_ENTRIES`（附每项 `ViolationCode`） |

所有应用与拒绝都会写入有界 `AuditLog`（来源、操作者、from/to 版本、动作、明细、时间戳）。
**注意：试算不写审计。**

### 回滚

`rollback(toVersion, operator)` 把配置内容恢复为历史版本 `toVersion` 的**整份内容**，
但**版本号继续递增**（回滚本身也是一次可审计变更，记录 `ROLLED_BACK`）。
目标版本不存在或已超出历史保留上限时抛出 `IllegalArgumentException`。

### 来源异常降级

`refresh()` 拉取某来源失败（`ConfigSourceUnavailableException`）时，
**降级到该来源最近一次成功加载的快照**继续合并，而不是整体失败；
该来源从未成功过则跳过并记录警告。降级状态会体现在总览的 `sources` 与 `statusBanner` 中。

## 7. REST 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/config` | 当前生效快照 |
| POST | `/api/config/refresh` | 触发全量热更新 |
| POST | `/api/config/changes` | **（旧）**单项变更提交；拒绝时 409 + 原因 |
| POST | `/api/config/rollback/{version}?operator=xx` | 回滚到指定版本（整份恢复） |
| GET | `/api/config/audit` | **（旧）**缓冲内全部审计记录（正序） |
| GET | `/api/config/audit/query` | 有界、可按结果/时间筛选的审计查询（倒序，带截断提示） |
| GET | `/api/flags/evaluate?flag=pay&subject=u1` | 开关判定（返回可解释 Decision） |
| POST | `/api/releases/preflight` | **（新）**发布前只读试算（HTTP 200，结论看 `status`） |
| POST | `/api/releases/batch` | **（新）**批次原子发布；拒绝时 409 + 原因 |
| GET | `/api/operations/overview` | **（新）**值班运行总览（`outcomes/since/until/limit`） |

## 8. 对旧调用方的兼容范围

- 旧的 `ChangeRequest` 与 `POST /api/config/changes`、`GET /api/config/audit`
  完全保留；旧单条提交在内部被适配为“单条目批次”，行为与以前一致。
- 旧审计调用方若自行构造/读取 8 段式 `AuditRecord`，其构造器仍保留；新增的 `operator`
  为第 9 段，旧记录该字段为 `null`。
- `GET /api/config`、`POST /api/config/refresh`、`rollback`、`evaluate` 的请求/响应形状不变。
- 变化点（不影响旧语义）：审计从无界列表改为默认 1000 条环形缓冲、历史版本默认保留 1000 个；
  批次发布的审计明细多了 `batchSize/entries` 等字段。编译开启 `-parameters`，
  record 的 JSON 字段名保持稳定可读。

## 9. 本地验证

```bash
# 运行全部测试（含试算不改变状态、批次全量生效/回退、并发不半新半旧、
# 重复与过期可区分、降级下总览可用，以及一条真正启动服务的端到端 HTTP 链路）
mvn test

# 启动服务（首次启动约需 10+ 秒）
mvn spring-boot:run
```

curl 复现（建议加 `-H 'Accept: application/json'`）：

```bash
# 0. 查看当前版本（默认 new-checkout=false / 0），记下 version
curl -s -H 'Accept: application/json' localhost:8080/api/config

# 1. 灰度判定（关闭状态，返回输入/依据/结果）
curl -s -H 'Accept: application/json' \
  'localhost:8080/api/flags/evaluate?flag=new-checkout&subject=user-42'

# 2. 发布前试算：预计打开并灰度 50%（只读，不会真的生效，不占用 changeId）
curl -s -X POST localhost:8080/api/releases/preflight \
  -H 'Content-Type: application/json' -d '{
    "changeId": "demo-pf", "source": "curl", "operator": "alice", "baseVersion": 1,
    "entries": {"feature.new-checkout.enabled": "true",
                "feature.new-checkout.percentage": "50"}}'
# -> "status":"APPLYABLE","wouldApply":true，并给出 itemDiffs / impactedFlags

# 2b. 试算不改变状态：再查版本仍是 1
curl -s -H 'Accept: application/json' localhost:8080/api/config

# 3. 批次原子发布：整批一次生效，形成一个新版本（把 baseVersion 换成当前版本）
curl -s -X POST localhost:8080/api/releases/batch \
  -H 'Content-Type: application/json' -d '{
    "changeId": "demo-batch-1", "source": "curl", "operator": "alice", "baseVersion": 1,
    "entries": {"feature.new-checkout.enabled": "true",
                "feature.new-checkout.percentage": "50"}}'

# 4. 重复标识 vs 过期基准（两者原因不同）
curl -s -X POST localhost:8080/api/releases/batch -H 'Content-Type: application/json' \
  -d '{"changeId":"demo-batch-1","source":"curl","baseVersion":2,"entries":{}}'   # 409 DUPLICATE_CHANGE_ID
curl -s -X POST localhost:8080/api/releases/batch -H 'Content-Type: application/json' \
  -d '{"changeId":"demo-stale","source":"curl","baseVersion":1,"entries":{"feature.new-checkout.percentage":"90"}}'
#   409 STALE_BASE_VERSION

# 5. 审计：可筛选 + 截断提示
curl -s -H 'Accept: application/json' \
  'localhost:8080/api/config/audit/query?outcomes=APPLIED&limit=5'

# 6. 值班总览：当前版本/来源降级/最近活动/打开开关与灰度比例
curl -s -H 'Accept: application/json' 'localhost:8080/api/operations/overview?limit=10'
curl -s -H 'Accept: application/json' \
  'localhost:8080/api/operations/overview?outcomes=REJECTED,ROLLED_BACK&limit=5'

# 7. 回滚到发布前版本（整批内容恢复：开关重新关闭、比例 0）
curl -s -X POST 'localhost:8080/api/config/rollback/1?operator=oncall'

# 8. 旧调用方入口继续可用
curl -s -X POST localhost:8080/api/config/changes -H 'Content-Type: application/json' -d '{
  "changeId": "demo-legacy", "source": "curl", "baseVersion": 3,
  "entries": {"feature.new-checkout.percentage": "5"}}'
```

## 10. 测试覆盖

位于 `src/test/java/.../tests/`：

- `ConfigServiceTest`：优先级合并、非法项拒绝（原因可区分）、过期/重复变更拒绝、
  来源不可用降级、回滚与审计（**既有，未回归**）；
- `FeatureFlagServiceTest`：总开关、0%/100% 放量、同主体稳定性、命中比例统计、
  可解释判定、放量提升只迁移边界主体（**既有，未回归**）；
- `ConcurrencyTest`：8 读线程 × 2000 次判定叠加 200 次切换逐条对照版本快照重算、
  16 线程同 changeId 仅生效一次、并发 refresh 与快照自洽（**既有，未回归**）；
- `ReleaseServiceTest`：**试算不改生效配置/历史/幂等位置**、预计差异与灰度影响、
  过期与重复原因可区分、非法批次整批不生效、批次整批一个版本、回滚整批恢复；
- `BatchConcurrencyTest`：发布/回滚/试算/判定混合并发下不半新半旧、
  同一批次整批最多生效一次、判定始终与其声称版本快照一致；
- `AuditLogTest`：环形缓冲容量上限与丢弃计数、limit 截断、按动作/时间窗筛选；
- `OperationsServiceTest`：打开开关与灰度展示、按结果筛选、容量上限与截断提示、
  来源降级时总览仍可用且明确标注；
- `FullStackHttpTest`：**真正以随机端口启动服务**，从外部 HTTP 串起灰度判定 →
  热更新 → 试算（不改状态）→ 批次发布 → 重复/过期区分 → 审计/审计查询 → 回滚 →
  值班总览 → 旧入口兼容，并直接读取解析 JSON；服务端日志继续打印输入、判定依据与最终结果。
