package com.wf.fargo.automation.standalone;

import com.mongodb.ConnectionString;
import de.flapdoodle.embed.mongo.commands.MongodArguments;
import de.flapdoodle.embed.mongo.config.Storage;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

@Configuration
@Profile({"standalone", "test"})
public class EmbeddedMongoConfig implements EnvironmentPostProcessor, Ordered {

    static final String REPLICA_SET_PROPERTY = "spring.mongodb.embedded.storage.repl-set-name";
    static final String FLAPDOODLE_REPLICA_SET_PROPERTY = "de.flapdoodle.mongodb.embedded.storage.repl-set-name";
    static final String MONGO_DATA_DIR_PROPERTY = "automation.standalone.mongo-data-dir";
    static final String FLAPDOODLE_DATABASE_DIR_PROPERTY = "de.flapdoodle.mongodb.embedded.database-dir";

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!StandaloneProfiles.active(environment)) {
            return;
        }
        Map<String, Object> overrides = new HashMap<>();
        String replicaSet = environment.getProperty(REPLICA_SET_PROPERTY);
        if (replicaSet != null && !replicaSet.isBlank()) {
            overrides.put(FLAPDOODLE_REPLICA_SET_PROPERTY, replicaSet);
        }
        String dataDir = environment.getProperty(MONGO_DATA_DIR_PROPERTY);
        if (dataDir != null && !dataDir.isBlank()) {
            overrides.put(FLAPDOODLE_DATABASE_DIR_PROPERTY, dataDir);
        }
        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("standaloneEmbeddedMongo", overrides));
        }
    }

    @Bean
    MongodArguments mongodArguments(Environment environment) {
        String replicaSet = environment.getProperty(REPLICA_SET_PROPERTY, "rs0");
        return MongodArguments.builder()
                .replication(Storage.of(replicaSet, 16))
                .useNoJournal(false)
                .build();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    MongoClientSettingsBuilderCustomizer replicaSetClient(Environment environment) {
        return builder -> {
            String uri = environment.getProperty("spring.data.mongodb.uri");
            if (uri == null || uri.isBlank()) {
                return;
            }
            String replicaSet = environment.getProperty(
                    FLAPDOODLE_REPLICA_SET_PROPERTY,
                    environment.getProperty(REPLICA_SET_PROPERTY, "rs0"));
            builder.applyConnectionString(new ConnectionString(withReplicaSet(uri, replicaSet)));
        };
    }

    static String withReplicaSet(String uri, String replicaSet) {
        String updated = uri;
        if (!updated.contains("replicaSet=")) {
            updated = updated + (updated.contains("?") ? "&" : "?") + "replicaSet=" + replicaSet;
        }
        if (!updated.contains("directConnection=")) {
            updated = updated + (updated.contains("?") ? "&" : "?") + "directConnection=false";
        }
        return updated;
    }
}
