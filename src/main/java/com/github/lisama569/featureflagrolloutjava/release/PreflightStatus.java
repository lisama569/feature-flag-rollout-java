package com.github.lisama569.featureflagrolloutjava.release;

/**
 * 试算结论。三种状态必须可明确区分，调用方不能把试算结果误认为已经生效：
 * <ul>
 *   <li>{@link #APPLYABLE}：若直接正式发布，整批可以生效；试算本身不改变任何状态。</li>
 *   <li>{@link #STALE_BASE_VERSION}：基准版本已经过期（与当前生效版本不一致），按此提交必被拒绝。</li>
 *   <li>{@link #DUPLICATE_CHANGE_ID}：该 changeId 此前已经真实发布过，再次出现会按重复提交拒绝。</li>
 *   <li>{@link #INVALID_ENTRIES}：拟变更内容含非法项，逐项原因见 rejectedItems。</li>
 *   <li>{@link #EMPTY_BATCH}：一个开关/配置项都没有提交。</li>
 * </ul>
 */
public enum PreflightStatus {
    APPLYABLE,
    STALE_BASE_VERSION,
    DUPLICATE_CHANGE_ID,
    INVALID_ENTRIES,
    EMPTY_BATCH
}
