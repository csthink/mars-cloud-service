package com.mars.cloud.service.upms.infrastructure.registry;

import com.mars.cloud.service.upms.domain.policy.CapabilityItem;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class RegistryFixtureLoader {

    public RegistryDefinition load(Resource resource) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(resource);
        Properties properties = factory.getObject();
        if (properties == null) {
            throw new RegistryValidationException("registry fixture is empty");
        }
        return new RegistryDefinition(
                require(properties, "platform"),
                Integer.parseInt(require(properties, "caps_ver")),
                require(properties, "content_hash"),
                require(properties, "syntax"),
                indexedValues(properties, "actions"),
                indexedResources(properties)
        );
    }

    private static List<String> indexedValues(Properties properties, String key) {
        List<String> values = new ArrayList<>();
        for (int index = 0; properties.containsKey(key + "[" + index + "]"); index++) {
            values.add(require(properties, key + "[" + index + "]"));
        }
        return List.copyOf(values);
    }

    private static List<CapabilityItem> indexedResources(Properties properties) {
        List<CapabilityItem> values = new ArrayList<>();
        for (int index = 0; properties.containsKey("resources[" + index + "].id"); index++) {
            String prefix = "resources[" + index + "]";
            String id = require(properties, prefix + ".id");
            String sensitivity = properties.getProperty(prefix + ".sensitivity");
            if (sensitivity != null && !"sensitive".equals(sensitivity)) {
                throw new RegistryValidationException("unsupported registry sensitivity: " + sensitivity);
            }
            boolean sensitive = "sensitive".equals(sensitivity);
            boolean deprecated = Boolean.parseBoolean(properties.getProperty(prefix + ".deprecated", "false"));
            values.add(new CapabilityItem(id, sensitive, deprecated));
        }
        return List.copyOf(values);
    }

    private static String require(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new RegistryValidationException("missing registry field: " + key);
        }
        return value;
    }
}
