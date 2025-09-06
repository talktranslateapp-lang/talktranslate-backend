package com.example.translationcallapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class OpenAITranslationService {

    @Value("${openai.api.key}")
    private String openAiApiKey;

    @Value("${openai.api.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${openai.speech.model:whisper-1}")
    private String speechModel;

    @Value("${openai.tts.model:tts-1}")
    private String ttsModel;

    @Value("${openai.tts.voice:alloy}")
    private String ttsVoice;

    @Value("${openai.translation.model:gpt-4}")
    private String translationModel;

    @Value("${openai.max.retries:3}")
    private int maxRetries;

    @Value("${openai.timeout.seconds:30}")
    private int timeoutSeconds;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    // Static language code mapping
    private static final Map<String, String> LANGUAGE_NAMES = createLanguageNameMap();

    private static Map<String, String> createLanguageNameMap() {
        Map<String, String> languageNames = new HashMap<>();
        languageNames.put("en", "English");
        languageNames.put("es", "Spanish");
        languageNames.put("fr", "French");
        languageNames.put("de", "German");
        languageNames.put("it", "Italian");
        languageNames.put("pt", "Portuguese");
        languageNames.put("ru", "Russian");
        languageNames.put("ja", "Japanese");
        languageNames.put("ko", "Korean");
        languageNames.put("zh", "Chinese");
        languageNames.put("ar", "Arabic");
        languageNames.put("hi", "Hindi");
        languageNames.put("nl", "Dutch");
        languageNames.put("sv", "Swedish");
        languageNames.put("no", "Norwegian");
        languageNames.put("da", "Danish");
        languageNames.put("fi", "Finnish");
        languageNames.put("pl", "Polish");
        languageNames.put("cs", "Czech");
        languageNames.put("hu", "Hungarian");
        languageNames.put("ro", "Romanian");
        languageNames.put("bg", "Bulgarian");
        languageNames.put("hr", "Croatian");
        languageNames.put("sk", "Slovak");
        languageNames.put("sl", "Slovenian");
        languageNames.put("et", "Estonian");
        languageNames.put("lv", "Latvian");
        languageNames.put("lt", "Lithuanian");
        languageNames.put("uk", "Ukrainian");
        languageNames.put("be", "Belarusian");
        languageNames.put("tr", "Turkish");
        languageNames.put("he", "Hebrew");
        languageNames.put("th", "Thai");
        languageNames.put("vi", "Vietnamese");
        languageNames.put("id", "Indonesian");
        languageNames.put("ms", "Malay");
        languageNames.put("tl", "Filipino");
        return languageNames;
    }

    /**
     * Convert speech to text using OpenAI Whisper API
     */
    public String speechToText(byte[] audioData, String language) {
        log.debug("Converting speech to text for language: {}", language);

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpPost request = new HttpPost(baseUrl + "/audio/transcriptions");
                request.setHeader("Authorization", "Bearer " + openAiApiKey);

                // Create multipart form data
                MultipartEntityBuilder builder = MultipartEntityBuilder.create();
                builder.addBinaryBody("file", audioData, ContentType.create("audio/wav"), "audio.wav");
                builder.addTextBody("model", speechModel);
                
                // Add language if specified
                String languageCode = normalizeLanguageCode(language);
                if (languageCode != null && !languageCode.isEmpty()) {
                    builder.addTextBody("language", languageCode);
                }

                request.setEntity(builder.build());

                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    String responseBody = EntityUtils.toString(response.getEntity());
                    
                    if (response.getCode() == 200) {
                        JsonNode jsonResponse = objectMapper.readTree(responseBody);
                        String transcription = jsonResponse.get("text").asText().trim();
                        
                        log.debug("Speech-to-text successful: {}", transcription);
                        return transcription;
                    } else {
                        log.error("OpenAI Whisper API error (attempt {}): {} - {}", 
                                attempt, response.getCode(), responseBody);
                    }
                }

            } catch (Exception e) {
                log.error("Error calling OpenAI Whisper API (attempt {}): {}", attempt, e.getMessage(), e);
                
                if (attempt == maxRetries) {
                    log.error("All speech-to-text attempts failed for language: {}", language);
                    return null;
                }
                
                // Wait before retry
                try {
                    Thread.sleep(1000 * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        return null;
    }

    /**
     * Translate text using OpenAI GPT API
     */
    public String translateText(String text, String fromLanguage, String toLanguage) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }

        log.debug("Translating text from {} to {}: {}", fromLanguage, toLanguage, text);

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpPost request = new HttpPost(baseUrl + "/chat/completions");
                request.setHeader("Authorization", "Bearer " + openAiApiKey);
                request.setHeader("Content-Type", "application/json");

                String fromLangName = getLanguageName(fromLanguage);
                String toLangName = getLanguageName(toLanguage);

                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", translationModel);
                requestBody.put("messages", new Object[]{
                    Map.of("role", "system", "content", 
                        "You are a professional translator. Translate the given text from " + 
                        fromLangName + " to " + toLangName + ". " +
                        "Provide only the translation without any additional commentary or explanation."),
                    Map.of("role", "user", "content", text)
                });
                requestBody.put("max_tokens", 1000);
                requestBody.put("temperature", 0.1);

                String jsonRequest = objectMapper.writeValueAsString(requestBody);
                request.setEntity(new StringEntity(jsonRequest, ContentType.APPLICATION_JSON));

                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    String responseBody = EntityUtils.toString(response.getEntity());
                    
                    if (response.getCode() == 200) {
                        JsonNode jsonResponse = objectMapper.readTree(responseBody);
                        String translation = jsonResponse
                            .get("choices")
                            .get(0)
                            .get("message")
                            .get("content")
                            .asText()
                            .trim();
                        
                        log.debug("Translation successful: {}", translation);
                        return translation;
                    } else {
                        log.error("OpenAI GPT API error (attempt {}): {} - {}", 
                                attempt, response.getCode(), responseBody);
                    }
                }

            } catch (Exception e) {
                log.error("Error calling OpenAI GPT API (attempt {}): {}", attempt, e.getMessage(), e);
                
                if (attempt == maxRetries) {
                    log.error("All translation attempts failed for {} -> {}: {}", 
                            fromLanguage, toLanguage, text);
                    return text; // Return original text as fallback
                }
                
                // Wait before retry
                try {
                    Thread.sleep(1000 * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return text;
                }
            }
        }

        return text;
    }

    /**
     * Convert text to speech using OpenAI TTS API
     */
    public byte[] textToSpeech(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new byte[0];
        }

        log.debug("Converting text to speech: {}", text);

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpPost request = new HttpPost(baseUrl + "/audio/speech");
                request.setHeader("Authorization", "Bearer " + openAiApiKey);
                request.setHeader("Content-Type", "application/json");

                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", ttsModel);
                requestBody.put("input", text);
                requestBody.put("voice", ttsVoice);
                requestBody.put("response_format", "wav");

                String jsonRequest = objectMapper.writeValueAsString(requestBody);
                request.setEntity(new StringEntity(jsonRequest, ContentType.APPLICATION_JSON));

                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    if (response.getCode() == 200) {
                        byte[] audioData = EntityUtils.toByteArray(response.getEntity());
                        
                        log.debug("Text-to-speech successful, generated {} bytes", audioData.length);
                        return audioData;
                    } else {
                        String responseBody = EntityUtils.toString(response.getEntity());
                        log.error("OpenAI TTS API error (attempt {}): {} - {}", 
                                attempt, response.getCode(), responseBody);
                    }
                }

            } catch (Exception e) {
                log.error("Error calling OpenAI TTS API (attempt {}): {}", attempt, e.getMessage(), e);
                
                if (attempt == maxRetries) {
                    log.error("All text-to-speech attempts failed for text: {}", text);
                    return new byte[0];
                }
                
                // Wait before retry
                try {
                    Thread.sleep(1000 * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new byte[0];
                }
            }
        }

        return new byte[0];
    }

    /**
     * Get language name from code
     */
    public String getLanguageName(String languageCode) {
        if (languageCode == null) {
            return "Unknown";
        }
        
        String normalizedCode = normalizeLanguageCode(languageCode);
        return LANGUAGE_NAMES.getOrDefault(normalizedCode, languageCode);
    }

    /**
     * Normalize language code to 2-letter ISO format
     */
    private String normalizeLanguageCode(String language) {
        if (language == null) {
            return "en";
        }
        
        String lowerLang = language.toLowerCase().trim();
        
        // Handle common language names
        switch (lowerLang) {
            case "english": return "en";
            case "spanish": return "es";
            case "french": return "fr";
            case "german": return "de";
            case "italian": return "it";
            case "portuguese": return "pt";
            case "russian": return "ru";
            case "japanese": return "ja";
            case "korean": return "ko";
            case "chinese":
            case "mandarin": return "zh";
            case "arabic": return "ar";
            case "hindi": return "hi";
            case "dutch": return "nl";
            case "swedish": return "sv";
            case "norwegian": return "no";
            case "danish": return "da";
            case "finnish": return "fi";
            case "polish": return "pl";
            case "czech": return "cs";
            case "hungarian": return "hu";
            case "romanian": return "ro";
            case "bulgarian": return "bg";
            case "croatian": return "hr";
            case "slovak": return "sk";
            case "slovenian": return "sl";
            case "estonian": return "et";
            case "latvian": return "lv";
            case "lithuanian": return "lt";
            case "ukrainian": return "uk";
            case "belarusian": return "be";
            case "turkish": return "tr";
            case "hebrew": return "he";
            case "thai": return "th";
            case "vietnamese": return "vi";
            case "indonesian": return "id";
            case "malay": return "ms";
            case "filipino": return "tl";
            default:
                // If it's already a 2-letter code, return it
                if (lowerLang.length() == 2) {
                    return lowerLang;
                }
                // Default to English
                return "en";
        }
    }

    /**
     * Test the OpenAI API connection
     */
    public boolean testConnection() {
        try {
            String testText = "Hello, world!";
            byte[] audioData = textToSpeech(testText);
            
            if (audioData != null && audioData.length > 0) {
                log.info("OpenAI API connection test successful");
                return true;
            }
        } catch (Exception e) {
            log.error("OpenAI API connection test failed: {}", e.getMessage(), e);
        }
        
        return false;
    }

    /**
     * Transcribe audio from Spring Resource (for AudioFileStorageService)
     */
    public String transcribeAudio(org.springframework.core.io.Resource audioResource) throws IOException {
        try (java.io.InputStream inputStream = audioResource.getInputStream()) {
            byte[] audioData = inputStream.readAllBytes();
            return speechToText(audioData, "auto");
        }
    }

    /**
     * Translate audio to target language (for AudioFileStorageService)
     */
    public String translateAudio(org.springframework.core.io.Resource audioResource, String targetLanguage) throws IOException {
        // First transcribe the audio
        String transcription = transcribeAudio(audioResource);
        
        if (transcription == null || transcription.trim().isEmpty()) {
            return "";
        }
        
        // Then translate the text (auto-detect source language)
        return translateText(transcription, null, targetLanguage);
    }

    /**
     * Get API usage statistics
     */
    public Map<String, Object> getApiStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("baseUrl", baseUrl);
        stats.put("speechModel", speechModel);
        stats.put("ttsModel", ttsModel);
        stats.put("translationModel", translationModel);
        stats.put("maxRetries", maxRetries);
        stats.put("timeoutSeconds", timeoutSeconds);
        stats.put("supportedLanguages", LANGUAGE_NAMES.size());
        
        return stats;
    }
}