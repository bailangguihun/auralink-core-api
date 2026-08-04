package com.auralink.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 专门处理OPTIONS预检请求的控制器
 * 当其他CORS配置无效时，此控制器可作为备用方案
 */
@Slf4j
@RestController
public class CorsOptionsController {

    /**
     * 处理所有路径的OPTIONS请求
     */
    @RequestMapping(value = "/**", method = RequestMethod.OPTIONS)
    public ResponseEntity<Void> handleOptions(HttpServletRequest request) {
        log.info("接收到OPTIONS预检请求: {}", request.getRequestURI());

        // 设置CORS响应头
        HttpHeaders headers = new HttpHeaders();
        headers.add("Access-Control-Allow-Origin", request.getHeader("Origin"));
        headers.add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        headers.add("Access-Control-Allow-Headers",
                "Authorization, Content-Type, Accept, X-Requested-With, Cache-Control");
        headers.add("Access-Control-Allow-Credentials", "true");
        headers.add("Access-Control-Max-Age", "3600");

        // 返回204状态码(No Content)，表示预检成功
        return new ResponseEntity<>(headers, HttpStatus.NO_CONTENT);
    }
}
