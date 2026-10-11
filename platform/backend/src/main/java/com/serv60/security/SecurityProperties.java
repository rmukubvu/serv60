package com.serv60.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "serv60.security")
public record SecurityProperties(boolean enabled) {}
