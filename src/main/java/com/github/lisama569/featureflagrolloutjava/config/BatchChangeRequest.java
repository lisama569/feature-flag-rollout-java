package com.github.lisama569.featureflagrolloutjava.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次批次原子发布请求：可同时提交多个开关及其配置项。
 *
 * <p>批次内所有条目要么整批生效，要么整批保持原样；成功后只形成一个可回滚版本。
 * 为兼容旧调用方，单开关提交仍可使用 {@link ChangeRequest}（通过 {@link #from} 适配）。</p>
 *
 * @param changeId    整批的幂等标识（整批共享，重复出现则整批拒绝）
 * @param source      来源
 * @param operator    操作者（写入审计，可为 null）
 * @param baseVersion 基准版本，用于乐观并发与过期检测
 * @param entries     整批配置项（可跨多个 feature.* 开关）
 */
public record BatchChangeRequest(String changeId, String source, String operator, long baseVersion,
                                 Map<String, String> entries) {

    public BatchChangeRequest {
        entries = entries == null ? Map.of() : Map.copyOf(entries);
    }

    /** 把旧版单项变更请求视为单条目批次（无操作者）。 */
    public static BatchChangeRequest from(ChangeRequest request) {
        return new BatchChangeRequest(request.changeId(), request.source(), null,
                request.baseVersion(), request.entries());
    }

    /** 把多组条目按顺序合并为一个批次的条目视图。 */
    public static Map<String, String> mergedEntries(Iterable<Map<String, String>> parts) {
        Map<String, String> all = new LinkedHashMap<>();
        for (Map<String, String> part : parts) {
            all.putAll(part);
        }
        return Map.copyOf(all);
    }
}
