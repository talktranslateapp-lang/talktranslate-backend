package com.example.translationcallapp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TwilioMediaMessage {
    
    @JsonProperty("event")
    private String event;
    
    @JsonProperty("sequenceNumber")
    private String sequenceNumber;
    
    @JsonProperty("media")
    private MediaPayload media;
    
    @JsonProperty("streamSid")
    private String streamSid;
    
    @JsonProperty("start")
    private StartPayload start;
    
    @JsonProperty("mark")
    private MarkPayload mark;
    
    // Default constructor
    public TwilioMediaMessage() {}
    
    // Getters and setters
    public String getEvent() {
        return event;
    }
    
    public void setEvent(String event) {
        this.event = event;
    }
    
    public String getSequenceNumber() {
        return sequenceNumber;
    }
    
    public void setSequenceNumber(String sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }
    
    public MediaPayload getMedia() {
        return media;
    }
    
    public void setMedia(MediaPayload media) {
        this.media = media;
    }
    
    public String getStreamSid() {
        return streamSid;
    }
    
    public void setStreamSid(String streamSid) {
        this.streamSid = streamSid;
    }
    
    public StartPayload getStart() {
        return start;
    }
    
    public void setStart(StartPayload start) {
        this.start = start;
    }
    
    public MarkPayload getMark() {
        return mark;
    }
    
    public void setMark(MarkPayload mark) {
        this.mark = mark;
    }
    
    // Inner classes for nested payloads
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MediaPayload {
        @JsonProperty("track")
        private String track;
        
        @JsonProperty("chunk")
        private String chunk;
        
        @JsonProperty("timestamp")
        private String timestamp;
        
        @JsonProperty("payload")
        private String payload;
        
        public MediaPayload() {}
        
        public String getTrack() {
            return track;
        }
        
        public void setTrack(String track) {
            this.track = track;
        }
        
        public String getChunk() {
            return chunk;
        }
        
        public void setChunk(String chunk) {
            this.chunk = chunk;
        }
        
        public String getTimestamp() {
            return timestamp;
        }
        
        public void setTimestamp(String timestamp) {
            this.timestamp = timestamp;
        }
        
        public String getPayload() {
            return payload;
        }
        
        public void setPayload(String payload) {
            this.payload = payload;
        }
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StartPayload {
        @JsonProperty("streamSid")
        private String streamSid;
        
        @JsonProperty("accountSid")
        private String accountSid;
        
        @JsonProperty("callSid")
        private String callSid;
        
        @JsonProperty("tracks")
        private String[] tracks;
        
        @JsonProperty("mediaFormat")
        private MediaFormat mediaFormat;
        
        public StartPayload() {}
        
        public String getStreamSid() {
            return streamSid;
        }
        
        public void setStreamSid(String streamSid) {
            this.streamSid = streamSid;
        }
        
        public String getAccountSid() {
            return accountSid;
        }
        
        public void setAccountSid(String accountSid) {
            this.accountSid = accountSid;
        }
        
        public String getCallSid() {
            return callSid;
        }
        
        public void setCallSid(String callSid) {
            this.callSid = callSid;
        }
        
        public String[] getTracks() {
            return tracks;
        }
        
        public void setTracks(String[] tracks) {
            this.tracks = tracks;
        }
        
        public MediaFormat getMediaFormat() {
            return mediaFormat;
        }
        
        public void setMediaFormat(MediaFormat mediaFormat) {
            this.mediaFormat = mediaFormat;
        }
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MediaFormat {
        @JsonProperty("encoding")
        private String encoding;
        
        @JsonProperty("sampleRate")
        private int sampleRate;
        
        @JsonProperty("channels")
        private int channels;
        
        public MediaFormat() {}
        
        public String getEncoding() {
            return encoding;
        }
        
        public void setEncoding(String encoding) {
            this.encoding = encoding;
        }
        
        public int getSampleRate() {
            return sampleRate;
        }
        
        public void setSampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
        }
        
        public int getChannels() {
            return channels;
        }
        
        public void setChannels(int channels) {
            this.channels = channels;
        }
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MarkPayload {
        @JsonProperty("name")
        private String name;
        
        public MarkPayload() {}
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
    }
}