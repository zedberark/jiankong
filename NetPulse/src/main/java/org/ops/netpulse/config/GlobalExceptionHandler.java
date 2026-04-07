package org.ops.netpulse.config;

import com.fasterxml.jackson.databind.JsonMappingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理：请求体解析失败返回 400 + message；其余未捕获异常返回 500 + message，
 * 便于前端统一展示错误信息。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** 请求体 JSON 格式错误或字段类型不匹配时返回 400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("Request body invalid: {}", e.getMessage());
        String msg = "请求数据格式错误";
        if (e.getCause() instanceof JsonMappingException jme && jme.getCause() != null) {
            msg = msg + "：" + jme.getCause().getMessage();
        } else if (e.getMessage() != null && e.getMessage().length() < 120) {
            msg = msg + "：" + e.getMessage();
        }
        return ResponseEntity.badRequest().body(Map.of("message", msg));
    }

    /** Redis 不可达/认证失败时返回 503，避免前端看到底层英文异常 */
    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<Map<String, String>> handleRedisConnectionFailure(RedisConnectionFailureException e) {
        log.warn("Redis connection failure: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "Redis 不可用，请检查 REDIS_HOST/REDIS_PORT/REDIS_PASSWORD 配置或 Redis 服务状态"));
    }

    /** 巡检报告等表未迁移、字段缺失时常见；先于 Exception 以更友好提示 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDataAccess(DataAccessException e) {
        log.error("Data access error", e);
        String hint = "数据库访问失败，请确认已执行巡检相关 Flyway 迁移（含 V20：inspection_report.ai_summary）。"
                + "若曾手动 DROP 过 ai_summary，请执行：ALTER TABLE inspection_report ADD COLUMN ai_summary LONGTEXT NULL COMMENT 'AI 巡检分析结论' AFTER schedule_label；"
                + "或重启应用以执行 V22 自动补列。";
        Throwable root = e;
        while (root.getCause() != null) root = root.getCause();
        String rm = root.getMessage() != null ? root.getMessage() : "";
        if (rm.contains("ai_summary") || rm.contains("Unknown column")) {
            hint = "数据库缺少字段（常见于曾删除 inspection_report.ai_summary）：请执行迁移 V22 或手动 ADD COLUMN ai_summary（LONGTEXT，见 V20/V22 SQL），然后重启后端。";
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", hint));
    }

    /** 其他未捕获异常统一返回 500，消息截断至 200 字 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleException(Exception e) {
        log.error("Unhandled exception", e);
        String msg = e.getMessage() != null ? e.getMessage() : "服务器内部错误";
        if (msg.length() > 200) msg = msg.substring(0, 200) + "...";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "操作失败：" + msg));
    }
}
