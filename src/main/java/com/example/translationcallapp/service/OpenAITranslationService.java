package com.example.translationcallapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PreDestroy;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class OpenAITranslationService {

    @Value("${openai.api.key}")
    private String openaiApiKey;

    @Value("${openai.whisper.model:whisper-1}")
    private String whisperModel;

    @Value("${openai.chat.model:gpt-4}")
    private String chatModel;

    @Value("${openai.chat.max-tokens:150}")
    private int maxTokens;

    @Value("${openai.tts.model:tts-1}")
    private String ttsModel;

    @Value("${openai.tts.voice:alloy}")
    private String ttsVoice;

    @Autowired
    private AudioFormatConversionService audioConversionService;

    @Autowired
    private BotParticipantService botParticipantService;

    @Autowired
    private AudioFileStorageService audioFileStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    // Store active translation sessions
    private final ConcurrentHashMap<String, TranslationSession> translationSessions = new ConcurrentHashMap<>();

    /**
     * Start bidirectional translation for a conference
     */
    public boolean startTranslation(String conferenceName, String sourceLanguage, String targetLanguage) {
        try {
            log.info("Starting translation for conference: {}, {} -> {}", 
                    conferenceName, sourceLanguage, targetLanguage);

            TranslationSession session = new TranslationSession(conferenceName, sourceLanguage, targetLanguage);
            translationSessions.put(conferenceName, session);

            log.info("Translation session started for conference: {}", conferenceName);
            return true;

        } catch (Exception e) {
            log.error("Failed to start translation for conference: {}", conferenceName, e);
            return false;
        }
    }

    /**
     * Process audio from a conference participant using standard OpenAI API
     */
    public void processAudio(String conferenceName, String participant, String base64Audio) {
        try {
            TranslationSession session = translationSessions.get(conferenceName);
            if (session == null) {
                log.warn("No translation session found for conference: {}", conferenceName);
                return;
            }

            // Step 1: Convert Twilio audio to WAV format for Whisper
            String wavAudio = audioConversionService.convertTwilioToWav(base64Audio);
            if (wavAudio == null) {
                log.warn("Failed to convert audio format for conference: {}", conferenceName);
                return;
            }

            // Step 2: Transcribe audio using Whisper
            String transcription = transcribeAudio(wavAudio, session.getSourceLanguage());
            if (transcription == null || transcription.trim().isEmpty()) {
                log.debug("No transcription available for conference: {}", conferenceName);
                return;
            }

            log.debug("Transcribed text: {}", transcription);

            // Step 3: Translate text using GPT-4
            String translation = translateText(transcription, session.getSourceLanguage(), session.getTargetLanguage());
            if (translation == null || translation.trim().isEmpty()) {
                log.warn("Translation failed for conference: {}", conferenceName);
                return;
            }

            log.debug("Translated text: {}", translation);

            // Step 4: Convert text to speech using TTS
            String audioBase64 = textToSpeech(translation);
            if (audioBase64 == null) {
                log.warn("TTS failed for conference: {}", conferenceName);
                return;
            }

            // Step 5: Convert to Twilio format and store
            String twilioAudio = audioConversionService.convertToTwilio(audioBase64);
            if (twilioAudio == null) {
                log.warn("Failed to convert TTS audio to Twilio format");
                return;
            }

            // Step 6: Store and play audio
            String direction = "caller".equals(participant) ? "caller-to-target" : "target-to-caller";
            String audioUrl = audioFileStorageService.storeTranslatedAudio(twilioAudio, conferenceName, direction);
            if (audioUrl != null) {
                boolean success = botParticipantService.playTranslatedAudio(conferenceName, audioUrl);
                if (success) {
                    log.info("Successfully played translation for conference: {}", conferenceName);
                }

                // Schedule cleanup
                scheduler.schedule(() -> {
                    audioFileStorageService.cleanupAudioFile(audioUrl);
                }, 30, TimeUnit.SECONDS);
            }

        } catch (Exception e) {
            log.error("Error processing audio for conference: {}", conferenceName, e);
        }
    }

    /**
     * Process audio asynchronously
     */
    public void processAudioAsync(String conferenceName, String participant, String base64Audio, Runnable queueCallback) {
        scheduler.submit(() -> {
            try {
                processAudio(conferenceName, participant, base64Audio);
            } catch (Exception e) {
                log.error("Error in async audio processing for conference: {}", conferenceName, e);
            } finally {
                if (queueCallback != null) {
                    queueCallback.run();
                }
            }
        });
    }

    /**
     * Transcribe audio using Whisper API
     */
    private String transcribeAudio(String base64Audio, String language) {
        try {
            // Create multipart request for Whisper API
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.setBearerAuth(openaiApiKey);

            // Decode audio data
            byte[] audioData = Base64.getDecoder().decode(base64Audio);

            // Create form data
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            headers.setContentType(MediaType.parseMediaType("multipart/form-data; boundary=" + boundary));

            StringBuilder body = new StringBuilder();
            body.append("--").append(boundary).append("\r\n");
            body.append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n");
            body.append("Content-Type: audio/wav\r\n\r\n");
            // Note: In a real implementation, you'd need to properly handle binary data in multipart
            body.append(Base64.getEncoder().encodeToString(audioData)).append("\r\n");
            
            body.append("--").append(boundary).append("\r\n");
            body.append("Content-Disposition: form-data; name=\"model\"\r\n\r\n");
            body.append(whisperModel).append("\r\n");
            
            if (language != null && !language.isEmpty()) {
                body.append("--").append(boundary).append("\r\n");
                body.append("Content-Disposition: form-data; name=\"language\"\r\n\r\n");
                body.append(language.substring(0, 2)).append("\r\n"); // Use language code like "en"
            }
            
            body.append("--").append(boundary).append("--\r\n");

            HttpEntity<String> request = new HttpEntity<>(body.toString(), headers);
            
            ResponseEntity<String> response = restTemplate.postForEntity(
                "https://api.openai.com/v1/audio/transcriptions", 
                request, 
                String.class
            );

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode result = objectMapper.readTree(response.getBody());
                return result.get("text").asText();
            }

        } catch (Exception e) {
            log.error("Failed to transcribe audio", e);
        }
        return null;
    }

    /**
     * Translate text using GPT-4
     */
    private String translateText(String text, String sourceLanguage, String targetLanguage) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openaiApiKey);

            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", chatModel);
            request.put("max_tokens", maxTokens);
            request.put("temperature", 0.3);

            // Create translation prompt
            String prompt = String.format(
                "Translate the following text from %s to %s. " +
                "Provide only the translation, no explanations: \"%s\"",
                sourceLanguage, targetLanguage, text
            );

            ObjectNode message = objectMapper.createObjectNode();
            message.put("role", "user");
            message.put("content", prompt);

            request.set("messages", objectMapper.createArrayNode().add(message));

            HttpEntity<String> entity = new HttpEntity<>(request.toString(), headers);
            
            ResponseEntity<String> response = restTemplate.postForEntity(
                "https://api.openai.com/v1/chat/completions", 
                entity, 
                String.class
            );

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode result = objectMapper.readTree(response.getBody());
                return result.get("choices").get(0).get("message").get("content").asText().trim();
            }

        } catch (Exception e) {
            log.error("Failed to translate text", e);
        }
        return null;
    }

    /**
     * Convert text to speech using OpenAI TTS
     */
    private String textToSpeech(String text) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openaiApiKey);

            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", ttsModel);
            request.put("input", text);
            request.put("voice", ttsVoice);
            request.put("response_format", "mp3");

            HttpEntity<String> entity = new HttpEntity<>(request.toString(), headers);
            
            ResponseEntity<byte[]> response = restTemplate.postForEntity(
                "https://api.openai.com/v1/audio/speech", 
                entity, 
                byte[].class
            );

            if (response.getStatusCode() == HttpStatus.OK) {
                return Base64.getEncoder().encodeToString(response.getBody());
            }

        } catch (Exception e) {
            log.error("Failed to convert text to speech", e);
        }
        return null;
    }

    /**
     * Stop translation for a conference
     */
    public boolean stopTranslation(String conferenceName) {
        try {
            TranslationSession session = translationSessions.remove(conferenceName);
            if (session != null) {
                log.info("Translation stopped for conference: {}", conferenceName);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("Error stopping translation for conference: {}", conferenceName, e);
            return false;
        }
    }

    /**
     * Check if translation is active
     */
    public boolean isTranslationActive(String conferenceName) {
        return translationSessions.containsKey(conferenceName);
    }

    /**
     * Get active session count
     */
    public int getActiveSessionCount() {
        return translationSessions.size();
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up OpenAI Translation Service");
        translationSessions.clear();
        
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Translation session data class
     */
    private static class TranslationSession {
        private final String conferenceName;
        private final String sourceLanguage;
        private final String targetLanguage;

        public TranslationSession(String conferenceName, String sourceLanguage, String targetLanguage) {
            this.conferenceName = conferenceName;
            this.sourceLanguage = sourceLanguage;
            this.targetLanguage = targetLanguage;
        }

        public String getConferenceName() { return conferenceName; }
        public String getSourceLanguage() { return sourceLanguage; }
        public String getTargetLanguage() { return targetLanguage; }
    }
}