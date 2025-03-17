package com.hmdp.config;

import io.lettuce.core.RedisClient;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author:guoyaqi
 * @Date: 2025/3/13 0:25
 */

@Configuration
public class RedisConfig {

    @Bean
    public RedissonClient redissonClient(){
        Config config= new Config();

        config.useSingleServer().setAddress("redis://127.0.0.1:6379").setPassword("");

        return Redisson.create(config);
    }


}
