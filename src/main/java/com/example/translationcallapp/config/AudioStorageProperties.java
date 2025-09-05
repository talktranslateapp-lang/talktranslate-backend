package com.example.translationcallapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Arrays;

/**
 * Configuration properties for audio file storage.
 * Binds to application properties with prefix "app.audio.storage"
 */
@Component
@ConfigurationProperties(prefix = "app.audio.storage")
@Validated
public class AudioStorageProperties {

    /**
     * Base directory for storing audio files
     */
    @NotBlank
    private String basePath = "./audio-files";

    /**
     * Maximum file size in bytes (default 10MB)
     */
    @Positive
    private long maxFileSize = 10 * 1024 * 1024; // 10MB

    /**
     * Allowed audio file extensions
     */
    private List<String> allowedExtensions = Arrays.asList(".wav", ".mp3", ".m4a", ".aac");

    /**
     * File retention duration in hours before cleanup
     */
    @Positive
    private int retentionHours = 24;

    /**
     * Temporary directory for file processing
     */
    @NotBlank
    private String tempPath = System.getProperty("java.io.tmpdir") + "/translation-audio-temp";

    /**
     * Whether to enable automatic cleanup of old files
     */
    private boolean enableCleanup = true;

    /**
     * Maximum filename length to prevent filesystem issues
     */
    @Positive
    private int maxFilenameLength = 255;

    // Getters and Setters

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(long maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public List<String> getAllowedExtensions() {
        return allowedExtensions;
    }

    public void setAllowedExtensions(List<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }

    public int getRetentionHours() {
        return retentionHours;
    }

    public void setRetentionHours(int retentionHours) {
        this.retentionHours = retentionHours;
    }

    public String getTempPath() {
        return tempPath;
    }

    public void setTempPath(String tempPath) {
        this.tempPath = tempPath;
    }

    public boolean isEnableCleanup() {
        return enableCleanup;
    }

    public void setEnableCleanup(boolean enableCleanup) {
        this.enableCleanup = enableCleanup;
    }

    public int getMaxFilenameLength() {
        return maxFilenameLength;
    }

    public void setMaxFilenameLength(int maxFilenameLength) {
        this.maxFilenameLength = maxFilenameLength;
    }

    /**
     * Validates if a file extension is allowed
     */
    public boolean isExtensionAllowed(String extension) {
        return allowedExtensions.contains(extension.toLowerCase());
    }

    /**
     * Validates if a file size is within limits
     */
    public boolean isFileSizeValid(long fileSize) {
        return fileSize > 0 && fileSize <= maxFileSize;
    }

    /**
     * Validates and sanitizes filename
     */
    public boolean isFilenameValid(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return false;
        }
        
        // Check length
        if (filename.length() > maxFilenameLength) {
            return false;
        }
        
        // Check for path traversal attempts
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return false;
        }
        
        // Check for invalid characters
        String invalidChars = "<>:\"|?*";
        for (char c : invalidChars.toCharArray()) {
            if (filename.indexOf(c) >= 0) {
                return false;
            }
        }
        
        return true;
    }
}