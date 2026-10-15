package com.smt.meeting.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端（T2.6）：仅用于分布式锁。
 *
 * 用核心库而非 redisson-spring-boot-starter——starter 会接管 Spring Data Redis 的连接工厂，
 * 影响现有 StringRedisTemplate（Lettuce）的行为。职责隔离：分布式锁走 Redisson，其余 Redis 操作走 RedisTemplate。
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}
