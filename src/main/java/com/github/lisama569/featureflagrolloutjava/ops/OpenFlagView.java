package com.github.lisama569.featureflagrolloutjava.ops;

/** 当前打开的开关及其灰度比例（值班视角）。 */
public record OpenFlagView(String flag, boolean enabled, int percentage, long configVersion) {
}
