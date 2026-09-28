package com.github.lisama569.featureflagrolloutjava.operations;

import com.github.lisama569.featureflagrolloutjava.config.AuditPage;
import com.github.lisama569.featureflagrolloutjava.config.AuditRecord;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigSnapshot;
import com.github.lisama569.featureflagrolloutjava.config.SourceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 值班运行总览与诊断。
 *
 * <p>总览是只读视图，数据来自当前生效快照、来源状态与有界审计缓冲：
 * 有容量上限、可按结果（成功/拒绝/回滚）和时间窗筛选，不会随历史无限增长吃内存。
 * 任一来源不可用时，总览依然可生成，并明确标注“当前为降级状态”。</p>
 */
public class OperationsService {
    private static final Logger log = LoggerFactory.getLogger(OperationsService.class);

    /** 总览中最近活动的默认返回上限。 */
    public static final int DEFAULT_ACTIVITY_LIMIT = 20;

    private final ConfigService configService;

    public OperationsService(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * 生成总览。
     *
     * @param outcomes    只看这些结果（APPLIED/REJECTED/ROLLED_BACK）；null/空表示全部
     * @param sinceMillis 只看该时间之后的活动；null 不限制
     * @param untilMillis 只看该时间之前的活动；null 不限制
     * @param limit       活动返回上限；&lt;=0 用默认值
     */
    public SystemOverview overview(Set<AuditRecord.Action> outcomes, Long sinceMillis,
                                   Long untilMillis, Integer limit) {
        ConfigSnapshot snapshot = configService.currentSnapshot();
        List<SourceStatus> sources = configService.sourceStatuses();
        boolean degraded = sources.stream().anyMatch(SourceStatus::degraded);

        List<FlagOverview> openFlags = collectOpenFlags(snapshot);

        int effLimit = (limit == null || limit <= 0) ? DEFAULT_ACTIVITY_LIMIT : limit;
        AuditPage page = configService.auditLog().query(outcomes, sinceMillis, untilMillis, effLimit);
        List<RecentActivity> activities = new ArrayList<>();
        for (AuditRecord r : page.records()) {
            activities.add(new RecentActivity(r.seq(), r.changeId(), r.source(), r.operator(),
                    r.action().name(), r.fromVersion(), r.toVersion(), r.detail(), r.timestampMillis()));
        }

        String banner = degraded ? buildDegradedBanner(sources)
                : "全部来源正常，当前展示的是最新生效配置（非降级状态）";

        SystemOverview overview = new SystemOverview(snapshot.version(), snapshot.createdAtMillis(),
                degraded, banner, sources, List.copyOf(openFlags), List.copyOf(activities),
                page.returned(), page.totalMatched(), page.limit(), page.truncated(),
                page.droppedTotal(), System.currentTimeMillis());
        log.info("[overview] 输入: outcomes={} since={} until={} limit={} | 依据: 版本={} 来源降级={} | "
                        + "结果: 打开开关={} 活动返回={}/{} 截断={}",
                outcomes, sinceMillis, untilMillis, effLimit, snapshot.version(), degraded,
                openFlags.stream().map(FlagOverview::flag).toList(),
                page.returned(), page.totalMatched(), page.truncated());
        return overview;
    }

    private String buildDegradedBanner(List<SourceStatus> sources) {
        List<String> down = sources.stream()
                .filter(SourceStatus::degraded)
                .map(s -> s.name() + "(" + s.state()
                        + (s.lastSuccessMillis() < 0 ? ",从未成功" : ",最近成功=" + s.lastSuccessMillis())
                        + ")")
                .toList();
        return "当前为降级状态：来源 " + down + " 不可用，正使用其最近一次可用快照或跳过；"
                + "展示值可能不是该来源的最新配置";
    }

    private List<FlagOverview> collectOpenFlags(ConfigSnapshot snapshot) {
        // 只统计当前真正打开（enabled=true）的开关
        Map<String, Boolean> enabledByFlag = new TreeMap<>();
        Map<String, Integer> pctByFlag = new TreeMap<>();
        for (Map.Entry<String, String> e : snapshot.entries().entrySet()) {
            String key = e.getKey();
            if (key.startsWith("feature.") && key.endsWith(".enabled")) {
                String flag = key.substring("feature.".length(),
                        key.length() - ".enabled".length());
                enabledByFlag.put(flag, Boolean.parseBoolean(e.getValue().trim()));
            } else if (key.startsWith("feature.") && key.endsWith(".percentage")) {
                String flag = key.substring("feature.".length(),
                        key.length() - ".percentage".length());
                int pct;
                try {
                    pct = Integer.parseInt(e.getValue().trim());
                } catch (NumberFormatException ex) {
                    pct = 0;
                }
                pctByFlag.put(flag, pct);
            }
        }
        List<FlagOverview> open = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : enabledByFlag.entrySet()) {
            if (!e.getValue()) {
                continue;
            }
            String flag = e.getKey();
            int pct = pctByFlag.getOrDefault(flag, 0);
            // enabled=true 即“打开”：0% 表示已打开但尚未放量，100% 全量，中间为灰度中
            String state = pct >= 100 ? "FULL" : "CANARY";
            open.add(new FlagOverview(flag, true, pct, state));
        }
        return open;
    }
}
