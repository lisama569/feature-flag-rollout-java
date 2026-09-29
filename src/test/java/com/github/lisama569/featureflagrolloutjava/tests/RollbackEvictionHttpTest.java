package com.github.lisama569.featureflagrolloutjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 通过真实 HTTP 验证：历史保留上限很小时，超出上限的旧版本被淘汰、
 * /api/config/history 能看到淘汰提示，回滚到已淘汰版本返回 410 VERSION_EVICTED
 * 并明确给出可回滚最早版本，而不是静默恢复或抛看不懂的错误。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "feature-flag.history-retention-limit=3")
class RollbackEvictionHttpTest {
    private static final Logger log = LoggerFactory.getLogger(RollbackEvictionHttpTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    @SuppressWarnings("unchecked")
    void evictedVersionRollback_overHttp_returns410WithClearHint() {
        // 默认启动后构造器 refresh 已产生 v1；再发布 4 次 => v2..v5，保留窗口只剩 [v3,v4,v5]
        for (int i = 1; i <= 4; i++) {
            long base = ((Number) rest.getForObject(uri("/api/config"), Map.class).get("version")).longValue();
            Map<String, Object> req = Map.of(
                    "changeId", "evict-" + i, "source", "curl", "baseVersion", base,
                    "entries", Map.of("feature.new-checkout.percentage", String.valueOf(i)));
            ResponseEntity<Map> resp = rest.postForEntity(
                    uri("/api/config/changes"), json(req), Map.class);
            assertEquals(HttpStatus.OK, resp.getStatusCode(), "第 " + i + " 次发布应成功");
        }

        Map<String, Object> history = rest.getForObject(uri("/api/config/history"), Map.class);
        log.info("[http] 淘汰后 history={}", history);
        assertEquals(3, ((Number) history.get("retentionLimit")).intValue());
        assertEquals(3, ((Number) history.get("retainedCount")).intValue());
        List<Number> retained = (List<Number>) history.get("retainedVersions");
        assertEquals(3L, retained.get(0).longValue());
        assertEquals(5L, retained.get(2).longValue());
        assertEquals(3, ((Number) history.get("evictedVersionCount")).intValue(),
                "v0,v1,v2 三个版本被淘汰");
        assertNotNull(history.get("recentlyEvictedVersions"));

        // 回滚到已淘汰的 v1：明确 410，不静默恢复
        ResponseEntity<Map> gone = rest.exchange(uri("/api/config/rollback/1?operator=oncall"),
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<Void>(new org.springframework.http.HttpHeaders()), Map.class);
        log.info("[http] rollback evicted v1 -> {} body={}", gone.getStatusCode(), gone.getBody());
        assertEquals(HttpStatus.GONE, gone.getStatusCode());
        assertEquals("VERSION_EVICTED", gone.getBody().get("reason"));
        assertEquals(3, ((Number) gone.getBody().get("oldestRetainedVersion")).intValue());
        assertEquals(3, ((Number) gone.getBody().get("retentionLimit")).intValue());
        assertNotNull(gone.getBody().get("message"));

        // 回滚到窗口内的 v3 仍成功，且是明确的“恢复到 v3 内容”
        ResponseEntity<Map> ok = rest.exchange(uri("/api/config/rollback/3?operator=oncall"),
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<Void>(new org.springframework.http.HttpHeaders()), Map.class);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        Map<String, String> entries = (Map<String, String>) ok.getBody().get("entries");
        log.info("[http] rollback retained v3 -> version={} pct={}",
                ok.getBody().get("version"), entries.get("feature.new-checkout.percentage"));
        assertEquals("2", entries.get("feature.new-checkout.percentage"),
                "v3 是 evict-2 发布后的内容（percentage=2）");
    }

    private static org.springframework.http.HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return new org.springframework.http.HttpEntity<>(body, headers);
    }
}
