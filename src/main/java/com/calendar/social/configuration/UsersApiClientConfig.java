package com.calendar.social.configuration;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/** Client for wely-users' deleted-accounts listing, sending DELETED_USERS_READER_SECRET and nothing else. */
@Configuration
public class UsersApiClientConfig {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    public static final String SECRET_HEADER = "X-Internal-Secret";

    @Bean
    public WebClient usersWebClient(@Value("${app.users-api.url:}") String url,
                                    @Value("${app.users-api.deleted-users-secret:}") String secret) {
        return webClient(url, secret, TIMEOUT);
    }

    /** Takes the timeout, so a test need not wait ten seconds. */
    static WebClient webClient(String url, String secret, Duration timeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) timeout.toMillis())
                .responseTimeout(timeout);
        return WebClient.builder()
                .baseUrl(url)
                .defaultHeader(SECRET_HEADER, secret)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
