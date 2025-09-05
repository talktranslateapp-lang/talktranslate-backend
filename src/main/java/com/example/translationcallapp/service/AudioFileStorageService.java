package com.example.translationcallapp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AudioFileStorageService {

    @Value("${audio.storage.directory:./audio-files}")
    private String audioStorageDirectory;

    @Value("${audio.storage.cleanup.enabled:true}")
    private boolean cleanupEnabled;

    @Value("${audio.storage.cleanup.interval:300}")
    private int cleanupIntervalSeconds;

    @Value("${audio.storage.cleanup.max-age:14400}")
    private int maxAgeSeconds;

    @Value("${audio.storage.cleanup.batch-size:100}")
    private int cleanupBatchSize;

    private Path audioStorageDir;
    private ScheduledExecutorService cleanupScheduler;
    private final AtomicLong totalFilesServed = new AtomicLong(0);

    @PostConstruct
    public void initialize() {
        try {
            audioStorageDir = Paths.get(audioStorageDirectory);
            
            // Create directory if it doesn't exist
            if (!Files.exists(audioStorageDir)) {
                Files.createDirectories(audioStorageDir);
                log.info("Created audio storage directory: {}", audioStorageDir.toAbsolutePath());
            } else {
                log.info("Using existing audio storage directory: {}", audioStorageDir.toAbsolutePath());
            }

            // Verify directory is writable
            if (!Files.isWritable(audioStorageDir)) {
                throw new RuntimeException("Audio storage directory is not writable: " + audioStorageDir);
            }

            // Initialize cleanup scheduler if enabled
            if (cleanupEnabled) {
                cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "audio-cleanup");
                    t.setDaemon(true);
                    return t;
                });

                cleanupScheduler.scheduleWithFixedDelay(
                        this::performCleanup,
                        cleanupIntervalSeconds,
                        cleanupIntervalSeconds,
                        TimeUnit.SECONDS
                );

                log.info("Audio file cleanup scheduled every {} seconds for files older than {} seconds",
                        cleanupIntervalSeconds, maxAgeSeconds);
            }

        } catch (Exception e) {
            log.error("Failed to initialize audio storage service", e);
            throw new RuntimeException("Failed to initialize audio storage service", e);
        }
    }

    /**
     * Store translated audio file and return its URL
     */
    public String storeTranslatedAudio(String base64Audio, String conferenceName, String direction) {
        try {
            if (base64Audio == null || base64Audio.isEmpty()) {
                log.warn("Cannot store empty audio data");
                return null;
            }

            // Generate unique filename with timestamp
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            String filename = String.format("translated-%s-%s-%s.wav", conferenceName, direction, timestamp);

            // Create conference-specific subdirectory
            Path conferenceDir = audioStorageDir.resolve(conferenceName).resolve(direction);
            if (!Files.exists(conferenceDir)) {
                Files.createDirectories(conferenceDir);
            }

            // Decode base64 and write to file
            byte[] audioData = Base64.getDecoder().decode(base64Audio);
            Path audioFile = conferenceDir.resolve(filename);
            
            Files.write(audioFile, audioData, StandardOpenOption.CREATE, StandardOpenOption.WRITE);

            // Generate URL for Twilio to access
            String audioUrl = String.format("/api/audio/conference/%s/%s/%s", conferenceName, direction, filename);
            
            log.debug("Stored translated audio: {} ({} bytes)", audioFile, audioData.length);
            return audioUrl;

        } catch (Exception e) {
            log.error("Failed to store translated audio for conference: {}, direction: {}", 
                     conferenceName, direction, e);
            return null;
        }
    }

    /**
     * Store audio file with simple filename
     */
    public String storeAudioFile(String base64Audio, String filename) {
        try {
            if (base64Audio == null || base64Audio.isEmpty()) {
                log.warn("Cannot store empty audio data");
                return null;
            }

            // Sanitize filename
            String sanitizedFilename = sanitizeFilename(filename);
            if (sanitizedFilename == null) {
                log.warn("Invalid filename: {}", filename);
                return null;
            }

            // Decode base64 and write to file
            byte[] audioData = Base64.getDecoder().decode(base64Audio);
            Path audioFile = audioStorageDir.resolve(sanitizedFilename);
            
            Files.write(audioFile, audioData, StandardOpenOption.CREATE, StandardOpenOption.WRITE);

            // Generate URL for access
            String audioUrl = "/api/audio/translated/" + sanitizedFilename;
            
            log.debug("Stored audio file: {} ({} bytes)", audioFile, audioData.length);
            return audioUrl;

        } catch (Exception e) {
            log.error("Failed to store audio file: {}", filename, e);
            return null;
        }
    }

    /**
     * Get audio resource for serving files
     */
    public Resource getAudioResource(String filename) {
        try {
            Path filePath = audioStorageDir.resolve(filename);
            if (!Files.exists(filePath)) {
                return null;
            }
            
            totalFilesServed.incrementAndGet();
            return new org.springframework.core.io.UrlResource(filePath.toUri());
            
        } catch (Exception e) {
            log.error("Error getting audio resource: {}", filename, e);
            return null;
        }
    }

    /**
     * Get conference-specific audio resource
     */
    public Resource getConferenceAudioResource(String conferenceName, String direction, String filename) {
        try {
            Path filePath = audioStorageDir
                    .resolve(conferenceName)
                    .resolve(direction)
                    .resolve(filename);
                    
            if (!Files.exists(filePath)) {
                return null;
            }
            
            totalFilesServed.incrementAndGet();
            return new org.springframework.core.io.UrlResource(filePath.toUri());
            
        } catch (Exception e) {
            log.error("Error getting conference audio resource: {}", filename, e);
            return null;
        }
    }

    /**
     * Check if service is healthy
     */
    public boolean isServiceHealthy() {
        try {
            return Files.exists(audioStorageDir) && Files.isWritable(audioStorageDir);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get active file count
     */
    public int getActiveFileCount() {
        try {
            return (int) Files.walk(audioStorageDir)
                    .filter(Files::isRegularFile)
                    .count();
        } catch (Exception e) {
            log.error("Error counting active files", e);
            return 0;
        }
    }

    /**
     * Get total files served
     */
    public long getTotalFilesServed() {
        return totalFilesServed.get();
    }

    /**
     * Get storage used in MB
     */
    public double getStorageUsedMB() {
        try {
            long totalBytes = Files.walk(audioStorageDir)
                    .filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (Exception e) {
                            return 0;
                        }
                    })
                    .sum();
                    
            return totalBytes / (1024.0 * 1024.0);
            
        } catch (Exception e) {
            log.error("Error calculating storage usage", e);
            return 0.0;
        }
    }

    /**
     * Manual cleanup for maintenance
     */
    public int performManualCleanup() {
        try {
            List<Path> filesToDelete = Files.walk(audioStorageDir)
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis() 
                                   < System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .collect(Collectors.toList());

            int deletedCount = 0;
            for (Path file : filesToDelete) {
                try {
                    Files.deleteIfExists(file);
                    deletedCount++;
                } catch (Exception e) {
                    log.warn("Failed to delete file: {}", file, e);
                }
            }

            return deletedCount;
            
        } catch (Exception e) {
            log.error("Error during manual cleanup", e);
            return 0;
        }
    }

    /**
     * Clean up a specific audio file by URL
     */
    public boolean cleanupAudioFile(String audioUrl) {
        try {
            if (audioUrl == null || audioUrl.isEmpty()) {
                return false;
            }

            // Extract filename from URL
            String filename = extractFilenameFromUrl(audioUrl);
            if (filename == null) {
                return false;
            }

            // Try different path patterns
            Path[] possiblePaths = {
                audioStorageDir.resolve(filename),
                // For conference-specific files, the URL pattern is /api/audio/conference/{conf}/{dir}/{file}
                extractConferenceFilePath(audioUrl)
            };

            for (Path path : possiblePaths) {
                if (path != null && Files.exists(path)) {
                    Files.deleteIfExists(path);
                    log.debug("Cleaned up audio file: {}", path);
                    return true;
                }
            }

            log.warn("Audio file not found for cleanup: {}", audioUrl);
            return false;

        } catch (Exception e) {
            log.error("Failed to cleanup audio file: {}", audioUrl, e);
            return false;
        }
    }

    /**
     * Scheduled cleanup task
     */
    private void performCleanup() {
        try {
            long cutoffTime = System.currentTimeMillis() - (maxAgeSeconds * 1000L);
            
            List<Path> filesToDelete = Files.walk(audioStorageDir)
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis() < cutoffTime;
                        } catch (IOException e) {
                            log.debug("Error checking file modification time: {}", path, e);
                            return false;
                        }
                    })
                    .limit(cleanupBatchSize)
                    .collect(Collectors.toList());

            if (!filesToDelete.isEmpty()) {
                int deletedCount = 0;
                for (Path file : filesToDelete) {
                    try {
                        Files.deleteIfExists(file);
                        deletedCount++;
                    } catch (IOException e) {
                        log.warn("Failed to delete expired file: {}", file, e);
                    }
                }
                
                if (deletedCount > 0) {
                    log.info("Cleaned up {} expired audio files", deletedCount);
                }
            }

            // Clean up empty directories
            cleanupEmptyDirectories();

        } catch (Exception e) {
            log.error("Error during scheduled cleanup", e);
        }
    }

    /**
     * Clean up empty conference directories
     */
    private void cleanupEmptyDirectories() {
        try {
            Files.walk(audioStorageDir)
                    .filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(audioStorageDir))
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount()) // Delete deepest first
                    .forEach(dir -> {
                        try {
                            if (Files.list(dir).findAny().isEmpty()) {
                                Files.deleteIfExists(dir);
                                log.debug("Removed empty directory: {}", dir);
                            }
                        } catch (IOException e) {
                            log.debug("Could not check/delete directory: {}", dir, e);
                        }
                    });
        } catch (Exception e) {
            log.debug("Error cleaning up empty directories", e);
        }
    }

    /**
     * Sanitize filename to prevent path traversal
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return null;
        }

        // Remove path separators and other dangerous characters
        String sanitized = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        
        // Prevent empty or too long filenames
        if (sanitized.length() == 0 || sanitized.length() > 255) {
            return null;
        }

        return sanitized;
    }

    /**
     * Extract filename from audio URL
     */
    private String extractFilenameFromUrl(String audioUrl) {
        if (audioUrl == null) {
            return null;
        }

        // Extract filename from URL path
        int lastSlash = audioUrl.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < audioUrl.length() - 1) {
            return audioUrl.substring(lastSlash + 1);
        }

        return null;
    }

    /**
     * Extract conference file path from URL
     */
    private Path extractConferenceFilePath(String audioUrl) {
        try {
            // Pattern: /api/audio/conference/{conferenceName}/{direction}/{filename}
            if (audioUrl.startsWith("/api/audio/conference/")) {
                String[] parts = audioUrl.split("/");
                if (parts.length >= 6) {
                    String conferenceName = parts[3];
                    String direction = parts[4];
                    String filename = parts[5];
                    return audioStorageDir.resolve(conferenceName).resolve(direction).resolve(filename);
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting conference file path from URL: {}", audioUrl, e);
        }
        return null;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down audio storage service");
        
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdown();
            try {
                if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}