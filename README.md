# Feature Flag Rollout（运行期热更新的配置与特性开关）

在 Spring Boot 工程中实现的一套配置中心 + 特性开关能力：多来源配置分层合并与热更新、
按比例灰度放量、变更审计与回滚、来源异常降级，全部保证并发访问下的一致性与可解释性。

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

## 3. 变更审计、回滚与降级

### 变更提交（乐观并发 + 幂等）

`submitChange(ChangeRequest)` 携带 `changeId`（幂等键）与 `baseVersion`（基于的配置版本）：

| 情况 | 结果 | 拒绝原因 |
|------|------|----------|
| `baseVersion` ≠ 当前版本 | 拒绝，当前值不变 | `STALE_BASE_VERSION` |
| `changeId` 已应用过 | 拒绝，当前值不变 | `DUPLICATE_CHANGE_ID` |
| 含非法配置项 | 拒绝，当前值不变 | `INVALID_ENTRIES`（附每项 `ViolationCode`） |

所有应用与拒绝都会写入 `AuditLog`（来源、from/to 版本、动作、明细、时间戳）。

### 回滚

`rollback(toVersion, operator)` 把配置内容恢复为历史版本 `toVersion` 的内容，
但**版本号继续递增**（回滚本身也是一次可审计的变更，记录 `ROLLED_BACK`）。
目标版本不存在时抛出 `IllegalArgumentException`。

### 来源异常降级

`refresh()` 拉取某来源失败（`ConfigSourceUnavailableException`）时，
**降级到该来源最近一次成功加载的快照**继续合并，而不是整体失败；
该来源从未成功过则跳过该来源并记录警告日志。每次成功/失败都会更新该来源的健康状态
（最近成功时间、最近失败时间与原因、最近可用快照条目数），供值班总览展示。

> 注意既有语义：`refresh()` 是“从各来源重新合并”，运行期通过变更接口提交的内容
> 若不属于任何来源，在一次 `refresh()` 后会被来源合并结果取代；来源降级时取代它的是
> 该来源**最近一次成功快照**。这是本轮未改动的既有行为。

## 4. 发布前试算（dry-run，不改变任何状态）

`POST /api/config/changes/preview`，请求体与正式变更完全相同（`ChangeRequest`）。

它基于**当前生效版本**给出“假如现在正式提交会怎样”的预计结果（`PreviewResult`）：

- `wouldApply`：若正式提交是否会被接受；`proposedVersion`：预计发布后的版本号；
- `rejectionReason` + `message`：被拒绝时的**可区分原因**与面向人的明确说明
  （`STALE_BASE_VERSION` / `DUPLICATE_CHANGE_ID` / `INVALID_ENTRIES`）；
- `errors`：每个非法配置项及其 `ViolationCode`；
- `diffs`：逐项 `currentValue` vs `proposedValue`，变化类型
  `ADDED` / `UPDATED` / `UNCHANGED`；
- `affectedFlags`：受影响开关的总开关与灰度比例前后值及变化类型。

**试算与正式发布的边界（强保证）：**

1. 试算**只读**当前快照，绝不修改生效配置、绝不产生可回滚历史版本；
2. 试算**不写审计**、**不占用真实发布的幂等位置**：同一个 `changeId` 可以反复试算，
   试算之后仍可用于真实发布；只有真实发布（单条或批次）才会登记幂等标识；
3. 基准版本已过期、内容不合法、或该标识此前已真实发布过时，试算返回
   `wouldApply=false`、`proposedVersion=null` 并给出对应原因，**不会让人误以为已生效**。
   判定顺序与正式提交一致：重复标识优先于过期基准。
4. 试算接口永远返回 HTTP 200（因为它本身是成功的“查询”），预计结果是否可接受请看
   `wouldApply`；正式提交被拒绝才返回 409。

## 5. 批次原子发布

`POST /api/config/batch`，请求体 `BatchPublishRequest`：

```json
{
  "batchId": "release-2026-09-29-1",
  "source": "release-pipeline",
  "operator": "alice",
  "baseVersion": 12,
  "items": [
    {"flag": "checkout", "entries": {"feature.checkout.enabled": "true", "feature.checkout.percentage": "80"}},
    {"flag": "checkout-v2", "entries": {"feature.checkout-v2.enabled": "true", "feature.checkout-v2.percentage": "25"}}
  ]
}
```

**原子性与一致性：**

- 多个开关及其配置项作为一个整体：**要么整批生效，要么整批保持原样**。
  任一必要项非法、基准版本过期、批次标识重复、或批次为空时整批拒绝，
  不会出现部分配置先生效；
