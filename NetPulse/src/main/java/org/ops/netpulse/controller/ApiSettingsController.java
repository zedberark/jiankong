package org.ops.netpulse.controller;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.service.AuditService;
import org.ops.netpulse.service.SystemConfigService;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 千问、DeepSeek 等 API 配置的专用接口 */
@RestController
@RequestMapping("/system/api-settings")
@RequiredArgsConstructor
@CrossOrigin
public class ApiSettingsController {

    private final SystemConfigService configService;
    private final AuditService auditService;

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> m = new HashMap<>();
        m.put("qwen", Map.of(
                "enabled", configService.getValue("api.qwen.enabled").orElse("0"),
                "endpoint", configService.getValue("api.qwen.endpoint").orElse(""),
                "key", mask(configService.getValue("api.qwen.key").orElse(""))
        ));
        m.put("deepseek", Map.of(
                "enabled", configService.getValue("api.deepseek.enabled").orElse("0"),
                "endpoint", configService.getValue("api.deepseek.endpoint").orElse(""),
                "key", mask(configService.getValue("api.deepseek.key").orElse(""))
        ));
        return m;
    }

    @PostMapping
    public void save(@RequestBody ApiSettingsDto dto) {
        if (dto.getQwen() != null) {
            configService.saveByKey("api.qwen.enabled", dto.getQwen().getOrDefault("enabled", "0"));
            String qEndpoint = normalizeEndpoint(dto.getQwen().getOrDefault("endpoint", ""), "qwen");
            configService.saveByKey("api.qwen.endpoint", qEndpoint);
            String qk = dto.getQwen().get("key");
            if (qk != null && !qk.isEmpty() && !qk.contains("***"))
                configService.saveByKey("api.qwen.key", qk);
        }
        if (dto.getDeepseek() != null) {
            configService.saveByKey("api.deepseek.enabled", dto.getDeepseek().getOrDefault("enabled", "0"));
            String dEndpoint = normalizeEndpoint(dto.getDeepseek().getOrDefault("endpoint", ""), "deepseek");
            configService.saveByKey("api.deepseek.endpoint", dEndpoint);
            String dk = dto.getDeepseek().get("key");
            if (dk != null && !dk.isEmpty() && !dk.contains("***"))
                configService.saveByKey("api.deepseek.key", dk);
        }
        auditService.log("SAVE_API_SETTINGS", "system_config", null, "qwen/deepseek");
    }

    /** 一键检测第三方 AI API 连通性与鉴权状态 */
    @GetMapping("/health-check")
    public Map<String, Object> healthCheck() {
        String qEndpoint = normalizeEndpoint(
                configService.getValue("api.qwen.endpoint").orElse("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"),
                "qwen");
        String dEndpoint = normalizeEndpoint(
                configService.getValue("api.deepseek.endpoint").orElse("https://api.deepseek.com/v1/chat/completions"),
                "deepseek");
        Map<String, Object> out = new HashMap<>();
        out.put("qwen", checkProvider(
                "qwen-turbo",
                configService.getValue("api.qwen.enabled").orElse("0"),
                qEndpoint,
                configService.getValue("api.qwen.key").orElse("")
        ));
        out.put("deepseek", checkProvider(
                "deepseek-chat",
                configService.getValue("api.deepseek.enabled").orElse("0"),
                dEndpoint,
                configService.getValue("api.deepseek.key").orElse("")
        ));
        return out;
    }

    private Map<String, Object> checkProvider(String model, String enabled, String endpoint, String key) {
        Map<String, Object> res = new HashMap<>();
        res.put("enabled", enabled);
        res.put("endpoint", endpoint);
        if (!"1".equals(enabled)) {
            res.put("ok", false);
            res.put("status", "disabled");
            res.put("message", "未启用");
            return res;
        }
        if (key == null || key.isBlank()) {
            res.put("ok", false);
            res.put("status", "missing_key");
            res.put("message", "未配置 API Key");
            return res;
        }
        try {
            long begin = System.currentTimeMillis();
            RestTemplate restTemplate = buildTimeoutRestTemplate(6000, 12000);
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", List.of(Map.of("role", "user", "content", "ping")));
            body.put("max_tokens", 8);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(key);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(endpoint, HttpMethod.POST, entity, String.class);
            long cost = System.currentTimeMillis() - begin;
            if (response.getStatusCode().is2xxSuccessful()) {
                res.put("ok", true);
                res.put("status", "ok");
                res.put("message", "连接正常");
                res.put("latencyMs", cost);
            } else {
                res.put("ok", false);
                res.put("status", "http_error");
                res.put("message", "HTTP " + response.getStatusCode().value());
                res.put("latencyMs", cost);
            }
            return res;
        } catch (RestClientResponseException e) {
            int code = e.getRawStatusCode();
            res.put("ok", false);
            if (code == 401) {
                res.put("status", "unauthorized");
                res.put("message", "401 未授权，API Key 无效或已过期");
            } else if (code == 404) {
                res.put("status", "not_found");
                res.put("message", "404 接口不存在，请检查 endpoint 完整路径");
            } else {
                res.put("status", "http_error");
                res.put("message", "HTTP " + code);
            }
            return res;
        } catch (ResourceAccessException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            res.put("ok", false);
            if (msg.contains("timed out")) {
                res.put("status", "timeout");
                res.put("message", "连接超时，请检查服务器出网/防火墙");
            } else {
                res.put("status", "network_error");
                res.put("message", "网络异常，请检查 DNS/代理/防火墙");
            }
            return res;
        } catch (Exception e) {
            res.put("ok", false);
            res.put("status", "error");
            res.put("message", "检测失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
            return res;
        }
    }

    private RestTemplate buildTimeoutRestTemplate(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    private String normalizeEndpoint(String raw, String provider) {
        String endpoint = raw == null ? "" : raw.trim();
        if (endpoint.isEmpty()) return endpoint;
        String lower = endpoint.toLowerCase();
        if (lower.contains("/chat/completions")) return endpoint;
        if ("qwen".equals(provider)) {
            if (lower.endsWith("/v1")) return endpoint + "/chat/completions";
            if (lower.endsWith("/compatible-mode")) return endpoint + "/v1/chat/completions";
            return endpoint + "/compatible-mode/v1/chat/completions";
        }
        if ("deepseek".equals(provider)) {
            if (lower.endsWith("/v1")) return endpoint + "/chat/completions";
            return endpoint + "/v1/chat/completions";
        }
        return endpoint;
    }

    private static String mask(String s) {
        if (s == null || s.length() <= 8) return s != null && !s.isEmpty() ? "***" : "";
        return s.substring(0, 4) + "***" + s.substring(s.length() - 2);
    }

    @lombok.Data
    public static class ApiSettingsDto {
        private Map<String, String> qwen;
        private Map<String, String> deepseek;
    }
}
