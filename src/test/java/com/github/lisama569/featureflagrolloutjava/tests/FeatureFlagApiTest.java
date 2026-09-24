package com.github.lisama569.featureflagrolloutjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP API 集成测试：判定、变更接受、过期/重复提交返回 409 且原因码可区分、回滚、审计。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeatureFlagApiTest {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagApiTest.class);

    @Autowired
    private MockMvc mockMvc;

    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Test
    void evaluateEndpointReturnsExplainableResult() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/feature-flags/dark-mode/evaluate")
                        .param("subject", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flagKey").value("dark-mode"))
                .andExpect(jsonPath("$.subject").value("alice"))
                .andExpect(jsonPath("$.reason").isNotEmpty())
                .andReturn();
        log.info("输入: GET evaluate dark-mode/alice | 最终生效: {}", result.getResponse().getContentAsString());
    }

    @Test
    void submitAcceptThenRejectStaleAndDuplicate() throws Exception {
        String valid = """
                {"requestId":"api-1","sourceName":"remote","expectedSourceVersion":0,
                 "entries":[{"type":"PROPERTY","key":"endpoint","value":"http://api-v2"}]}
                """;
        mockMvc.perform(post("/api/feature-flags/changes")
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").isNumber());
        log.info("输入: 合法提交 api-1 基于 v0 | 判定依据: 接受并发布 | 最终生效: 200 + 新版本");

        // 重复提交
        MvcResult dup = mockMvc.perform(post("/api/feature-flags/changes")
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("DUPLICATE_SUBMISSION"))
                .andReturn();
        log.info("输入: 重复 api-1 | 最终生效: 409 {}", dup.getResponse().getContentAsString());

        // 过期提交：仍基于 v0，但来源已到 v1
        String stale = """
                {"requestId":"api-2","sourceName":"remote","expectedSourceVersion":0,
                 "entries":[{"type":"PROPERTY","key":"endpoint","value":"http://api-v3"}]}
                """;
        MvcResult staleResult = mockMvc.perform(post("/api/feature-flags/changes")
                        .contentType(MediaType.APPLICATION_JSON).content(stale))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("STALE_VERSION"))
                .andReturn();
        log.info("输入: api-2 基于过期 v0 | 最终生效: 409 {}", staleResult.getResponse().getContentAsString());

        // 非法提交：放量比例越界
        String invalid = """
                {"requestId":"api-3","sourceName":"remote","expectedSourceVersion":1,
                 "entries":[{"type":"FLAG","key":"bad","enabled":true,"rolloutPercentage":250,
                             "defaultValue":false,"variantKey":"exp"}]}
                """;
        MvcResult invalidResult = mockMvc.perform(post("/api/feature-flags/changes")
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("INVALID_FLAG_PERCENTAGE"))
                .andReturn();
        log.info("输入: api-3 放量250 | 最终生效: 409 {}", invalidResult.getResponse().getContentAsString());

        // 未知条目类型：JSON 层无法反序列化 → 400 INVALID_PAYLOAD，与业务校验原因区分
        String unknownType = """
                {"requestId":"api-4","sourceName":"remote","expectedSourceVersion":1,
                 "entries":[{"type":"SECRET","key":"k","value":"v"}]}
                """;
        MvcResult unreadable = mockMvc.perform(post("/api/feature-flags/changes")
                        .contentType(MediaType.APPLICATION_JSON).content(unknownType))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_PAYLOAD"))
                .andReturn();
        log.info("输入: api-4 type=SECRET(未知) | 最终生效: 400 {}", unreadable.getResponse().getContentAsString());
    }

    @Test
    void rollbackAndAuditEndpointsWork() throws Exception {
        mockMvc.perform(get("/api/feature-flags/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").exists())
                .andDo(r -> log.info("输入: GET audit | 最终生效: 记录数={}",
                        r.getResponse().getContentAsString().split("kind").length - 1));

        MvcResult current = mockMvc.perform(get("/api/feature-flags/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").isNumber())
                .andReturn();
        long currentVersion = mapper.readTree(current.getResponse().getContentAsString())
                .get("version").asLong();

        MvcResult versions = mockMvc.perform(get("/api/feature-flags/versions"))
                .andExpect(status().isOk())
                .andReturn();
        // 回滚到最早的历史版本（一定不是当前版本，除非只有一个版本；此时应得到 409 NO_OP_CHANGE）
        long earliest = mapper.readTree(versions.getResponse().getContentAsString())
                .get(0).get("version").asLong();
        var rollbackCall = mockMvc.perform(post("/api/feature-flags/rollback/" + earliest));
        if (earliest == currentVersion) {
            rollbackCall.andExpect(status().isConflict())
                    .andExpect(jsonPath("$.reason").value("NO_OP_CHANGE"));
            log.info("输入: rollback/{} 但已是当前 | 最终生效: 409 NO_OP_CHANGE", earliest);
        } else {
            rollbackCall.andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").isNumber())
                    .andDo(r -> log.info("输入: rollback/{} | 最终生效: {}", earliest,
                            r.getResponse().getContentAsString()));
        }
    }
}
