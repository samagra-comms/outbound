package com.uci.outbound.Application;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import reactor.core.publisher.Flux;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Configuration
@Slf4j
public class ReactiveKafkaConfiguration {

    @Value("${spring.kafka.bootstrap-servers}")
    private String BOOTSTRAP_SERVERS;

    private String GROUP_ID = "outbound";

    @Bean
    Map<String, Object> kafkaConsumerConfiguration() {
        Map<String, Object> configuration = new HashMap<>();
        configuration.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        configuration.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
        configuration.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configuration.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configuration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        return configuration;
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        return new DefaultKafkaConsumerFactory<>(kafkaConsumerConfiguration(), new StringDeserializer(),
                new StringDeserializer());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String>
    kafkaListenerContainerFactory()  {
        ConcurrentKafkaListenerContainerFactory<String, String> factory
                = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setErrorHandler(((exception, data) -> {
            /*
             * here you can do you custom handling, I am just logging it same as default
             * Error handler does If you just want to log. you need not configure the error
             * handler here. The default handler does it for you. Generally, you will
             * persist the failed records to DB for tracking the failed records.
             */
            log.error("Error in process with Exception {} and the record is {}", exception, data);
        }));


        return factory;
    }

    @Bean
    ReceiverOptions<String, String> kafkaReceiverOptions(@Value("${outbound}") String[] inTopicName) {
        ReceiverOptions<String, String> options = ReceiverOptions.create(kafkaConsumerConfiguration());
        return options.subscription(Arrays.asList(inTopicName))
                .withKeyDeserializer(new StringDeserializer())
                .withValueDeserializer(new StringDeserializer());
    }

    @Bean
    Flux<ReceiverRecord<String, String>> reactiveKafkaReceiver(ReceiverOptions<String, String> kafkaReceiverOptions) {
        return KafkaReceiver.create(kafkaReceiverOptions).receive();
    }
}