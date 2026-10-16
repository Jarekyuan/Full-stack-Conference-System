package com.smt.meeting.config;

import java.time.Duration;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Spring Cache 配置（T4.7）：会议室列表/详情用**注解式**缓存（{@code @Cacheable}/{@code @CacheEvict}）。
 *
 * <p>日历缓存**不走这里**——它需要 key 里带周次、随机 TTL、空值短 TTL、互斥重建，注解表达不了，
 * 所以在 {@code AvailabilityService} 里手写（两套都写过，才知道边界在哪，这也是面试会问的点）。
 *
 * <h3>序列化为什么不用默认的 JDK 序列化</h3>
 * Spring Data Redis 默认用 {@code JdkSerializationRedisSerializer}：redis-cli 里看到的是一串二进制，
 * 排障只能靠猜；而且它把**类名 + serialVersionUID** 写进字节流——DTO 改个字段就可能读不出旧缓存
 * （表现为一堆莫名的反序列化异常）。JSON 至少肉眼可读，字段增减也宽容。
 *
 * <h3>为什么敢开类型信息（`@class`）</h3>
 * {@code GenericJackson2JsonRedisSerializer} 靠 JSON 里的 {@code @class} 还原对象类型——
 * 这本身是个**反序列化面**（能写缓存的人就能让应用实例化任意类）。
 * 这里用 {@link BasicPolymorphicTypeValidator} 把可实例化类型**白名单**限制在
 * 本项目 DTO（{@code com.smt.}）+ 集合/时间类型，而不是放行 {@code Object} 的全部子类；
 * 加上 Redis 已 requirepass 且只绑回环（评审 C1），缓存内容也只有本服务会写。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** 会议室缓存的存活时间（docs/02 6.4）：会议室是**极低频变更**的数据，30 分钟足够 */
    public static final Duration ROOM_TTL = Duration.ofMinutes(30);

    public static final String ROOM_LIST_CACHE = "room:list";
    public static final String ROOM_DETAIL_CACHE = "room:detail";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ROOM_TTL)
                // key 用可读字符串（默认也是 String 序列化，这里显式写出来免得后人误改）
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(cacheObjectMapper())));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base)
                .withCacheConfiguration(ROOM_LIST_CACHE, base)
                .withCacheConfiguration(ROOM_DETAIL_CACHE, base)
                .build();
    }

    /**
     * 缓存专用 ObjectMapper：与 Web 层那个（common 配的 `yyyy-MM-dd HH:mm:ss`）**分开**，
     * 避免为了缓存去动接口的序列化行为。
     */
    private ObjectMapper cacheObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 白名单式默认类型信息：只允许本项目的类 + 集合/时间等基础类型被实例化
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.smt.")
                        .allowIfSubType("java.util.")
                        .allowIfSubType("java.time.")
                        .allowIfSubType("java.lang.")
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        return mapper;
    }
}
