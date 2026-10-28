package com.example.hotel.booking.config;

import com.example.hotel.booking.properties.HotelClientProperties;

import org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

import tools.jackson.databind.json.JsonMapper;

import static java.time.Duration.ofMillis;

@Configuration(proxyBeanMethods = false)
class HotelClientConfig {

    @Bean
    @Primary
    private static RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @LoadBalanced
    private static RestClient.Builder hotelRestClientBuilder(JsonMapper mapper, RestClientBuilderConfigurer configurer) {
        return configurer.configure(RestClient.builder())
            .baseUrl("http://hotel-service")
            .configureMessageConverters(converters -> converters
                .withJsonConverter(new JacksonJsonHttpMessageConverter(mapper)));
    }

    @Bean
    private static HttpClient hotelHttpClient(HotelClientProperties properties) {
        return HttpClient.newBuilder()
            .connectTimeout(ofMillis(properties.connectTimeoutMs()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }
}
