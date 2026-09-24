# 热更新配置与特性开关服务（feature-flag-rollout-java）

一套可在运行期热更新的配置 / 特性开关能力，目标是让**配置分层、灰度放量、异常降级**的结果
在并发访问下保持**一致且可解释**。

- 多来源按优先级分层合并，热更新期间读取方永远看到完整快照
- 按比例灰度放量 + 实验分流，同主体在配置版本不变时稳定命中
- 乐观并发提交（拒绝过期 / 重复 / 无变化 / 非法变更，原因可区分）
- 变更审计、按版本回滚；来源不可用时降级到最近一次可用快照

---

## 1. 整体结构

```
domain/      不可变领域模型：ConfigEntry(Property|Flag)、MergedConfig、EvaluationResult、
             ChangeRequest、AuditRecord、ValidationIssue、RejectReason …
rollout/     Bucketer 分流器（FNV-1a 64bit → [0,100) 稳定桶）
validation/  StrictConfigValidator 逐条严格校验，非法条目剔除并携带可区分原因
merge/       PriorityConfigMerger 按来源优先级分层合并
source/      ConfigSource / MapConfigSource（内存实现，可模拟来源不可用）
audit/       AuditLog 变更审计；SnapshotHistory 已发布快照历史（回滚/降级依据）
service/     FeatureFlagService 核心：原子快照发布、降级、乐观提交、回滚
             ConfigRefreshScheduler 周期热更新（默认 10s，可配）
web/         AdminController 管理面 REST API；ApiExceptionHandler 拒绝原因 → HTTP 码
config/      默认来源装配、Jackson 定制
```

**一致性核心**：当前生效配置始终是同一个不可变 `MergedConfig`，由单个
`AtomicReference` 持有。读取（`evaluate` / `getProperty`）一次调用只取一次快照引用，
在该引用上完成全部判定；发布（reload / submit / rollback）在同一把写锁上串行化，
构造好完整的新快照后一次性原子替换。因此切换瞬间，任何一次读取看到的要么是完整旧配置、
要么是完整新配置，**不存在新旧分支混用或半新半旧**。

---

## 2. 配置分层与合并规则

1. 来源按 `priority()` 数值从大到小排序（默认装配：`remote`=100 > `local-defaults`=0）。
2. 重载时每个来源被**独立**拉取与校验：
   - 合法条目进入合并；
   - 非法条目被剔除并记录为 `ValidationIssue(source, key, reason, detail)`，
     收集进新快照的 `rejected` 列表，对读取方可解释。
3. 合并对每个 key 采用「**高优先级先出现者生效**」（`putIfAbsent`）。
   因为非法条目在进入合并器之前已被剔除，所以**高优先级来源的非法 / 缺失值绝不会
   静默覆盖低优先级的已生效值**——这是与"浅拷贝覆盖"式合并最关键的区别。
4. 属性（`PropertyEntry`）与开关（`FlagEntry`）分别在各自命名空间内合并。
5. 仅当合并内容发生变化，或本轮存在拒绝 / 降级记录（需要可解释性）时，才发布全局
   单调递增的新版本；无变化的普通重载不产生噪声版本。

非法原因（`RejectReason`，全部可区分）：

| 原因 | 触发条件 |
| --- | --- |
| `INVALID_PAYLOAD` | 请求体无法解析、键为空、同一来源内键重复 |
| `INVALID_FLAG_PERCENTAGE` | 开关放量比例不在 `[0,100]` |
| `INVALID_FLAG_TYPE` | 开关缺少变体键 `variantKey` 等类型问题 |
| `INVALID_PROPERTY_BLANK` | 属性值为 null / 空白 |
| `UNKNOWN_ENTRY_TYPE` | 出现未知条目实现类型 |
| `STALE_VERSION` / `DUPLICATE_SUBMISSION` / `NO_OP_CHANGE` / `UNKNOWN_SOURCE` / `SOURCE_UNAVAILABLE` | 见第 5 节 |

---

## 3. 灰度分流语义

开关定义 `FlagEntry(key, enabled, rolloutPercentage, defaultValue, variantKey)`：

- `enabled=false`：总开关关闭，**所有**主体取 `defaultValue`（结果里给出依据）。
- `enabled=true`：对主体计算稳定桶
  `bucket = FNV1a64(flagKey + "|" + variantKey + "|" + subject) mod 100`：
  - `bucket < rolloutPercentage` → 命中灰度，取 `defaultValue` 的翻转值；
  - 否则取 `defaultValue`。
- 边界确定：`0%` 全量关闭、`100%` 全量开启。
- **稳定性**：哈希输入不含合并版本号，只含开关键 / 变体 / 主体。因此：
  - 开关定义不变、配置版本不变 → 同主体永远同结果；
  - 一次纯来源热更新（开关定义未变）不会造成无谓的重新分流；
  - 调比例时，只有跨过新百分比边界的主体改变状态，符合灰度"逐步放量"直觉；
  - 换 `variantKey` 即开启一条相互独立的新实验分流。
- 判定结果 `EvaluationResult` 携带 `bucket / percentage / configVersion / reason`，
  完整记录"输入是什么、依据什么、最终生效什么"。

---

## 4. 热更新、降级与回滚策略

### 热更新

- 启动时 `@PostConstruct` 自动加载一次；之后 `ConfigRefreshScheduler` 按
  `featureflag.refresh-ms`（默认 10000ms）周期 reload，也可 `POST /reload` 手动触发。
- reload 流程：拉取各来源 →（失败则）降级替换 → 严格校验 → 分层合并 → 原子发布。
- 调度线程吞掉一切异常，保证后台刷新失败不影响应用，且服务继续沿用当前生效快照。

### 来源不可用降级（不失败）

