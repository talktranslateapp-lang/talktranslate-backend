package com.example.translationcallapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class OpenAITranslationService {

    @Value("${openai.api.key}")
    private String openaiApiKey;

    @Value("${openai.api.base.url:https://api.openai.com/v1}")
    private String openaiBaseUrl;

    @Value("${openai.model.whisper:whisper-1}")
    private String whisperModel;

    @Value("${openai.model.tts:tts-1}")
    private String ttsModel;

    @Value("${openai.model.chat:gpt-3.5-turbo}")
    private String chatModel;

    @Value("${openai.tts.voice:alloy}")
    private String ttsVoice;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CloseableHttpClient httpClient;
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);
    
    // Cache for language detection and translation optimization
    private final Map<String, String> languageCache = new HashMap<>();
    
    public OpenAITranslationService() {
        this.httpClient = HttpClients.createDefault();
        log.info("OpenAI Translation Service initialized");
    }

    /**
     * Transcribe audio using OpenAI Whisper
     */
    public String transcribeAudio(Resource audioResource) throws IOException {
        log.info("Transcribing audio resource: {}", audioResource.getFilename());
        
        try (InputStream audioStream = audioResource.getInputStream()) {
            return transcribeAudio(audioStream, audioResource.getFilename());
        }
    }

    /**
     * Transcribe audio from InputStream
     */
    public String transcribeAudio(InputStream audioStream, String filename) throws IOException {
        String url = openaiBaseUrl + "/audio/transcriptions";
        
        HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader("Authorization", "Bearer " + openaiApiKey);

        // Build multipart entity
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.addBinaryBody("file", audioStream, ContentType.APPLICATION_OCTET_STREAM, filename);
        builder.addTextBody("model", whisperModel);
        builder.addTextBody("response_format", "json");
        builder.addTextBody("language", "auto"); // Auto-detect language
        
        httpPost.setEntity(builder.build());

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            String responseBody = EntityUtils.toString(response.getEntity());
            
            if (response.getStatusLine().getStatusCode() == 200) {
                JsonNode jsonResponse = objectMapper.readTree(responseBody);
                String transcription = jsonResponse.get("text").asText();
                
                log.info("Audio transcription completed successfully");
                return transcription;
            } else {
                log.error("Transcription failed with status: {} - {}", 
                         response.getStatusLine().getStatusCode(), responseBody);
                throw new IOException("Transcription failed: " + responseBody);
            }
        }
    }

    /**
     * Translate audio to target language
     */
    public String translateAudio(Resource audioResource, String targetLanguage) throws IOException {
        log.info("Translating audio to language: {}", targetLanguage);
        
        // First transcribe the audio
        String transcription = transcribeAudio(audioResource);
        
        // Then translate the text
        return translateText(transcription, null, targetLanguage);
    }

    /**
     * Translate text using OpenAI Chat API
     */
    public String translateText(String text, String sourceLanguage, String targetLanguage) throws IOException {
        log.info("Translating text from {} to {}", sourceLanguage, targetLanguage);
        
        if (text == null || text.trim().isEmpty()) {
            return "";
        }

        String url = openaiBaseUrl + "/chat/completions";
        
        HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader("Authorization", "Bearer " + openaiApiKey);
        httpPost.setHeader("Content-Type", "application/json");

        // Build translation prompt
        String prompt = buildTranslationPrompt(text, sourceLanguage, targetLanguage);
        
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", chatModel);
        requestBody.put("messages", Arrays.asList(
            Map.of("role", "system", "content", "You are a professional translator. Translate the given text accurately while preserving the meaning and tone."),
            Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("max_tokens", 1000);
        requestBody.put("temperature", 0.3); // Lower temperature for more consistent translations

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        httpPost.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            String responseBody = EntityUtils.toString(response.getEntity());
            
            if (response.getStatusLine().getStatusCode() == 200) {
                JsonNode jsonResponse = objectMapper.readTree(responseBody);
                String translation = jsonResponse.get("choices").get(0).get("message").get("content").asText().trim();
                
                log.info("Text translation completed successfully");
                return translation;
            } else {
                log.error("Translation failed with status: {} - {}", 
                         response.getStatusLine().getStatusCode(), responseBody);
                throw new IOException("Translation failed: " + responseBody);
            }
        }
    }

    /**
     * Convert text to speech using OpenAI TTS
     */
    public CompletableFuture<String> textToSpeech(String text) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return performTextToSpeech(text);
            } catch (IOException e) {
                log.error("TTS error: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, executorService);
    }

    private String performTextToSpeech(String text) throws IOException {
        log.info("Converting text to speech: {} characters", text.length());
        
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        String url = openaiBaseUrl + "/audio/speech";
        
        HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader("Authorization", "Bearer " + openaiApiKey);
        httpPost.setHeader("Content-Type", "application/json");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", ttsModel);
        requestBody.put("input", text);
        requestBody.put("voice", ttsVoice);
        requestBody.put("response_format", "mp3");
        requestBody.put("speed", 1.0);

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        httpPost.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            if (response.getStatusLine().getStatusCode() == 200) {
                HttpEntity entity = response.getEntity();
                byte[] audioData = EntityUtils.toByteArray(entity);
                
                // Convert to base64 for transmission
                String base64Audio = Base64.getEncoder().encodeToString(audioData);
                
                log.info("TTS completed successfully, generated {} bytes", audioData.length);
                return base64Audio;
            } else {
                String responseBody = EntityUtils.toString(response.getEntity());
                log.error("TTS failed with status: {} - {}", 
                         response.getStatusLine().getStatusCode(), responseBody);
                throw new IOException("TTS failed: " + responseBody);
            }
        }
    }

    /**
     * Process audio stream for real-time translation
     */
    public CompletableFuture<String> processAudioStream(String audioBase64, String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.debug("Processing audio stream for session: {}", sessionId);
                
                // Decode base64 audio
                byte[] audioData = Base64.getDecoder().decode(audioBase64);
                
                // Create temporary input stream
                try (InputStream audioStream = new ByteArrayInputStream(audioData)) {
                    
                    // Transcribe audio
                    String transcription = transcribeAudio(audioStream, "audio_" + sessionId + ".wav");
                    
                    if (transcription == null || transcription.trim().isEmpty()) {
                        return null;
                    }
                    
                    // Detect language if not specified
                    String detectedLanguage = detectLanguage(transcription);
                    log.debug("Detected language: {} for session: {}", detectedLanguage, sessionId);
                    
                    // For now, translate to English if not English, or to Spanish if English
                    String targetLanguage = "en".equals(detectedLanguage) ? "es" : "en";
                    
                    // Translate text
                    String translation = translateText(transcription, detectedLanguage, targetLanguage);
                    
                    log.info("Audio stream processed - Original: '{}' -> Translation: '{}'", 
                            transcription, translation);
                    
                    return translation;
                }
                
            } catch (Exception e) {
                log.error("Error processing audio stream for session {}: {}", sessionId, e.getMessage(), e);
                return null;
            }
        }, executorService);
    }

    /**
     * Detect language of text using OpenAI
     */
    public String detectLanguage(String text) throws IOException {
        if (text == null || text.trim().isEmpty()) {
            return "en"; // Default to English
        }
        
        // Check cache first
        String cacheKey = text.substring(0, Math.min(50, text.length()));
        if (languageCache.containsKey(cacheKey)) {
            return languageCache.get(cacheKey);
        }
        
        String url = openaiBaseUrl + "/chat/completions";
        
        HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader("Authorization", "Bearer " + openaiApiKey);
        httpPost.setHeader("Content-Type", "application/json");

        String prompt = "Detect the language of this text and respond with only the ISO 639-1 language code (e.g., 'en', 'es', 'fr'): " + text;
        
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", chatModel);
        requestBody.put("messages", Arrays.asList(
            Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("max_tokens", 10);
        requestBody.put("temperature", 0.1);

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        httpPost.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            if (response.getStatusLine().getStatusCode() == 200) {
                String responseBody = EntityUtils.toString(response.getEntity());
                JsonNode jsonResponse = objectMapper.readTree(responseBody);
                String languageCode = jsonResponse.get("choices").get(0).get("message").get("content").asText().trim().toLowerCase();
                
                // Cache the result
                languageCache.put(cacheKey, languageCode);
                
                return languageCode;
            } else {
                log.warn("Language detection failed, defaulting to English");
                return "en";
            }
        }
    }

    /**
     * Get supported languages
     */
    public List<Map<String, String>> getSupportedLanguages() {
        List<Map<String, String>> languages = new ArrayList<>();
        
        // Common supported languages
        String[][] languageData = {
            {"en", "English"},
            {"es", "Spanish"},
            {"fr", "French"},
            {"de", "German"},
            {"it", "Italian"},
            {"pt", "Portuguese"},
            {"ru", "Russian"},
            {"ja", "Japanese"},
            {"ko", "Korean"},
            {"zh", "Chinese"},
            {"ar", "Arabic"},
            {"hi", "Hindi"},
            {"nl", "Dutch"},
            {"pl", "Polish"},
            {"sv", "Swedish"},
            {"no", "Norwegian"},
            {"da", "Danish"},
            {"fi", "Finnish"},
            {"tr", "Turkish"},
            {"he", "Hebrew"}
        };
        
        for (String[] lang : languageData) {
            Map<String, String> langMap = new HashMap<>();
            langMap.put("code", lang[0]);
            langMap.put("name", lang[1]);
            languages.add(langMap);
        }
        
        return languages;
    }

    /**
     * Health check for OpenAI API
     */
    public boolean isServiceHealthy() {
        try {
            // Simple test with minimal text
            String testResult = translateText("Hello", "en", "es");
            return testResult != null && !testResult.trim().isEmpty();
        } catch (Exception e) {
            log.error("Health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get service statistics
     */
    public Map<String, Object> getServiceStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cacheSize", languageCache.size());
        stats.put("executorActiveThreads", ((java.util.concurrent.ThreadPoolExecutor) executorService).getActiveCount());
        stats.put("supportedLanguages", getSupportedLanguages().size());
        stats.put("apiBaseUrl", openaiBaseUrl);
        stats.put("whisperModel", whisperModel);
        stats.put("chatModel", chatModel);
        stats.put("ttsModel", ttsModel);
        stats.put("healthy", isServiceHealthy());
        return stats;
    }

    private String buildTranslationPrompt(String text, String sourceLanguage, String targetLanguage) {
        StringBuilder prompt = new StringBuilder();
        
        if (sourceLanguage != null && !sourceLanguage.isEmpty()) {
            prompt.append("Translate the following text from ").append(getLanguageName(sourceLanguage))
                  .append(" to ").append(getLanguageName(targetLanguage)).append(":\n\n");
        } else {
            prompt.append("Translate the following text to ").append(getLanguageName(targetLanguage)).append(":\n\n");
        }
        
        prompt.append(text);
        
        return prompt.toString();
    }

    private String getLanguageName(String languageCode) {
        Map<String, String> languageNames = Map.of(
            "en", "English",
            "es", "Spanish", 
            "fr", "French",
            "de", "German",
            "it", "Italian",
            "pt", "Portuguese",
            "ru", "Russian",
            "ja", "Japanese",
            "ko", "Korean",
            "zh", "Chinese",
            "ar", "Arabic",
            "hi", "Hindi"
        );
        
        return languageNames.getOrDefault(languageCode, languageCode);
    }

    @PreDestroy
    public void cleanup() {
        log.info("Shutting down OpenAI Translation Service");
        
        try {
            executorService.shutdown();
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        try {
            if (httpClient != null) {
                httpClient.close();
            }
        } catch (IOException e) {
            log.error("Error closing HTTP client: {}", e.getMessage());
        }
        
        log.info("OpenAI Translation Service shutdown completed");
    }
}