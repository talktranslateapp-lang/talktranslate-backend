package com.example.translationcallapp;

import com.example.translationcallapp.config.AudioStorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for the Translation Call App
 * Enables real-time voice translation during phone calls using Twilio and OpenAI APIs
 */
@SpringBootApplication
@EnableConfigurationProperties(AudioStorageProperties.class)
@EnableCaching
@EnableAsync
@EnableScheduling
public class TranslationCallAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(TranslationCallAppApplication.class, args);
    }
}