package org.ops.netpulse.controller;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 简易健康检查：GET /api/health 返回 200，用于 curl 或负载均衡探活。注意：完整 URL 必须包含 /api，例如 http://192.168.1.160:8080/api/health */
@RestController
@RequestMapping("")
@CrossOrigin
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "监控运维系统");
    }
}
