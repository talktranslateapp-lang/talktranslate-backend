package com.example.translationcallapp.controller;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
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
