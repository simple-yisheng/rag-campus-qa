package com.rag.campus.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 向量数据库客户端配置
 * <p>
 * 仅当 {@code rag.vector.store=milvus} 时加载。
 * 连接 Milvus standalone，默认端口 19530。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "rag.vector.store", havingValue = "milvus")
public class MilvusConfig {

    @Value("${milvus.host:localhost}")
    private String host;

    @Value("${milvus.port:19530}")
    private int port;

    @Value("${milvus.database:default}")
    private String database;

    /**
     * Milvus gRPC 客户端
     * <p>
     * 生产环境建议配置连接池参数（maxIdleConnections、keepAlive 等），
     * 避免频繁创建/销毁 gRPC 连接。
     */
    @Bean
    public MilvusServiceClient milvusServiceClient() {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .withDatabaseName(database)
                .build();

        MilvusServiceClient client = new MilvusServiceClient(connectParam);
        log.info("Milvus 客户端已连接: {}:{}/{}", host, port, database);
        return client;
    }
}