- 判定方在发布过程中只能看到**完整的旧快照或完整的新快照**
  （生效配置仍通过 `AtomicReference` 整体切换，所有写操作由同一把写锁串行化）。
  并发的试算、发布、回滚、灰度判定混在一起也保持该语义；
- 一次成功的批次发布**只形成一个可回滚版本**；
- 审计中一条 `APPLIED` 记录即代表整批：含整批键 `affectedKeys`、来源 `source`、
  操作者 `operator`、前后版本；
- `rollback` 到批次前/批次所在版本时，恢复的都是**整批内容**，不会只回一部分
  （回滚版本号继续递增，记 `ROLLED_BACK`，操作者入审计）。

**幂等 / 过期规则：**

- `batchId` 与单条变更的 `changeId` **共用同一幂等空间**：任一真实发布（单条或批次）
  用过的标识都不能再用；同一批次并发提交时只有一个整批生效，其余得到
  `DUPLICATE_CHANGE_ID` 或 `STALE_BASE_VERSION`；
- `baseVersion` 必须等于提交时的当前版本，否则 `STALE_BASE_VERSION`；
- 空批次返回 `EMPTY_BATCH`；批次项缺 `flag`/`entries` 或配置项非法返回
  `INVALID_ENTRIES`（携带每项 `ViolationCode`）。拒绝均返回 HTTP 409 且不改变现状。

## 6. 值班运行总览与诊断

- `GET /api/ops/overview`：当前生效版本、各来源是否降级与最近一次可用情况、
  最近的发布成功/拒绝/回滚摘要、当前打开（总开关为 true）的开关及其灰度比例；
  含 `generatedAtMillis`。
- `GET /api/ops/audit?action=APPLIED&from=<毫秒>&to=<毫秒>&limit=50`：有界、可筛选审计。

**容量上限、截断与降级提示：**

- 审计日志默认只保留最近 **500** 条（`AuditLog.DEFAULT_CAPACITY`），超出淘汰最老记录并
  累计 `evicted`，因此不会随历史无限增长吃内存；
- 单次查询默认返回最近 **50** 条、上限 **200**（`limit` 自动夹到 [1,200]），
  默认按时间**倒序**；返回体含 `limit`、`totalMatched`、`truncated`、
  `retainedCapacity`、`evicted`；
- 总览的“近期动作”固定最多 **20** 条（`recentLimit`），超额时
  `recentTruncated=true` 并给出 `recentTotalAvailable`；
- 支持按结果（`APPLIED` / `REJECTED` / `ROLLED_BACK`）与时间区间（`from`/`to` 毫秒，
  闭区间）筛选；
- 某个配置来源不可用时，**总览本身仍可正常返回**，并通过顶层 `degraded=true` 与
  `sources[].degraded=true`、最近失败时间/原因明确告知当前处于降级状态。

## 7. 对旧调用方的兼容范围

- 既有接口与语义全部保留：`GET /api/config`、`POST /api/config/refresh`、
  `POST /api/config/changes`、`POST /api/config/rollback/{version}`、
  `GET /api/config/audit`、`GET /api/flags/evaluate` 的路径、请求/判定语义不变；
- `AuditRecord` 新增了 `operator`、`affectedKeys` 字段：旧构造路径下
  `operator` 取 `source`、`affectedKeys` 为空列表，老代码无需改动；
- `GET /api/config/audit` 仍返回当前保留的全部审计记录（正序）；
  新的有界/筛选能力通过新的 `/api/ops/audit` 提供；
- `RejectionReason` 为新增枚举值 `EMPTY_BATCH`（仅批次空时出现），原有三个原因与
  409 行为不变；
- 顺带修复了 `ConfigSnapshot` 经 HTTP 序列化的问题（此前手写类的 `version()` 等访问器
  不被 Jackson 识别，`GET /api/config`、`refresh`、`rollback` 在无 record 序列化条件时
  会返回 406），现已通过 JSON 注解正常返回，方法签名与运行期行为不变。

## 8. REST 接口

