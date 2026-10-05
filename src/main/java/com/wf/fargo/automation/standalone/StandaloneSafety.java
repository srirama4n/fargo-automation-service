package com.wf.fargo.automation.standalone;

import com.mongodb.ConnectionString;
import java.net.URI;
import java.util.Locale;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

@Profile({"standalone", "test"})
public class StandaloneSafety implements EnvironmentPostProcessor, Ordered {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 2;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!standaloneActive(environment)) {
            return;
        }
        if (anyRemote(environment, "spring.kafka.bootstrap-servers", StandaloneSafety::localBootstrap)) {
            throw new IllegalStateException(
                    "Standalone refuses a non-local Kafka bootstrap server. Use localhost or leave it unset.");
        }
        if (anyRemote(environment, "spring.data.mongodb.uri", StandaloneSafety::localMongo)) {
            throw new IllegalStateException(
                    "Standalone refuses a non-local MongoDB URI. Use localhost or leave it unset.");
        }
    }

    private static boolean anyRemote(
            ConfigurableEnvironment environment, String key, java.util.function.Predicate<String> local) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source.getName().startsWith("standalone")) {
                continue;
            }
            Object value = source.getProperty(key);
            if (value != null && !local.test(String.valueOf(value))) {
                return true;
            }
        }
        String resolved = environment.getProperty(key);
        return resolved != null && !local.test(resolved);
    }

    static boolean standaloneActive(ConfigurableEnvironment environment) {
        return profile(environment, "standalone");
    }

    static boolean profile(ConfigurableEnvironment environment, String name) {
        for (String profile : environment.getActiveProfiles()) {
            if (name.equals(profile)) {
                return true;
            }
        }
        String configured = environment.getProperty("spring.profiles.active", "");
        for (String profile : configured.split(",")) {
            if (name.equals(profile.trim())) {
                return true;
            }
        }
        return false;
    }

    static boolean localBootstrap(String servers) {
        if (servers == null || servers.isBlank()) {
            return true;
        }
        for (String server : servers.split(",")) {
            if (!localHost(hostOf(server.trim()))) {
                return false;
            }
        }
        return true;
    }

    static boolean localMongo(String uri) {
        if (uri == null || uri.isBlank()) {
            return true;
        }
        try {
            for (String host : new ConnectionString(uri).getHosts()) {
                String name = host;
                int colon = host.lastIndexOf(':');
                if (colon > 0 && host.indexOf(':') == colon) {
                    name = host.substring(0, colon);
                }
                if (!localHost(name)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException ex) {
            return localHost(hostOf(uri));
        }
    }

    private static String hostOf(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String host = value;
        if (host.contains("://")) {
            try {
                URI uri = URI.create(host);
                if (uri.getHost() != null) {
                    return uri.getHost();
                }
            } catch (IllegalArgumentException ignored) {
                host = host.substring(host.indexOf("://") + 3);
            }
        }
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end > 1 ? host.substring(1, end) : host;
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        return host;
    }

    private static boolean localHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "0.0.0.0".equals(normalized)
                || "::1".equals(normalized)
                || "https://example.net/id/garnet".equals(normalized);
    }
}
