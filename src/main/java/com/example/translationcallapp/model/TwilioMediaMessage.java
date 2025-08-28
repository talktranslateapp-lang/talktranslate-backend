package com.example.translationcallapp.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class TwilioMediaMessage {
    private String event;
    private String streamSid;
    private Media media;
    private Start start;
    
    public static class Media {
        private String track;
        private String chunk;
        private String timestamp;
        private String payload;
        
        public String getTrack() { return track; }
        public void setTrack(String track) { this.track = track; }
        public String getChunk() { return chunk; }
        public void setChunk(String chunk) { this.chunk = chunk; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
        public String getPayload() { return payload; }
        public void setPayload(String payload) { this.payload = payload; }
    }
    
    public static class Start {
        private String streamSid;
        private String accountSid;
        private String callSid;
        private String tracks;
        private String mediaFormat;
        private String customParameters;
        
        public String getStreamSid() { return streamSid; }
        public void setStreamSid(String streamSid) { this.streamSid = streamSid; }
        public String getAccountSid() { return accountSid; }
        public void setAccountSid(String accountSid) { this.accountSid = accountSid; }
        public String getCallSid() { return callSid; }
        public void setCallSid(String callSid) { this.callSid = callSid; }
        public String getTracks() { return tracks; }
        public void setTracks(String tracks) { this.tracks = tracks; }
        public String getMediaFormat() { return mediaFormat; }
        public void setMediaFormat(String mediaFormat) { this.mediaFormat = mediaFormat; }
        public String getCustomParameters() { return customParameters; }
        public void setCustomParameters(String customParameters) { this.customParameters = customParameters; }
    }
    
    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }
    public String getStreamSid() { return streamSid; }
    public void setStreamSid(String streamSid) { this.streamSid = streamSid; }
    public Media getMedia() { return media; }
    public void setMedia(Media media) { this.media = media; }
    public Start getStart() { return start; }
    public void setStart(Start start) { this.start = start; }
}
