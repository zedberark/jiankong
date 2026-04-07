package org.ops.netpulse.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.ops.netpulse.repository.SysUserRepository;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AuthTokenInterceptor implements HandlerInterceptor {

    public static final String AUTH_USER_ATTR = "auth.username";

    private final AuthTokenService authTokenService;
    private final SysUserRepository userRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        if (uri.endsWith("/auth/login") || uri.contains("/ws/")) return true;

        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            write401(response, "未登录或登录已过期");
            return false;
        }
        String token = auth.substring("Bearer ".length()).trim();
        String username = authTokenService.verifyAndGetUsername(token);
        if (username == null || username.isBlank()) {
            write401(response, "登录凭证无效，请重新登录");
            return false;
        }
        var user = userRepository.findByUsernameAndEnabledTrue(username).orElse(null);
        if (user == null) {
            write401(response, "账号不存在或已禁用");
            return false;
        }
        String headerUser = request.getHeader("X-User-Name");
        if (headerUser != null && !headerUser.isBlank() && !username.equals(headerUser.trim())) {
            write401(response, "请求身份不一致");
            return false;
        }
        request.setAttribute(AUTH_USER_ATTR, username);
        return true;
    }

    private void write401(HttpServletResponse response, String msg) throws Exception {
        response.setStatus(401);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"success\":false,\"message\":\"" + msg + "\"}");
    }
}
