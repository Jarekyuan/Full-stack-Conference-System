package com.smt.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关鉴权配置（application.yml 的 security.* 段）
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "security")
public class AuthProperties {

    /** 免鉴权路径（Ant 风格，见 docs/04 接口设计 1.1 白名单约定） */
    private List<String> whitelist = new ArrayList<>();

    private final Jwt jwt = new Jwt();

    @Getter
    @Setter
    public static class Jwt {

        /** JWT 签名密钥（HS256 要求 ≥32 字节；开发用 yml 默认值，生产用环境变量 JWT_SECRET 注入） */
        private String secret;
    }
}