既有（兼容保留）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/config` | 当前生效快照 |
| POST | `/api/config/refresh` | 触发全量热更新 |
| POST | `/api/config/changes` | 提交单条变更（拒绝时 409 + 可区分原因） |
| POST | `/api/config/rollback/{version}?operator=xx` | 回滚到指定版本 |
| GET | `/api/config/audit` | 当前保留的全部审计记录（正序） |
| GET | `/api/flags/evaluate?flag=pay&subject=u1` | 开关判定（返回可解释 Decision） |

本轮新增：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/config/changes/preview` | 发布前试算（恒 200；是否可接受看 `wouldApply`，不改状态） |
| POST | `/api/config/batch` | 批次原子发布（整批生效/整批拒绝，失败 409 + 原因） |
| GET | `/api/ops/overview` | 值班总览：版本、来源降级、近期动作、打开开关 |
| GET | `/api/ops/audit?action=&from=&to=&limit=` | 有界、可筛选的审计查询（倒序、含截断提示） |

## 9. 本地验证

```bash
# 运行全部测试（含并发、批次原子性、来源降级、真实启动服务的全链路测试）
mvn test

# 启动服务
mvn spring-boot:run

# 1) 发布前试算：基于当前版本给出预计结果/差异/受影响开关，但当前配置不变
curl -s -X POST localhost:8080/api/config/changes/preview \
  -H 'Content-Type: application/json' -d '{
    "changeId": "demo-pre", "source": "curl", "baseVersion": 1,
    "entries": {"feature.new-checkout.enabled": "true", "feature.new-checkout.percentage": "50"}}'
# 关注 wouldApply=true、proposedVersion=2、diffs/affectedFlags；
# 再 GET /api/config 会发现版本仍是 1（试算未生效、未占幂等）。

# 2) 批次原子发布：两个开关一起发布，只产生一个版本
curl -s -X POST localhost:8080/api/config/batch \
  -H 'Content-Type: application/json' -d '{
    "batchId": "demo-batch-1", "source": "release-pipeline", "operator": "alice",
    "baseVersion": 1,
    "items": [
      {"flag": "new-checkout", "entries": {"feature.new-checkout.enabled": "true", "feature.new-checkout.percentage": "80"}},
      {"flag": "checkout-v2",   "entries": {"feature.checkout-v2.enabled": "true", "feature.checkout-v2.percentage": "25"}}]}'
# 重复提交同一 batchId（即使 baseVersion 更新）-> 409 DUPLICATE_CHANGE_ID

# 3) 灰度判定（输入、依据、结果）
curl -s 'localhost:8080/api/flags/evaluate?flag=checkout-v2&subject=user-42'

# 4) 值班总览（来源状态/降级、近期成功拒绝回滚、打开开关与比例、截断提示）
curl -s localhost:8080/api/ops/audit?action=APPLIED\&limit=5     # 只看成功发布
curl -s 'localhost:8080/api/ops/audit?from=1700000000000&limit=50'
curl -s localhost:8080/api/ops/overview

# 5) 回滚到批次前版本 1：整批内容一起恢复（checkout-v2 也会消失）
curl -s -X POST 'localhost:8080/api/config/rollback/1?operator=oncall'
```

测试覆盖（`src/test/java/.../tests/`）：

- `ConfigServiceTest`：优先级合并、非法项拒绝（原因可区分）、过期/重复变更拒绝、
  来源不可用降级、回滚与审计；
- `FeatureFlagServiceTest`：总开关、0%/100% 放量、同主体稳定性、命中比例统计、
  可解释判定、放量提升只迁移边界主体；
- `ConcurrencyTest`：8 读线程 × 2000 次判定叠加 200 次配置切换，逐条对照版本快照重算，
  验证无新旧混用；16 线程并发提交同一 changeId 仅生效一次；并发 refresh 与读取快照自洽；
- `PreviewServiceTest`（新）：试算预计结果/差异/受影响开关、不改变生效配置与历史、
  不占幂等位置、过期/重复/非法原因可区分；
- `BatchPublishTest`（新）：批次整批生效且只产生一个版本、整批审计含来源/操作者/整批键、
  一项非法/基准过期/重复/空批次整批拒绝且不部分生效、回滚恢复整批内容；
- `BatchConcurrencyTest`（新）：16 线程并发同一 batchId 仅生效一次；批次发布/回滚/试算/
  灰度判定并发时读取方只见完整旧结果或完整新结果，试算不污染状态；
- `OperationsOverviewTest`（新）：来源降级时总览仍可用且明确标注、打开开关与比例正确、
  近期摘要上限与截断提示、审计按结果/时间筛选、有界容量与淘汰计数；
- `FullStackIntegrationTest`（新）：**真正启动 Spring Boot 服务（随机端口）**，从外部 HTTP
  依次打通灰度判定、热更新、审计、回滚以及新增的试算、批次、总览/审计查询，返回 JSON
  均可直接读取解析。

所有新增与既有链路的日志均继续打印输入、判定依据与最终生效结果。
