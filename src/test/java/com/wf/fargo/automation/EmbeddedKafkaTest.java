package com.wf.fargo.automation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;

public abstract class EmbeddedKafkaTest extends EmbeddedMongoTest {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @BeforeEach
    void cleanTopics() throws Exception {
        try (AdminClient admin = AdminClient.create(adminConfig())) {
            Set<String> names = admin.listTopics().names().get(30, TimeUnit.SECONDS);
            List<String> topics = names.stream().filter(name -> !name.startsWith("_")).toList();
            if (topics.isEmpty()) {
                return;
            }
            Map<String, TopicDescription> described = admin.describeTopics(topics).allTopicNames().get(30, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            for (TopicDescription description : described.values()) {
                description.partitions().forEach(partition ->
                        latest.put(new TopicPartition(description.name(), partition.partition()), OffsetSpec.latest()));
            }
            Map<TopicPartition, org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo> ends =
                    admin.listOffsets(latest).all().get(30, TimeUnit.SECONDS);
            Map<TopicPartition, RecordsToDelete> toDelete = new HashMap<>();
            ends.forEach((partition, info) -> {
                if (info.offset() > 0) {
                    toDelete.put(partition, RecordsToDelete.beforeOffset(info.offset()));
                }
            });
            if (!toDelete.isEmpty()) {
                admin.deleteRecords(toDelete).all().get(30, TimeUnit.SECONDS);
            }
        }
    }

    protected Properties adminConfig() {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return properties;
    }
}
