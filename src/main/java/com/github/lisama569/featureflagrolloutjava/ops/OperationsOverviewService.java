package com.github.lisama569.featureflagrolloutjava.ops;

import com.github.lisama569.featureflagrolloutjava.config.AuditRecord;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigSnapshot;
import com.github.lisama569.featureflagrolloutjava.config.SourceHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 面向值班人员的运行总览与诊断。
 *
 * <p>只读取当前快照、来源健康与（有界）审计日志，不触碰生效配置：
 * 即使某配置来源拉取失败，总览本身仍可生成，并通过 {@code degraded=true} 与各来源状态
 * 明确告诉值班人员当前看到的是降级状态。近期动作有固定容量上限并给出截断提示。</p>
 */
public class OperationsOverviewService {
    private static final Logger log = LoggerFactory.getLogger(OperationsOverviewService.class);

    private final ConfigService configService;

    public OperationsOverviewService(ConfigService configService) {
        this.configService = configService;
    }

    /** 近期动作在总览中的展示上限。 */
    public static final int RECENT_LIMIT = 20;

    /** 生成当前运行总览。 */
    public OperationsOverview overview() {
        ConfigSnapshot snapshot = configService.currentSnapshot();
        List<SourceStatus> sources = new ArrayList<>();
        boolean anyDegraded = false;
        for (SourceHealth h : configService.sourceHealth()) {
            anyDegraded |= h.degraded();
            sources.add(new SourceStatus(h.name(), h.priority(), h.degraded(),
                    h.lastSuccessMillis(), h.lastFailureMillis(), h.lastFailureReason(),
                    h.lastGoodEntryCount()));
        }

        // 近期动作：取有界审计的最近 RECENT_LIMIT 条（任意结果），最新在前
        AuditPage page = configService.auditLog()
                .query(null, null, null, RECENT_LIMIT);
        List<RecentAction> recent = new ArrayList<>();
        for (AuditRecord r : page.records()) {
            recent.add(new RecentAction(r.action().name(), r.changeId(), r.source(), r.operator(),
                    r.fromVersion(), r.toVersion(), r.timestampMillis(), r.detail()));
        }

        List<OpenFlagView> openFlags = collectOpenFlags(snapshot);

        int historyRetained = configService.history().size();
        long historyEvicted = configService.historyEvictedCount();
        OperationsOverview overview = new OperationsOverview(
                snapshot.version(), anyDegraded, List.copyOf(sources), List.copyOf(recent),
                RECENT_LIMIT, page.truncated(), page.totalMatched(),
                List.copyOf(openFlags),
                configService.historyRetention(), historyRetained, historyEvicted,
                configService.earliestRetainedVersion(),
                System.currentTimeMillis());
        log.info("[ops-overview] 输入: 值班总览请求 | 依据: 版本={} 来源数={} 降级={} 近期动作={}/{} 截断={} 打开开关={} 历史保留={}/{} 已淘汰={} | 结果: 总览已生成{}",
                snapshot.version(), sources.size(), anyDegraded, recent.size(),
                page.totalMatched(), page.truncated(), openFlags.size(),
                historyRetained, configService.historyRetention(), historyEvicted,
                anyDegraded ? "（降级状态）" : "");
        return overview;
    }

    private List<OpenFlagView> collectOpenFlags(ConfigSnapshot snapshot) {
        Map<String, String> entries = snapshot.entries();
        Set<String> flags = new TreeSet<>();
        for (String key : entries.keySet()) {
            String flag = ConfigService.flagOf(key);
            if (flag != null) {
                flags.add(flag);
            }
        }
        List<OpenFlagView> views = new ArrayList<>();
        for (String flag : flags) {
            boolean enabled = Boolean.parseBoolean(
                    entries.getOrDefault("feature." + flag + ".enabled", "false").trim());
            int percentage = 0;
            String pctRaw = entries.get("feature." + flag + ".percentage");
            if (pctRaw != null) {
                try {
                    percentage = Integer.parseInt(pctRaw.trim());
                } catch (NumberFormatException ignored) {
                    percentage = 0;
                }
            }
            // “当前打开的开关”：总开关打开即纳入（无论灰度比例），值班可据此看到灰度比例
            if (enabled) {
                views.add(new OpenFlagView(flag, true, percentage, snapshot.version()));
            }
        }
        return List.copyOf(views);
    }

    /** 按时间区间/结果筛选审计记录（有容量上限与截断提示）。 */
    public AuditPage queryAudit(AuditRecord.Action action, Long fromMillis, Long toMillis, Integer limit) {
        AuditPage page = configService.auditLog().query(action, fromMillis, toMillis, limit);
        log.info("[ops-audit] 输入: action={} from={} to={} limit={} | 依据: 有界审计(容量={} 已淘汰={}) | 结果: 返回={} 匹配={} 截断={}",
                action, fromMillis, toMillis, page.limit(),
                page.retainedCapacity(), page.evicted(),
                page.records().size(), page.totalMatched(), page.truncated());
        return page;
    }
}
