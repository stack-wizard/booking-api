package com.stackwizard.booking_api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
public class S3StorageConfig {

    @Bean
    public S3Client s3Client(MediaS3Properties mediaS3Properties) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(mediaS3Properties.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(mediaS3Properties.isPathStyleAccess())
                        .build());

        if (StringUtils.hasText(mediaS3Properties.getEndpoint())) {
            builder.endpointOverride(URI.create(mediaS3Properties.getEndpoint()));
        }

        if (StringUtils.hasText(mediaS3Properties.getAccessKey()) && StringUtils.hasText(mediaS3Properties.getSecretKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(mediaS3Properties.getAccessKey(), mediaS3Properties.getSecretKey())
            ));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        return builder.build();
    }
}
