package com.example.translationcallapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;
import java.util.Arrays;

/**
 * Configuration properties for audio file storage
 */
@ConfigurationProperties(prefix = "audio.storage")
public class AudioStorageProperties {
    
    private boolean enabled = true;
    private String location = "C:/temp/audio";
    private long maxFileSize = 10485760; // 10MB in bytes
    private long cleanupInterval = 3600000; // 1 hour in milliseconds
    private int retentionDays = 7;
    private List<String> allowedExtensions = Arrays.asList("wav", "mp3", "m4a", "ogg", "flac", "aac");

    /**
     * Default constructor
     */
    public AudioStorageProperties() {}

    /**
     * Constructor with all parameters
     */
    public AudioStorageProperties(boolean enabled, String location, long maxFileSize, 
                                 long cleanupInterval, int retentionDays, List<String> allowedExtensions) {
        this.enabled = enabled;
        this.location = location;
        this.maxFileSize = maxFileSize;
        this.cleanupInterval = cleanupInterval;
        this.retentionDays = retentionDays;
        this.allowedExtensions = allowedExtensions;
    }

    // Getter and Setter methods

    /**
     * @return true if audio storage is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param enabled whether audio storage is enabled
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @return the storage location path
     */
    public String getLocation() {
        return location;
    }

    /**
     * @param location the storage location path
     */
    public void setLocation(String location) {
        this.location = location;
    }

    /**
     * @return maximum file size in bytes
     */
    public long getMaxFileSize() {
        return maxFileSize;
    }

    /**
     * @param maxFileSize maximum file size in bytes
     */
    public void setMaxFileSize(long maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    /**
     * @return cleanup interval in milliseconds
     */
    public long getCleanupInterval() {
        return cleanupInterval;
    }

    /**
     * @param cleanupInterval cleanup interval in milliseconds
     */
    public void setCleanupInterval(long cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }

    /**
     * @return retention period in days
     */
    public int getRetentionDays() {
        return retentionDays;
    }

    /**
     * @param retentionDays retention period in days
     */
    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    /**
     * @return list of allowed file extensions
     */
    public List<String> getAllowedExtensions() {
        return allowedExtensions;
    }

    /**
     * @param allowedExtensions list of allowed file extensions
     */
    public void setAllowedExtensions(List<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }

    // Utility methods

    /**
     * Check if a file extension is allowed
     * @param extension the file extension to check
     * @return true if the extension is allowed
     */
    public boolean isExtensionAllowed(String extension) {
        if (extension == null || allowedExtensions == null) {
            return false;
        }
        return allowedExtensions.contains(extension.toLowerCase());
    }

    /**
     * Get max file size in MB for display purposes
     * @return max file size in MB
     */
    public double getMaxFileSizeMB() {
        return maxFileSize / (1024.0 * 1024.0);
    }

    /**
     * Get cleanup interval in hours for display purposes
     * @return cleanup interval in hours
     */
    public double getCleanupIntervalHours() {
        return cleanupInterval / (1000.0 * 60.0 * 60.0);
    }

    @Override
    public String toString() {
        return "AudioStorageProperties{" +
                "enabled=" + enabled +
                ", location='" + location + '\'' +
                ", maxFileSize=" + maxFileSize +
                ", cleanupInterval=" + cleanupInterval +
                ", retentionDays=" + retentionDays +
                ", allowedExtensions=" + allowedExtensions +
                '}';
    }
}