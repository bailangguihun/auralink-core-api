package com.auralink.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 自定义CORS过滤器，确保预检请求能被正确处理
 * 此过滤器执行顺序最高，在所有其他过滤器之前执行
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CustomCorsFilter extends OncePerRequestFilter {

    // 允许的域名列表
    private static final Set<String> ALLOWED_ORIGINS = new HashSet<>(Arrays.asList(
        "https://fanhualy.top",
        "http://fanhualy.top",
        "https://api.fanhualy.top",
        "http://api.fanhualy.top",
        "http://localhost:3000",
        "https://localhost:3000"
    ));

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        log.debug("CORS过滤器处理请求: {} {}", request.getMethod(), request.getRequestURI());

        // 获取请求的源
        String origin = request.getHeader("Origin");

        // 如果有Origin头，添加CORS响应头
        if (origin != null) {
            // 检查是否是允许的域名，或者是否为子域名
            if (ALLOWED_ORIGINS.contains(origin) ||
                origin.endsWith(".fanhualy.top")) {

                // 设置CORS头
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, HEAD, PATCH");
                response.setHeader("Access-Control-Allow-Headers",
                        "Origin, Content-Type, Accept, Authorization, X-Requested-With, Cache-Control");
                response.setHeader("Access-Control-Expose-Headers",
                        "Access-Control-Allow-Origin, Access-Control-Allow-Methods, Access-Control-Allow-Headers, " +
                        "Access-Control-Max-Age, Access-Control-Allow-Credentials, Authorization, Content-Disposition");
                response.setHeader("Access-Control-Allow-Credentials", "true");
                response.setHeader("Access-Control-Max-Age", "3600");

                // 对OPTIONS请求直接返回OK
                if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                    log.info("预检请求被CORS过滤器处理: {}", request.getRequestURI());
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }
            } else {
                log.warn("请求来自未授权的源: {}", origin);
            }
        }

        filterChain.doFilter(request, response);
    }
}
