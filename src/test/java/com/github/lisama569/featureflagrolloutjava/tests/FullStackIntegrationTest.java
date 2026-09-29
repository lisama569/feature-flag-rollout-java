package com.github.lisama569.featureflagrolloutjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真正启动服务（随机端口），从外部 HTTP 依次打通：
 * 灰度判定 -> 热更新 -> 单条变更/审计 -> 发布前试算 -> 批次原子发布 -> 回滚 -> 值班总览/审计查询。
 * 所有返回都以 JSON 读取并解析，核对字段可以被直接读取。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FullStackIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(FullStackIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private java.net.URI uri(String path) {
        return java.net.URI.create("http://localhost:" + port + path);
    }

    @Test
    @SuppressWarnings("unchecked")
    void endToEnd_http_evaluateRefreshPreviewBatchAuditRollbackOverview() {
        // 1) 灰度判定（既有链路）：默认 new-checkout 关闭
        Map<String, Object> decision = rest.getForObject(
                uri("/api/flags/evaluate?flag=new-checkout&subject=user-42"), Map.class);
        log.info("[http] evaluate -> {}", decision);
        assertEquals("new-checkout", decision.get("flagKey"));
        assertEquals("user-42", decision.get("subjectId"));
        assertEquals(Boolean.FALSE, decision.get("enabled"));
        assertNotNull(decision.get("configVersion"));
        assertNotNull(decision.get("reason"));

        // 2) 热更新（既有链路）
        Map<String, Object> refreshed = rest.exchange(
                uri("/api/config/refresh"),
                org.springframework.http.HttpMethod.POST, new org.springframework.http.HttpEntity<Void>(new org.springframework.http.HttpHeaders()), Map.class).getBody();
        log.info("[http] refresh -> version={}", refreshed.get("version"));
        assertNotNull(refreshed.get("version"));

        // 3) 发布前试算（新链路）：应预计可应用，但当前不生效
        long base = ((Number) refreshed.get("version")).longValue();
        Map<String, Object> previewReq = Map.of(
                "changeId", "e2e-preview-1", "source", "curl", "baseVersion", base,
                "entries", Map.of(
                        "feature.new-checkout.enabled", "true",
                        "feature.new-checkout.percentage", "50"));
        Map<String, Object> preview = rest.postForObject(
                uri("/api/config/changes/preview"), jsonEntity(previewReq), Map.class);
        log.info("[http] preview -> wouldApply={} reason={} proposed={}",
                preview.get("wouldApply"), preview.get("rejectionReason"), preview.get("proposedVersion"));
        assertEquals(Boolean.TRUE, preview.get("wouldApply"));
        assertNullish(preview.get("rejectionReason"));
        assertEquals(base + 1, ((Number) preview.get("proposedVersion")).longValue());
        List<Map<String, Object>> diffs = (List<Map<String, Object>>) preview.get("diffs");
        assertTrue(diffs.size() >= 2);
        List<Map<String, Object>> impacts = (List<Map<String, Object>>) preview.get("affectedFlags");
        Map<String, Object> checkoutImpact = impacts.stream()
                .filter(f -> "new-checkout".equals(f.get("flag"))).findFirst().orElseThrow();
        assertEquals(50, ((Number) checkoutImpact.get("percentageAfter")).intValue());
        // 预计可生效时：能拿到按当前生效版本合并后的完整预计配置
        Map<String, String> merged = (Map<String, String>) preview.get("mergedEntries");
        assertNotNull(merged);
        assertEquals("true", merged.get("feature.new-checkout.enabled"));
        assertEquals("50", merged.get("feature.new-checkout.percentage"));

        // 试算不应改变生效版本
        Map<String, Object> currentAfterPreview = rest.getForObject(uri("/api/config"), Map.class);
        assertEquals(base, ((Number) currentAfterPreview.get("version")).longValue());

        // 4) 试算后再做一次过期试算：必须明确 STALE 而不是假装可发布
        Map<String, Object> stalePreviewReq = Map.of(
                "changeId", "e2e-preview-stale", "source", "curl", "baseVersion", base + 999,
                "entries", Map.of("feature.new-checkout.percentage", "10"));
        Map<String, Object> stalePreview = rest.postForObject(
                uri("/api/config/changes/preview"), jsonEntity(stalePreviewReq), Map.class);
        assertEquals(Boolean.FALSE, stalePreview.get("wouldApply"));
        assertEquals("STALE_BASE_VERSION", stalePreview.get("rejectionReason"));
        // 被拒试算不得回带不会生效的预计值
        assertNullish(stalePreview.get("proposedVersion"));
        assertNullish(stalePreview.get("mergedEntries"));
        assertTrue(((List<?>) stalePreview.get("diffs")).isEmpty());
        assertTrue(((List<?>) stalePreview.get("affectedFlags")).isEmpty());

        // 5) 批次原子发布（新链路）：整批一次生效
        Map<String, Object> batchReq = Map.of(
                "batchId", "e2e-batch-1", "source", "release-pipeline", "operator", "alice",
                "baseVersion", base,
                "items", List.of(
                        Map.of("flag", "new-checkout", "entries", Map.of(
                                "feature.new-checkout.enabled", "true",
                                "feature.new-checkout.percentage", "80")),
                        Map.of("flag", "checkout-v2", "entries", Map.of(
                                "feature.checkout-v2.enabled", "true",
                                "feature.checkout-v2.percentage", "25"))));
        ResponseEntity<Map> batchResp = rest.postForEntity(uri("/api/config/batch"), jsonEntity(batchReq), Map.class);
        assertEquals(HttpStatus.OK, batchResp.getStatusCode());
        Map<String, Object> batchSnap = batchResp.getBody();
        log.info("[http] batch -> version={}", batchSnap.get("version"));
        assertEquals(base + 1, ((Number) batchSnap.get("version")).longValue());
        Map<String, String> entriesAfter = (Map<String, String>) batchSnap.get("entries");
        assertEquals("80", entriesAfter.get("feature.new-checkout.percentage"));
        assertEquals("true", entriesAfter.get("feature.checkout-v2.enabled"));
        assertEquals("25", entriesAfter.get("feature.checkout-v2.percentage"));

        // 6) 灰度判定反映批次新值
        Map<String, Object> decision2 = rest.getForObject(
                uri("/api/flags/evaluate?flag=new-checkout&subject=user-7"), Map.class);
        log.info("[http] evaluate after batch -> enabled={} pct={} version={}",
                decision2.get("enabled"), decision2.get("percentage"), decision2.get("configVersion"));
        assertEquals(80, ((Number) decision2.get("percentage")).intValue());
        assertEquals(base + 1, ((Number) decision2.get("configVersion")).longValue());

        // 重复批次：409 + DUPLICATE
        Map<String, Object> dupReq = Map.of(
                "batchId", "e2e-batch-1", "source", "release-pipeline", "operator", "alice",
                "baseVersion", base + 1, "items", List.of(
                        Map.of("flag", "new-checkout", "entries", Map.of(
                                "feature.new-checkout.percentage", "10"))));
        ResponseEntity<Map> dupResp = rest.postForEntity(uri("/api/config/batch"), jsonEntity(dupReq), Map.class);
        assertEquals(HttpStatus.CONFLICT, dupResp.getStatusCode());
        assertEquals("DUPLICATE_CHANGE_ID", dupResp.getBody().get("reason"));

        // 7) 审计（既有全量 + 新的有界筛选）
        List<Map<String, Object>> auditAll = rest.getForObject(uri("/api/config/audit"), List.class);
        assertTrue(auditAll.stream().anyMatch(r -> "e2e-batch-1".equals(r.get("changeId"))
                && "APPLIED".equals(r.get("action"))));
        // 批次审计记录带完整键值：一条记录即可复盘整批发布了什么
        Map<String, Object> batchAudit = auditAll.stream()
                .filter(r -> "e2e-batch-1".equals(r.get("changeId"))
                        && "APPLIED".equals(r.get("action")))
                .findFirst().orElseThrow();
        Map<String, String> batchEntries = (Map<String, String>) batchAudit.get("affectedEntries");
        log.info("[http] batch audit -> entries={} operator={} {}->{}",
                batchEntries, batchAudit.get("operator"),
                batchAudit.get("fromVersion"), batchAudit.get("toVersion"));
        assertEquals("80", batchEntries.get("feature.new-checkout.percentage"));
        assertEquals("true", batchEntries.get("feature.checkout-v2.enabled"));
        assertEquals("25", batchEntries.get("feature.checkout-v2.percentage"));
        assertEquals("alice", batchAudit.get("operator"));
        assertEquals("release-pipeline", batchAudit.get("source"));
        Map<String, Object> appliedOnly = rest.getForObject(
                uri("/api/ops/audit?action=APPLIED&limit=5"), Map.class);
        log.info("[http] ops audit APPLIED -> returned={} truncated={}",
                ((List<?>) appliedOnly.get("records")).size(), appliedOnly.get("truncated"));
        List<Map<String, Object>> appliedRecords = (List<Map<String, Object>>) appliedOnly.get("records");
        assertTrue(appliedRecords.stream().allMatch(r -> "APPLIED".equals(r.get("action"))));
        assertTrue(((List<?>) appliedOnly.get("records")).size() <= 5);
        assertNotNull(appliedOnly.get("retainedCapacity"));

        // 8) 回滚（既有链路）：回滚到批次前版本，整批内容一起撤销
        ResponseEntity<Map> rollbackResp = rest.exchange(
                uri("/api/config/rollback/" + base + "?operator=oncall"),
                org.springframework.http.HttpMethod.POST, new org.springframework.http.HttpEntity<Void>(new org.springframework.http.HttpHeaders()), Map.class);
        assertEquals(HttpStatus.OK, rollbackResp.getStatusCode());
        Map<String, Object> rolled = rollbackResp.getBody();
        Map<String, String> rolledEntries = (Map<String, String>) rolled.get("entries");
        assertFalse("true".equals(rolledEntries.get("feature.checkout-v2.enabled")));
        log.info("[http] rollback -> version={} v2Enabled={}",
                rolled.get("version"), rolledEntries.get("feature.checkout-v2.enabled"));

        // 9) 值班总览（新链路）：可直接读取与解析
        Map<String, Object> overview = rest.getForObject(uri("/api/ops/overview"), Map.class);
        log.info("[http] overview -> version={} degraded={} sources={} recent={} openFlags={}",
                overview.get("currentVersion"), overview.get("degraded"),
                ((List<?>) overview.get("sources")).size(),
                ((List<?>) overview.get("recentActions")).size(),
                ((List<?>) overview.get("openFlags")).size());
        assertNotNull(overview.get("currentVersion"));
        assertNotNull(overview.get("sources"));
        assertNotNull(overview.get("recentActions"));
        assertNotNull(overview.get("recentLimit"));
        assertNotNull(overview.get("recentTruncated"));
        assertNotNull(overview.get("generatedAtMillis"));
        // 版本历史保留信息在总览可见
        assertNotNull(overview.get("historyRetention"));
        assertNotNull(overview.get("historyRetainedVersions"));
        assertNotNull(overview.get("historyEvictedCount"));
        assertNotNull(overview.get("earliestRetainedVersion"));
        // 回滚后 new-checkout 总开关恢复为默认 false，总览中不应再列为打开
        List<Map<String, Object>> openFlags = (List<Map<String, Object>>) overview.get("openFlags");
        assertTrue(openFlags.stream().noneMatch(f -> "checkout-v2".equals(f.get("flag"))));

        // 10) 版本历史超过保留上限后：回滚已淘汰版本 -> 410 VERSION_EVICTED（明确不可用）
        int retention = ((Number) overview.get("historyRetention")).intValue();
        long v = ((Number) rest.getForObject(uri("/api/config"), Map.class).get("version")).longValue();
        for (int i = 0; i < retention + 5; i++) {
            Map<String, Object> change = Map.of(
                    "changeId", "e2e-churn-" + i, "source", "curl", "baseVersion", v,
                    "entries", Map.of("feature.new-checkout.percentage", String.valueOf(i % 100)));
            ResponseEntity<Map> resp = rest.postForEntity(uri("/api/config/changes"), jsonEntity(change), Map.class);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            v = ((Number) resp.getBody().get("version")).longValue();
        }
        Map<String, Object> overview2 = rest.getForObject(uri("/api/ops/overview"), Map.class);
        log.info("[http] after churn -> retained={}/{} evicted={} earliest={}",
                overview2.get("historyRetainedVersions"), overview2.get("historyRetention"),
                overview2.get("historyEvictedCount"), overview2.get("earliestRetainedVersion"));
        assertTrue(((Number) overview2.get("historyEvictedCount")).longValue() > 0,
                "超过保留上限后总览必须能看到淘汰提示");
        assertEquals(retention, ((Number) overview2.get("historyRetainedVersions")).intValue());

        // 回滚到已被淘汰的版本 1：410 + 明确原因，且当前配置不变
        ResponseEntity<Map> evictedResp = rest.exchange(
                uri("/api/config/rollback/1?operator=oncall"),
                org.springframework.http.HttpMethod.POST, new org.springframework.http.HttpEntity<Void>(new org.springframework.http.HttpHeaders()), Map.class);
        log.info("[http] rollback evicted -> {} {}", evictedResp.getStatusCode(), evictedResp.getBody());
        assertEquals(HttpStatus.GONE, evictedResp.getStatusCode());
        assertEquals("VERSION_EVICTED", evictedResp.getBody().get("reason"));
        assertNotNull(evictedResp.getBody().get("earliestAvailableVersion"));
        long versionAfterEvicted = ((Number) rest.getForObject(uri("/api/config"), Map.class)
                .get("version")).longValue();
        assertEquals(v, versionAfterEvicted, "回滚被淘汰版本不得改动当前配置");

        // 回滚到从未存在过的版本：404，与已淘汰明确区分
        ResponseEntity<Map> missingResp = rest.exchange(
                uri("/api/config/rollback/" + (v + 1000) + "?operator=oncall"),
                org.springframework.http.HttpMethod.POST, new org.springframework.http.HttpEntity<Void>(new org.springframework.http.HttpHeaders()), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, missingResp.getStatusCode());
        assertEquals("VERSION_NOT_FOUND", missingResp.getBody().get("reason"));
    }

    private static void assertNullish(Object value) {
        assertTrue(value == null || value.equals(""), "期望为空但为: " + value);
    }

    private static org.springframework.http.HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(org.springframework.http.MediaType.APPLICATION_JSON));
        return new org.springframework.http.HttpEntity<>(body, headers);
    }

}
