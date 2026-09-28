package com.github.lisama569.featureflagrolloutjava.release;

/**
 * 单个配置项在“当前生效值 → 试算预计值”之间的差异。
 *
 * @param changeType 变化类型（新增/修改/删除/未变）
 * @param key        配置键
 * @param currentValue 当前生效值；null 表示当前不存在该键
 * @param projectedValue 试算合并后的预计值；非法项不参与合并时为 null
 * @param accepted   该项是否会被接受（非法项为 false 并附拒绝原因）
 * @param rejectionCode 被拒绝时的可区分原因码；未被拒绝为 null
 * @param rejectionMessage 被拒绝时的人类可读说明
 */
public record ItemDiff(String changeType, String key, String currentValue, String projectedValue,
                       boolean accepted, String rejectionCode, String rejectionMessage) {
}
