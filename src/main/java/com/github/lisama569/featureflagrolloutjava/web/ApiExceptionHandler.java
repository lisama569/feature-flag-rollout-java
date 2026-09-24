package com.github.lisama569.featureflagrolloutjava.web;

import com.github.lisama569.featureflagrolloutjava.domain.RejectReason;
import com.github.lisama569.featureflagrolloutjava.service.ChangeRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 拒绝类异常的统一翻译：
 * <ul>
 *   <li>乐观并发类拒绝（过期/重复/无变化/来源未知）→ 409，带可区分 reason 码；</li>
 *   <li>请求体非法（JSON 结构错误、未知条目类型等）→ 400 + INVALID_PAYLOAD，
 *       与业务校验拒绝（INVALID_FLAG_PERCENTAGE 等，同样 409）区分开。</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ChangeRejectedException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> handleRejected(
            ChangeRejectedException ex) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("reason", ex.reason().name(), "message", ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> handleUnreadable(
            HttpMessageNotReadableException ex) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("reason", RejectReason.INVALID_PAYLOAD.name(),
                        "message", "请求体无法解析为变更: " + ex.getMostSpecificCause().getMessage()));
    }
}
