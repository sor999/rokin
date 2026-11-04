package com.robot.fleet.global.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    @org.springframework.context.annotation.Primary
    public ProducerFactory<String, String> producerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.RETRIES_CONFIG, 3,
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true
        ));
    }

    @Bean
    @org.springframework.context.annotation.Primary
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="command.durable-enabled",havingValue="true")
    public ProducerFactory<String,String> outboxProducerFactory() {
        var config=new java.util.HashMap<String,Object>(producerFactory().getConfigurationProperties());
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,500);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,1000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,2000);
        return new DefaultKafkaProducerFactory<>(config);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="command.durable-enabled",havingValue="true")
    public KafkaTemplate<String,String> outboxKafkaTemplate() { return new KafkaTemplate<>(outboxProducerFactory()); }
}
