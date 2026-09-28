package com.github.lisama569.featureflagrolloutjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真正启动服务（随机端口），从外部 HTTP 走完整链路：
 * 灰度判定 → 热更新 → 审计 → 试算（不改状态）→ 批次发布 → 回滚 → 总览。
 * 返回内容必须可以被直接读取与解析为 JSON。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FullStackHttpTest {
    private static final Logger log = LoggerFactory.getLogger(FullStackHttpTest.class);

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private String base(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base(path)))
                        .header("Accept", "application/json")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base(path)))
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void endToEnd_http_evaluateRefreshPreflightBatchAuditRollbackOverview() throws Exception {
        // 0) 初始配置：默认 new-checkout=false / 0
        HttpResponse<String> initial = get("/api/config");
        assertEquals(200, initial.statusCode());
        assertTrue(initial.body().contains("new-checkout"), "当前快照 JSON 可读: " + initial.body());
        long startVersion = JsonUtil.readLong(initial.body(), "version");
        log.info("[http] 初始配置版本={} body={}", startVersion, initial.body());

        // 1) 灰度判定（外部调用既有判定链路）：关闭时应 enabled=false
        HttpResponse<String> decisionOff = get("/api/flags/evaluate?flag=new-checkout&subject=user-42");
        assertEquals(200, decisionOff.statusCode());
        assertTrue(decisionOff.body().contains("\"enabled\":false"),
                "关闭状态判定应为 false: " + decisionOff.body());
        assertTrue(decisionOff.body().contains("bucket") && decisionOff.body().contains("reason"),
                "判定返回必须包含输入/依据/结果字段: " + decisionOff.body());
        log.info("[http] 关闭时判定: {}", decisionOff.body());

        // 2) 发布前试算：预计打开到 50%，但不得改变生效值
        String preflightBody = """
                {"changeId":"e2e-pf-1","source":"curl-e2e","operator":"alice","baseVersion":%d,
                 "entries":{"feature.new-checkout.enabled":"true","feature.new-checkout.percentage":"50"}}
                """.formatted(startVersion);
        HttpResponse<String> pf = post("/api/releases/preflight", preflightBody);
        assertEquals(200, pf.statusCode());
        assertTrue(pf.body().contains("\"status\":\"APPLYABLE\""), "试算应可发布: " + pf.body());
        assertTrue(pf.body().contains("\"wouldApply\":true"), pf.body());
        assertTrue(pf.body().contains("new-checkout"), "应列出受影响开关: " + pf.body());
        assertTrue(pf.body().contains("percentageDelta"), "应给出灰度变化: " + pf.body());
        // 试算后生效配置仍是旧值，版本未前进
        HttpResponse<String> afterPf = get("/api/config");
        assertEquals(startVersion, JsonUtil.readLong(afterPf.body(), "version"),
                "试算不得改变生效版本");
        assertFalse(afterPf.body().contains("\"feature.new-checkout.enabled\":\"true\""),
                "试算不得让开关真的打开");
        log.info("[http] 试算结果(不生效): {}", pf.body());

        // 3) 批次原子发布：整批打开并灰度 50%，只形成一个新版本
        String batchBody = """
                {"changeId":"e2e-batch-1","source":"curl-e2e","operator":"alice","baseVersion":%d,
                 "entries":{"feature.new-checkout.enabled":"true","feature.new-checkout.percentage":"50"}}
                """.formatted(startVersion);
        HttpResponse<String> batch = post("/api/releases/batch", batchBody);
        assertEquals(200, batch.statusCode(), "批次发布应成功: " + batch.body());
        long afterVersion = JsonUtil.readLong(batch.body(), "version");
        assertEquals(startVersion + 1, afterVersion, "整批只能形成一个新版本");
        log.info("[http] 批次发布后版本={}", afterVersion);

        // 4) 灰度判定在打开后返回 enabled 且依据新版本
        HttpResponse<String> decisionOn = get("/api/flags/evaluate?flag=new-checkout&subject=user-42");
        assertTrue(decisionOn.body().contains("\"configVersion\":" + afterVersion),
                "判定应基于新版本: " + decisionOn.body());
        log.info("[http] 打开后判定: {}", decisionOn.body());

        // 5) 重复批次标识再次出现：试算与真实发布都应明确为重复
        HttpResponse<String> pfDup = post("/api/releases/preflight", batchBody);
        assertTrue(pfDup.body().contains("\"status\":\"DUPLICATE_CHANGE_ID\""),
                "试算应区分重复标识: " + pfDup.body());
        HttpResponse<String> dupPublish = post("/api/releases/batch", batchBody);
        assertEquals(409, dupPublish.statusCode());
        assertTrue(dupPublish.body().contains("DUPLICATE_CHANGE_ID"),
                "真实重复发布应 409 且原因可区分: " + dupPublish.body());

        // 6) 过期基准：用旧基准试算/发布都应明确为过期
        String staleBody = batchBody.replace("e2e-batch-1", "e2e-stale")
                .replace("\"baseVersion\":" + startVersion, "\"baseVersion\":" + (startVersion - 3));
        HttpResponse<String> pfStale = post("/api/releases/preflight", staleBody);
        assertTrue(pfStale.body().contains("\"status\":\"STALE_BASE_VERSION\""),
                "试算应区分过期基准: " + pfStale.body());
        HttpResponse<String> stalePublish = post("/api/releases/batch", staleBody);
        assertEquals(409, stalePublish.statusCode());
        assertTrue(stalePublish.body().contains("STALE_BASE_VERSION"),
                "过期发布应 409 且原因可区分: " + stalePublish.body());
        log.info("[http] 重复/过期均被明确拒绝，未误生效");

        // 7) 审计：可直接读取，含批次来源/操作者/前后版本
        HttpResponse<String> audit = get("/api/config/audit");
        assertEquals(200, audit.statusCode());
        assertTrue(audit.body().contains("e2e-batch-1"), "审计应含整批 changeId: " + audit.body());
        assertTrue(audit.body().contains("alice"), "审计应含操作者: " + audit.body());
        assertTrue(audit.body().contains("APPLIED"), "审计应含成功动作: " + audit.body());
        log.info("[http] 审计长度={} 字符，含批次/操作者/动作", audit.body().length());

        // 7.1) 有界/可筛选审计查询
        HttpResponse<String> auditQuery = get("/api/config/audit/query?outcomes=APPLIED&limit=5");
        assertEquals(200, auditQuery.statusCode());
        assertTrue(auditQuery.body().contains("\"truncated\""), "应返回截断标志: " + auditQuery.body());
        assertTrue(auditQuery.body().contains("\"totalMatched\""), "应返回匹配总数: " + auditQuery.body());

        // 8) 回滚到发布前版本：整批恢复（开关重新关闭、比例 0）
        HttpResponse<String> rolled = post("/api/config/rollback/" + startVersion + "?operator=oncall", null);
        assertEquals(200, rolled.statusCode());
        assertTrue(rolled.body().contains("\"feature.new-checkout.enabled\":\"false\""),
                "回滚应整批恢复关闭: " + rolled.body());
        assertTrue(rolled.body().contains("\"feature.new-checkout.percentage\":\"0\""),
                "回滚应整批恢复比例 0: " + rolled.body());
        log.info("[http] 回滚后配置: {}", rolled.body());

        // 9) 热更新链路仍可用（既有能力不回归）
        HttpResponse<String> refreshed = post("/api/config/refresh", null);
        assertEquals(200, refreshed.statusCode());
        assertTrue(refreshed.body().contains("version"), refreshed.body());

        // 10) 值班总览：可直接读取解析，含版本/来源/活动/开关
        HttpResponse<String> overview = get("/api/operations/overview?limit=10");
        assertEquals(200, overview.statusCode());
        String ov = overview.body();
        assertTrue(ov.contains("currentVersion") && ov.contains("sources"),
                "总览应含当前版本与来源: " + ov);
        assertTrue(ov.contains("statusBanner"), "总览应含状态横幅: " + ov);
        assertTrue(ov.contains("recentActivity"), "总览应含最近活动: " + ov);
        assertTrue(ov.contains("activityTruncated") && ov.contains("activityLimit"),
                "总览活动应有容量与截断提示: " + ov);
        // 回滚后 new-checkout 关闭，不应出现在 openFlags
        assertFalse(ov.contains("\"flag\":\"new-checkout\""),
                "关闭的开关不应在 openFlags: " + ov);
        log.info("[http] 值班总览: {}", ov);

        // 11) 旧调用方兼容入口仍工作
        String legacyChange = """
                {"changeId":"e2e-legacy-1","source":"legacy","baseVersion":%d,
                 "entries":{"feature.new-checkout.percentage":"5"}}
                """.formatted(JsonUtil.readLong(get("/api/config").body(), "version"));
        HttpResponse<String> legacy = post("/api/config/changes", legacyChange);
        assertEquals(200, legacy.statusCode(), "旧 /changes 入口应继续可用: " + legacy.body());
        assertTrue(legacy.body().contains("\"feature.new-checkout.percentage\":\"5\""), legacy.body());
        log.info("[http] 旧 /changes 兼容入口发布成功");
    }

    /** 极简 JSON 字段读取，避免引入额外依赖；仅用于测试中直接解析返回内容。 */
    static final class JsonUtil {
        static long readLong(String json, String field) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
            if (!m.find()) {
                throw new IllegalStateException("JSON 中缺少数字字段 " + field + ": " + json);
            }
            return Long.parseLong(m.group(1));
        }
    }
}
