package org.ops.netpulse.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 1）反向代理把 /api 剥掉时，URI 可能为 /devices/...、/inspection/...，而应用挂在 context-path=/api 下，需 forward 进当前 ServletContext。
 * 2）未配置 context-path 但前端仍请求 /api/** 时，需 forward 去掉 /api 前缀，否则会报 “No static resource inspection/run” 等。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiPathForwardFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String cp = request.getContextPath();
        if (cp == null) {
            cp = "";
        }
        /*
         * 未配置 server.servlet.context-path（为空）时，控制器映射在 /inspection、/devices 等根路径下，
         * 而前端 axios baseURL 固定为 /api，实际请求为 /api/inspection/run，无法命中 @RequestMapping("/inspection")，
         * 会落到 ResourceHttpRequestHandler 并报 “No static resource inspection/run”。
         * 将 /api/** 转发到 /**（同一次请求内），与带 context-path=/api 时的效果一致。
         */
        if (cp.isEmpty() && (uri.startsWith("/api/") || "/api".equals(uri))) {
            String path = "/api".equals(uri) ? "/" : uri.substring("/api".length());
            if (path.isEmpty()) {
                path = "/";
            }
            request.getServletContext().getRequestDispatcher(path).forward(request, response);
            return;
        }
        // 已有 /api 前缀（或其它 context）的正常请求
        if (!cp.isEmpty() && uri.startsWith(cp + "/")) {
            filterChain.doFilter(request, response);
            return;
        }
        // 仅在有非空 context-path 时补进应用：/ai/**、/devices/**、/inspection/**（手动巡检与报告列表）
        if (!cp.isEmpty()
                && (uri.equals("/devices") || uri.startsWith("/devices/")
                || uri.equals("/ai") || uri.startsWith("/ai/")
                || uri.equals("/inspection") || uri.startsWith("/inspection/"))) {
            request.getServletContext().getRequestDispatcher(uri).forward(request, response);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
