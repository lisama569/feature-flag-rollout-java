package com.github.lisama569.featureflagrolloutjava.config;

import java.util.List;

/**
 * 批次原子发布请求：一次提交多个开关的配置项，整批生效或整批保持原样。
 * batchId 为整批的幂等键（与单条 changeId 共用同一去重空间）；
 * operator 为操作者，会进入审计；baseVersion 为乐观并发基准。
 */
public record BatchPublishRequest(String batchId, String source, String operator,
                                  long baseVersion, List<BatchItem> items) {

    /** 批次内的一个开关及其配置项（通常含 enabled / percentage）。 */
    public record BatchItem(String flag, java.util.Map<String, String> entries) {
    }
}
