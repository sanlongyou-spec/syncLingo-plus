package com.si.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "azure.translator")
public class AzureTranslatorProperties {

    private String key;
    private String region;
    private String endpoint = "https://api.cognitive.microsofttranslator.com";
    private int timeoutSeconds = 30;
}
