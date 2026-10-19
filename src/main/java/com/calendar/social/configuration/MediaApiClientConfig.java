package com.calendar.social.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** Client for wely-media, authenticated by this service's own internal secret. */
@Configuration
public class MediaApiClientConfig {

    @Bean
    public WebClient mediaWebClient(@Value("${app.media-api.url:}") String url,
                                    @Value("${app.media-api.secret:}") String secret) {
        return UsersApiClientConfig.webClient(url, secret, UsersApiClientConfig.TIMEOUT);
    }
}
