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
该来源从未成功过则跳过该来源并记录警告日志。

## 4. REST 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/config` | 当前生效快照 |
| POST | `/api/config/refresh` | 触发全量热更新 |
| POST | `/api/config/changes` | 提交变更（body 为 ChangeRequest；拒绝时返回 409 + 原因） |
| POST | `/api/config/rollback/{version}?operator=xx` | 回滚到指定版本 |
| GET | `/api/config/audit` | 审计记录 |
| GET | `/api/flags/evaluate?flag=pay&subject=u1` | 开关判定（返回可解释 Decision） |

## 5. 本地验证

```bash
# 运行全部测试（含并发读取与配置切换一致性测试）
mvn test

# 启动服务
mvn spring-boot:run

# 打开开关并灰度 50%
curl -X POST localhost:8080/api/config/changes -H 'Content-Type: application/json' -d '{
  "changeId": "demo-1", "source": "curl", "baseVersion": 1,
  "entries": {"feature.new-checkout.enabled": "true", "feature.new-checkout.percentage": "50"}}'

# 判定（返回输入、依据与结果）
curl 'localhost:8080/api/flags/evaluate?flag=new-checkout&subject=user-42'

# 查看审计 / 回滚到版本 1
curl localhost:8080/api/config/audit
curl -X POST 'localhost:8080/api/config/rollback/1?operator=oncall'
```

测试覆盖（`src/test/java/.../tests/`）：

- `ConfigServiceTest`：优先级合并、非法项拒绝（原因可区分）、过期/重复变更拒绝、
  来源不可用降级、回滚与审计；
- `FeatureFlagServiceTest`：总开关、0%/100% 放量、同主体稳定性、命中比例统计、
  可解释判定、放量提升只迁移边界主体；
- `ConcurrencyTest`：8 读线程 × 2000 次判定叠加 200 次配置切换，逐条对照版本快照重算，
  验证无新旧混用；16 线程并发提交同一 changeId 仅生效一次；并发 refresh 与读取快照自洽。
  单测日志均打印输入、判定依据与最终生效结果。
