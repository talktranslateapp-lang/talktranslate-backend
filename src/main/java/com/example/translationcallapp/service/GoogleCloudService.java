package com.example.translationcallapp.service;

import com.google.api.gax.rpc.BidiStreamingCallable;
import com.google.cloud.speech.v1.*;
import com.google.cloud.texttospeech.v1.*;
import com.google.cloud.translate.v3.TranslationServiceClient;
import com.google.cloud.translate.v3.TranslateTextRequest;
import com.google.cloud.translate.v3.TranslateTextResponse;

import com.google.protobuf.ByteString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@Service
public class GoogleCloudService {
    
    @Value("${google.cloud.project.id}")
    private String projectId;
    
    private SpeechClient speechClient;
    private TextToSpeechClient textToSpeechClient;
    private TranslationServiceClient translationClient;
    
    @PostConstruct
    public void init() {
        try {
            speechClient = SpeechClient.create();
            textToSpeechClient = TextToSpeechClient.create();
            translationClient = TranslationServiceClient.create();
            System.out.println("Google Cloud services initialized successfully for project: " + projectId);
        } catch (IOException e) {
            System.err.println("Failed to initialize Google Cloud services: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    @PreDestroy
    public void cleanup() {
        if (speechClient != null) speechClient.close();
        if (textToSpeechClient != null) textToSpeechClient.close();
        if (translationClient != null) translationClient.close();
        System.out.println("Google Cloud services closed");
    }
    
    public BidiStreamingCallable<StreamingRecognizeRequest, StreamingRecognizeResponse> createSpeechStream(String sourceLanguage) {
        RecognitionConfig recognitionConfig = RecognitionConfig.newBuilder()
                .setEncoding(RecognitionConfig.AudioEncoding.MULAW)
                .setSampleRateHertz(8000)
                .setLanguageCode(sourceLanguage)
                .setEnableAutomaticPunctuation(true)
                .build();
        
        StreamingRecognitionConfig streamingRecognitionConfig = 
                StreamingRecognitionConfig.newBuilder()
                        .setConfig(recognitionConfig)
                        .setInterimResults(true)
                        .setSingleUtterance(false)
                        .build();
        
        return speechClient.streamingRecognizeCallable();
    }
    
    public CompletableFuture<String> translateText(String text, String sourceLanguage, String targetLanguage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                com.google.cloud.translate.v3.LocationName parent = com.google.cloud.translate.v3.LocationName.of(projectId, "global");
                
                TranslateTextRequest request = TranslateTextRequest.newBuilder()
                        .setParent(parent.toString())
                        .setMimeType("text/plain")
                        .setSourceLanguageCode(sourceLanguage)
                        .setTargetLanguageCode(targetLanguage)
                        .addContents(text)
                        .build();
                
                TranslateTextResponse response = translationClient.translateText(request);
                String translatedText = response.getTranslations(0).getTranslatedText();
                
                System.out.println("Translated '" + text + "' from " + sourceLanguage + " to " + targetLanguage + ": " + translatedText);
                return translatedText;
                
            } catch (Exception e) {
                System.err.println("Translation failed for text '" + text + "': " + e.getMessage());
                throw new RuntimeException("Translation failed", e);
            }
        });
    }
    
    public CompletableFuture<byte[]> synthesizeSpeech(String text, String languageCode, String voiceName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                SynthesisInput input = SynthesisInput.newBuilder()
                        .setText(text)
                        .build();
                
                VoiceSelectionParams voice = VoiceSelectionParams.newBuilder()
                        .setLanguageCode(languageCode)
                        .setName(voiceName)
                        .setSsmlGender(SsmlVoiceGender.NEUTRAL)
                        .build();
                
                AudioConfig audioConfig = AudioConfig.newBuilder()
                        .setAudioEncoding(AudioEncoding.MULAW)
                        .setSampleRateHertz(8000)
                        .build();
                
                SynthesizeSpeechResponse response = textToSpeechClient.synthesizeSpeech(
                        SynthesizeSpeechRequest.newBuilder()
                                .setInput(input)
                                .setVoice(voice)
                                .setAudioConfig(audioConfig)
                                .build());
                
                byte[] audioData = response.getAudioContent().toByteArray();
                System.out.println("Generated speech for text '" + text + "' in " + languageCode + " (" + audioData.length + " bytes)");
                return audioData;
                
            } catch (Exception e) {
                System.err.println("Text-to-speech failed for text '" + text + "': " + e.getMessage());
                throw new RuntimeException("Text-to-speech failed", e);
            }
        });
    }
    
    public String getDefaultVoiceName(String languageCode) {
        switch (languageCode) {
            case "en-US": return "en-US-Standard-A";
            case "es-ES": return "es-ES-Standard-A";
            case "fr-FR": return "fr-FR-Standard-A";
            case "de-DE": return "de-DE-Standard-A";
            case "it-IT": return "it-IT-Standard-A";
            case "pt-BR": return "pt-BR-Standard-A";
            case "ja-JP": return "ja-JP-Standard-A";
            case "ko-KR": return "ko-KR-Standard-A";
            case "zh-CN": return "zh-CN-Standard-A";
            case "ar-SA": return "ar-XA-Standard-A";
            case "ru-RU": return "ru-RU-Standard-A";
            case "hi-IN": return "hi-IN-Standard-A";
            default: return "en-US-Standard-A";
        }
    }
}
