package com.example.translationcallapp.service;

import com.example.translationcallapp.config.AudioStorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AudioFileStorageService {

    private final AudioStorageProperties storageProperties;
    private final OpenAITranslationService translationService;
    private final ObjectMapper objectMapper;
    
    private Path audioStorageDir;
    private final Map<String, FileMetadata> fileMetadataCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
    
    // Security patterns for file validation
    private static final Pattern SAFE_FILENAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("wav", "mp3", "m4a", "ogg", "flac", "aac");
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB

    @Autowired
    public AudioFileStorageService(AudioStorageProperties storageProperties, 
                                 OpenAITranslationService translationService,
                                 ObjectMapper objectMapper) {
        this.storageProperties = storageProperties;
        this.translationService = translationService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        try {
            this.audioStorageDir = Paths.get(storageProperties.getLocation()).toAbsolutePath().normalize();
            log.info("Initializing audio storage directory: {}", audioStorageDir);
            Files.createDirectories(audioStorageDir);
            log.info("Audio storage service initialized successfully");
            
            // Start periodic cleanup task
            cleanupExecutor.scheduleWithFixedDelay(this::performPeriodicCleanup, 1, 24, TimeUnit.HOURS);
            
        } catch (IOException e) {
            log.error("Failed to initialize audio storage directory: {}", e.getMessage(), e);
            throw new RuntimeException("Could not initialize storage directory", e);
        }
    }

    public String storeAudioFile(MultipartFile file, String conferenceId, String direction) throws IOException {
        // Validate input parameters
        validateFileInput(file);
        String sanitizedConferenceId = sanitizeInput(conferenceId);
        String sanitizedDirection = sanitizeInput(direction);
        
        // Generate unique file ID
        String fileId = generateFileId();
        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename());
        String sanitizedFilename = sanitizeFilename(originalFilename);
        
        log.info("Storing audio file - ID: {}, Original: {}, Sanitized: {}", 
                fileId, originalFilename, sanitizedFilename);

        try {
            // Create directory structure: /conference-id/direction/
            Path targetDir = createDirectoryStructure(sanitizedConferenceId, sanitizedDirection);
            Path targetFile = targetDir.resolve(fileId + "_" + sanitizedFilename);
            
            // Ensure the resolved path is within our storage directory (prevent path traversal)
            if (!targetFile.normalize().startsWith(audioStorageDir.normalize())) {
                throw new SecurityException("Path traversal attempt detected!");
            }
            
            // Copy file to target location
            Files.copy(file.getInputStream(), targetFile, StandardCopyOption.REPLACE_EXISTING);
            
            // Store metadata
            FileMetadata metadata = new FileMetadata(
                    fileId, originalFilename, sanitizedFilename, targetFile.toString(),
                    file.getSize(), file.getContentType(), sanitizedConferenceId, 
                    sanitizedDirection, LocalDateTime.now()
            );
            fileMetadataCache.put(fileId, metadata);
            saveMetadataToFile(metadata);
            
            log.info("Audio file stored successfully: {}", fileId);
            return fileId;
            
        } catch (IOException e) {
            log.error("Failed to store audio file {}: {}", fileId, e.getMessage(), e);
            throw new IOException("Failed to store file: " + e.getMessage(), e);
        }
    }

    public Resource loadAudioFileAsResource(String fileId) {
        try {
            FileMetadata metadata = getFileMetadata(fileId);
            if (metadata == null) {
                log.warn("File metadata not found for ID: {}", fileId);
                return null;
            }

            Path filePath = Paths.get(metadata.getFilePath()).normalize();
            
            // Security check: ensure file is within storage directory
            if (!filePath.startsWith(audioStorageDir.normalize())) {
                log.error("Security violation: File path outside storage directory for ID: {}", fileId);
                throw new SecurityException("Invalid file path");
            }

            Resource resource = new UrlResource(filePath.toUri());
            if (resource.exists() && resource.isReadable()) {
                log.info("Loading audio file resource: {}", fileId);
                return resource;
            } else {
                log.warn("File not found or not readable: {}", fileId);
                return null;
            }
        } catch (MalformedURLException e) {
            log.error("Error loading file as resource {}: {}", fileId, e.getMessage(), e);
            return null;
        }
    }

    public boolean deleteAudioFile(String fileId) {
        try {
            FileMetadata metadata = getFileMetadata(fileId);
            if (metadata == null) {
                log.warn("Cannot delete - file metadata not found: {}", fileId);
                return false;
            }

            Path filePath = Paths.get(metadata.getFilePath());
            boolean deleted = Files.deleteIfExists(filePath);
            
            if (deleted) {
                fileMetadataCache.remove(fileId);
                deleteMetadataFile(fileId);
                log.info("Audio file deleted successfully: {}", fileId);
            }
            
            return deleted;
        } catch (IOException e) {
            log.error("Error deleting audio file {}: {}", fileId, e.getMessage(), e);
            return false;
        }
    }

    public List<Map<String, Object>> listAudioFiles(String conferenceId, int limit, int offset) {
        List<Map<String, Object>> result = new ArrayList<>();
        
        try {
            List<FileMetadata> allFiles = new ArrayList<>(fileMetadataCache.values());
            
            // Filter by conference ID if specified
            if (conferenceId != null && !conferenceId.isEmpty()) {
                String sanitizedConferenceId = sanitizeInput(conferenceId);
                allFiles = allFiles.stream()
                        .filter(metadata -> sanitizedConferenceId.equals(metadata.getConferenceId()))
                        .toList();
            }
            
            // Sort by creation time (newest first)
            allFiles.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
            
            // Apply pagination
            int start = Math.max(0, offset);
            int end = Math.min(allFiles.size(), start + limit);
            
            for (int i = start; i < end; i++) {
                FileMetadata metadata = allFiles.get(i);
                Map<String, Object> fileInfo = new HashMap<>();
                fileInfo.put("fileId", metadata.getFileId());
                fileInfo.put("originalFilename", metadata.getOriginalFilename());
                fileInfo.put("size", metadata.getSize());
                fileInfo.put("contentType", metadata.getContentType());
                fileInfo.put("conferenceId", metadata.getConferenceId());
                fileInfo.put("direction", metadata.getDirection());
                fileInfo.put("createdAt", metadata.getCreatedAt().toString());
                result.add(fileInfo);
            }
            
        } catch (Exception e) {
            log.error("Error listing audio files: {}", e.getMessage(), e);
        }
        
        return result;
    }

    public Map<String, Object> getAudioFileInfo(String fileId) {
        try {
            FileMetadata metadata = getFileMetadata(fileId);
            if (metadata == null) {
                return null;
            }

            Path filePath = Paths.get(metadata.getFilePath());
            boolean exists = Files.exists(filePath);
            
            Map<String, Object> info = new HashMap<>();
            info.put("fileId", metadata.getFileId());
            info.put("originalFilename", metadata.getOriginalFilename());
            info.put("sanitizedFilename", metadata.getSanitizedFilename());
            info.put("size", metadata.getSize());
            info.put("contentType", metadata.getContentType());
            info.put("conferenceId", metadata.getConferenceId());
            info.put("direction", metadata.getDirection());
            info.put("createdAt", metadata.getCreatedAt().toString());
            info.put("exists", exists);
            
            if (exists) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
                    info.put("lastModified", attrs.lastModifiedTime().toString());
                    info.put("actualSize", attrs.size());
                } catch (IOException e) {
                    log.warn("Could not read file attributes for {}: {}", fileId, e.getMessage());
                }
            }
            
            return info;
        } catch (Exception e) {
            log.error("Error getting file info for {}: {}", fileId, e.getMessage(), e);
            return null;
        }
    }

    public Map<String, Object> processAudioFile(String fileId, String operation, String targetLanguage) {
        try {
            log.info("Processing audio file {} with operation: {}", fileId, operation);
            
            Resource resource = loadAudioFileAsResource(fileId);
            if (resource == null) {
                return null;
            }

            Map<String, Object> result = new HashMap<>();
            
            switch (operation.toLowerCase()) {
                case "transcribe":
                    // Implement transcription using OpenAI
                    String transcription = translationService.transcribeAudio(resource);
                    result.put("transcription", transcription);
                    break;
                    
                case "translate":
                    if (targetLanguage == null) {
                        throw new IllegalArgumentException("Target language required for translation");
                    }
                    String translation = translationService.translateAudio(resource, targetLanguage);
                    result.put("translation", translation);
                    result.put("targetLanguage", targetLanguage);
                    break;
                    
                default:
                    throw new IllegalArgumentException("Unsupported operation: " + operation);
            }
            
            result.put("fileId", fileId);
            result.put("operation", operation);
            result.put("processedAt", LocalDateTime.now().toString());
            
            return result;
            
        } catch (Exception e) {
            log.error("Error processing audio file {}: {}", fileId, e.getMessage(), e);
            return null;
        }
    }

    public Map<String, Object> getStorageStatus() {
        try {
            long totalFiles = fileMetadataCache.size();
            long totalSize = fileMetadataCache.values().stream()
                    .mapToLong(FileMetadata::getSize)
                    .sum();
            
            long availableSpace = Files.getFileStore(audioStorageDir).getUsableSpace();
            long totalSpace = Files.getFileStore(audioStorageDir).getTotalSpace();
            
            Map<String, Object> status = new HashMap<>();
            status.put("totalFiles", totalFiles);
            status.put("totalSizeBytes", totalSize);
            status.put("totalSizeMB", totalSize / (1024 * 1024));
            status.put("availableSpaceBytes", availableSpace);
            status.put("availableSpaceMB", availableSpace / (1024 * 1024));
            status.put("totalSpaceBytes", totalSpace);
            status.put("totalSpaceMB", totalSpace / (1024 * 1024));
            status.put("storageDirectory", audioStorageDir.toString());
            
            return status;
        } catch (Exception e) {
            log.error("Error getting storage status: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    public int cleanupOldFiles(int olderThanDays) {
        int deletedCount = 0;
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(olderThanDays);
        
        try {
            List<String> filesToDelete = fileMetadataCache.entrySet().stream()
                    .filter(entry -> entry.getValue().getCreatedAt().isBefore(cutoffDate))
                    .map(Map.Entry::getKey)
                    .toList();
            
            for (String fileId : filesToDelete) {
                if (deleteAudioFile(fileId)) {
                    deletedCount++;
                }
            }
            
            log.info("Cleanup completed - deleted {} files older than {} days", deletedCount, olderThanDays);
            
        } catch (Exception e) {
            log.error("Error during cleanup: {}", e.getMessage(), e);
        }
        
        return deletedCount;
    }

    private void validateFileInput(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be null or empty");
        }
        
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds maximum allowed size");
        }
        
        String filename = file.getOriginalFilename();
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("Filename cannot be null or empty");
        }
        
        String extension = getFileExtension(filename);
        if (!ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
            throw new IllegalArgumentException("File type not allowed: " + extension);
        }
    }

    private String sanitizeFilename(String filename) {
        if (filename == null) {
            return "unknown";
        }
        
        // Remove path separators and dangerous characters
        String sanitized = filename.replaceAll("[/\\\\:*?\"<>|]", "_");
        
        // Ensure filename is not too long
        if (sanitized.length() > 100) {
            String extension = getFileExtension(sanitized);
            String baseName = sanitized.substring(0, 95 - extension.length());
            sanitized = baseName + "." + extension;
        }
        
        return sanitized;
    }

    private String sanitizeInput(String input) {
        if (input == null) {
            return null;
        }
        return input.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        return lastDotIndex == -1 ? "" : filename.substring(lastDotIndex + 1);
    }

    private String generateFileId() {
        return UUID.randomUUID().toString() + "_" + 
               LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }

    private Path createDirectoryStructure(String conferenceId, String direction) throws IOException {
        Path targetDir = audioStorageDir;
        
        if (conferenceId != null && !conferenceId.isEmpty()) {
            targetDir = targetDir.resolve(conferenceId);
        }
        
        if (direction != null && !direction.isEmpty()) {
            targetDir = targetDir.resolve(direction);
        }
        
        Files.createDirectories(targetDir);
        return targetDir;
    }

    private FileMetadata getFileMetadata(String fileId) {
        return fileMetadataCache.get(fileId);
    }

    private void saveMetadataToFile(FileMetadata metadata) {
        try {
            Path metadataDir = audioStorageDir.resolve(".metadata");
            Files.createDirectories(metadataDir);
            
            Path metadataFile = metadataDir.resolve(metadata.getFileId() + ".json");
            objectMapper.writeValue(metadataFile.toFile(), metadata);
            
        } catch (IOException e) {
            log.error("Failed to save metadata for file {}: {}", metadata.getFileId(), e.getMessage(), e);
        }
    }

    private void deleteMetadataFile(String fileId) {
        try {
            Path metadataFile = audioStorageDir.resolve(".metadata").resolve(fileId + ".json");
            Files.deleteIfExists(metadataFile);
        } catch (IOException e) {
            log.error("Failed to delete metadata file for {}: {}", fileId, e.getMessage(), e);
        }
    }

    private void performPeriodicCleanup() {
        try {
            log.info("Performing periodic cleanup of old audio files");
            int deleted = cleanupOldFiles(7); // Clean files older than 7 days
            log.info("Periodic cleanup completed - deleted {} files", deleted);
        } catch (Exception e) {
            log.error("Error during periodic cleanup: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void cleanup() {
        log.info("Shutting down audio file storage service");
        cleanupExecutor.shutdown();
    }

    // Inner class for file metadata
    private static class FileMetadata {
        private String fileId;
        private String originalFilename;
        private String sanitizedFilename;
        private String filePath;
        private long size;
        private String contentType;
        private String conferenceId;
        private String direction;
        private LocalDateTime createdAt;

        public FileMetadata() {}

        public FileMetadata(String fileId, String originalFilename, String sanitizedFilename,
                          String filePath, long size, String contentType, String conferenceId,
                          String direction, LocalDateTime createdAt) {
            this.fileId = fileId;
            this.originalFilename = originalFilename;
            this.sanitizedFilename = sanitizedFilename;
            this.filePath = filePath;
            this.size = size;
            this.contentType = contentType;
            this.conferenceId = conferenceId;
            this.direction = direction;
            this.createdAt = createdAt;
        }

        // Getters and setters
        public String getFileId() { return fileId; }
        public void setFileId(String fileId) { this.fileId = fileId; }

        public String getOriginalFilename() { return originalFilename; }
        public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

        public String getSanitizedFilename() { return sanitizedFilename; }
        public void setSanitizedFilename(String sanitizedFilename) { this.sanitizedFilename = sanitizedFilename; }

        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }

        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }

        public String getContentType() { return contentType; }
        public void setContentType(String contentType) { this.contentType = contentType; }

        public String getConferenceId() { return conferenceId; }
        public void setConferenceId(String conferenceId) { this.conferenceId = conferenceId; }

        public String getDirection() { return direction; }
        public void setDirection(String direction) { this.direction = direction; }

        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }
}