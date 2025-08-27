package com.example.translationcallapp.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.bind.annotation.CrossOrigin;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // Allows all domains - you can restrict this to your v0 domain later
public class CallController {
    
    @GetMapping("/health")
    public String health() {
        return "OK";
    }
    
    @PostMapping("/start-call")
    public String startCall(@RequestParam String to) {
        return "{\"status\": \"call initiated\", \"to\": \"" + to + "\"}";
    }
}
