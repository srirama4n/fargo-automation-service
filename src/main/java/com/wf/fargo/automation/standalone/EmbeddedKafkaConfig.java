package com.wf.fargo.automation.standalone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

@Configuration
@Profile({"standalone", "test"})
public class EmbeddedKafkaConfig implements EnvironmentPostProcessor, Ordered {

    private static final List<String> TOPIC_PROPERTIES = List.of(
            "automation.topics.trigger-fired",
            "automation.topics.automation-replies",
            "automation.topics.agent-control",
            "automation.topics.baseline-updates",
            "automation.topics.orchestrator-input");

    private static final List<String> TOPIC_DEFAULTS = List.of(
            "trigger-fired",
            "automation-replies",
            "agent-control",
            "baseline-updates",
            "fargo-orchestrator-input");

    private static volatile EmbeddedKafkaKraftBroker broker;
    private static final AtomicBoolean shutdownRegistered = new AtomicBoolean();

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!StandaloneProfiles.active(environment)) {
            return;
        }
        EmbeddedKafkaKraftBroker started = start(environment);
        String brokers = started.getBrokersAsString();
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("spring.kafka.bootstrap-servers", brokers);
        overrides.put("spring.kafka.bootstrap-servers[0]", brokers);
        overrides.put("spring.kafka.producer.bootstrap-servers", brokers);
        overrides.put("spring.kafka.producer.bootstrap-servers[0]", brokers);
        environment.getPropertySources().addFirst(new MapPropertySource("standaloneEmbeddedKafka", overrides));
    }

    @Bean(destroyMethod = "")
    EmbeddedKafkaKraftBroker embeddedKafkaKraftBroker(ConfigurableEnvironment environment) {
        return start(environment);
    }

    @Bean
    @Primary
    KafkaTemplate<String, String> embeddedKafkaTemplate(
            EmbeddedKafkaKraftBroker broker,
            KafkaProperties kafkaProperties,
            @Autowired(required = false) SslBundles sslBundles) {
        Map<String, Object> producerProps = new HashMap<>(kafkaProperties.buildProducerProperties(sslBundles));
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
    }

    @Bean
    @Primary
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            EmbeddedKafkaKraftBroker broker,
            KafkaProperties kafkaProperties,
            @Autowired(required = false) SslBundles sslBundles,
            @Autowired(required = false) CommonErrorHandler errorHandler) {
        Map<String, Object> consumerProps = new HashMap<>(kafkaProperties.buildConsumerProperties(sslBundles));
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumerProps));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setAutoStartup(kafkaProperties.getListener().isAutoStartup());
        if (errorHandler != null) {
            factory.setCommonErrorHandler(errorHandler);
        }
        return factory;
    }

    static synchronized EmbeddedKafkaKraftBroker start(ConfigurableEnvironment environment) {
        if (broker == null) {
            EmbeddedKafkaKraftBroker created = new EmbeddedKafkaKraftBroker(1, 1, topicNames(environment));
            created.adminTimeout(60);
            created.afterPropertiesSet();
            broker = created;
            if (shutdownRegistered.compareAndSet(false, true)) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    EmbeddedKafkaKraftBroker running = broker;
                    if (running != null) {
                        running.destroy();
                    }
                }, "embedded-kafka-shutdown"));
            }
        }
        return broker;
    }

    static String[] topicNames(ConfigurableEnvironment environment) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < TOPIC_PROPERTIES.size(); i++) {
            String topic = environment.getProperty(TOPIC_PROPERTIES.get(i), TOPIC_DEFAULTS.get(i));
            names.add(topic);
            names.add(topic + ".dlt");
        }
        return names.toArray(String[]::new);
    }
}