- 每个来源最近一次**成功拉取**的原始快照缓存在服务内。
- 某来源拉取抛 `SourceUnavailableException` 时：
  - 有缓存 → 使用该缓存快照继续参与本轮合并，并在新快照的 `degraded` 列表记录
    `DegradedSource(source, detail, usedSourceVersion)`，同时写一条 `DEGRADATION` 审计；
  - 无缓存 → 跳过该来源（其余来源仍合并生效），同样记录降级，而不是让整体加载失败。

### 审计与回滚

- 每次 `RELOAD / SUBMIT_ACCEPTED / SUBMIT_REJECTED / ROLLBACK / DEGRADATION`
  都追加一条不可变 `AuditRecord`（时间、来源、from/to 版本、说明、最终配置版本）。
- 每个已发布版本同时保存"当时各来源的完整状态"。
- `rollback(targetVersion)`：恢复目标版本各来源内容 → 正常合并 → **作为新版本发布**
  （历史只追加、不改写；回滚本身也有审计与新的全局版本号）。后续定时 reload 与
  已恢复的来源状态一致，不会互相打架。

### 并发变更协议（`POST /changes`）

`ChangeRequest(requestId, sourceName, expectedSourceVersion, entries)` 的检查顺序：

1. 来源不存在 → `UNKNOWN_SOURCE`；
2. `requestId` 已处理过 → `DUPLICATE_SUBMISSION`（幂等键拒绝重复提交）；
3. 来源当前版本 != `expectedSourceVersion` → `STALE_VERSION`（过期变更，不覆盖生效值）；
4. 提交内容与当前完全一致 → `NO_OP_CHANGE`；
5. 逐条校验不通过 → 返回首个非法条目的可区分原因（如 `INVALID_FLAG_PERCENTAGE`）；
   被拒请求的 requestId 会被释放，允许同一 ID 修正后重试。

只有全部通过才替换来源内容并发布新版本。HTTP 层：并发 / 业务校验拒绝 → `409` +
`reason`；请求体无法解析（含未知条目类型）→ `400 INVALID_PAYLOAD`。

---

## 5. HTTP API 一览

| 方法与路径 | 说明 |
| --- | --- |
| `GET  /api/feature-flags/current` | 当前生效快照（版本、flags、properties、rejected、degraded） |
| `GET  /api/feature-flags/{key}/evaluate?subject=...` | 开关判定（含桶值、比例、版本、文字依据） |
| `GET  /api/feature-flags/properties/{key}` | 读取属性生效值 |
| `POST /api/feature-flags/reload` | 手动触发一次热更新 |
| `POST /api/feature-flags/changes` | 提交来源变更（见上） |
| `POST /api/feature-flags/rollback/{version}` | 回滚到历史配置版本 |
| `GET  /api/feature-flags/versions` | 已发布版本列表 |
| `GET  /api/feature-flags/audit` | 审计记录 |

变更请求体示例：

```json
{
  "requestId": "uuid-或业务幂等键",
  "sourceName": "remote",
  "expectedSourceVersion": 0,
  "entries": [
    {"type": "PROPERTY", "key": "endpoint", "value": "http://new"},
    {"type": "FLAG", "key": "dark-mode", "enabled": true,
     "rolloutPercentage": 50, "defaultValue": false, "variantKey": "ui-exp"}
  ]
}
```

拒绝响应示例（HTTP 409）：

```json
{"reason": "STALE_VERSION", "message": "期望来源版本 v0，当前已为 v1"}
```

---

## 6. 本地验证方法

要求 JDK 21+、Maven 3.9+。

```bash
# 全量测试（31 个用例，单测日志会打印 输入 / 判定依据 / 最终生效结果）
mvn test

# 只跑并发用例（24000 次读取与 20 轮切换对账、16 路并发提交仲裁）
mvn test -Dtest=ConcurrencyTest

# 启动服务（默认 10s 周期热更新，可用 -Dfeatureflag.refresh-ms= 调整）
mvn spring-boot:run

# 另开终端做手工验证
curl -s 'http://localhost:8080/api/feature-flags/current' | head
curl -s 'http://localhost:8080/api/feature-flags/dark-mode/evaluate?subject=alice'
curl -s -X POST http://localhost:8080/api/feature-flags/changes \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"t1","sourceName":"remote","expectedSourceVersion":0,
       "entries":[{"type":"FLAG","key":"dark-mode","enabled":true,
       "rolloutPercentage":100,"defaultValue":false,"variantKey":"ui-exp"}]}'
# 再提交一次同样的 requestId → 409 DUPLICATE_SUBMISSION
curl -s 'http://localhost:8080/api/feature-flags/audit'
curl -s -X POST http://localhost:8080/api/feature-flags/rollback/1
```

### 测试覆盖矩阵

| 测试类 | 覆盖内容 |
| --- | --- |
| `BucketerTest` | 同主体稳定同桶、桶在 `[0,100)`、10000 主体近似均匀 |
| `ValidatorTest` | 比例越界 / 变体键空白 / 属性空白 / 键重复，原因两两可区分 |
| `MergerTest` | 高优先级覆盖、非法高优值不覆盖低优生效值 |
| `FeatureFlagServiceTest` | 分层加载、0%/100% 边界、稳定性、50% 分布、来源不可用降级缓存、非法项拒绝 |
| `ChangeAndRollbackTest` | 接受 / 过期 / 重复 / 无变化 / 非法 / 未知来源的可区分拒绝、回滚、审计 |
| `ConcurrencyTest` | 12×2000 并发读取与 20 轮切换逐项对账（0 违规）、16 路并发提交唯一赢家 |
| `FeatureFlagApiTest` | REST 全链路：判定、409 原因码、400 INVALID_PAYLOAD、回滚、审计 |
