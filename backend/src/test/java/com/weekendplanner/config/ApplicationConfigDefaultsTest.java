package com.weekendplanner.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationConfigDefaultsTest {

    @Test
    void understandingDefaultsUseFastModelAndPracticalTimeouts() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties)
                .containsEntry("spring.ai.openai.chat.options.model", "${DEEPSEEK_MODEL:deepseek-chat}")
                .containsEntry("agent.understanding.chat-timeout-ms", 8000)
                .containsEntry("agent.understanding.initial-timeout-ms", 12000);
    }
}
