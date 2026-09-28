package com.github.lisama569.featureflagrolloutjava.release;

/**
 * 试算中会受影响的单个特性开关及其灰度变化。
 *
 * @param flag             开关名（feature.&lt;flag&gt;.* 中的 &lt;flag&gt;）
 * @param currentlyEnabled 当前总开关状态
 * @param projectedEnabled 试算预计总开关状态
 * @param currentPercentage 当前灰度比例（缺省 0）
 * @param projectedPercentage 试算预计灰度比例（缺省 0）
 * @param enabledChanged   总开关是否变化
 * @param percentageDelta  灰度比例变化量（预计 - 当前，正数放量、负数回收）
 */
public record FlagImpact(String flag, boolean currentlyEnabled, boolean projectedEnabled,
                         int currentPercentage, int projectedPercentage,
                         boolean enabledChanged, int percentageDelta) {
}
