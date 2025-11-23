package com.example.videoeditor.controller;

import com.example.videoeditor.entity.SoleTTS;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.security.JwtUtil;
import com.example.videoeditor.service.SoleTTSService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/sole-tts")
public class SoleTTSController {

    private final SoleTTSService soleTTSService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public SoleTTSController(SoleTTSService soleTTSService, JwtUtil jwtUtil, UserRepository userRepository) {
        this.soleTTSService = soleTTSService;
      this.jwtUtil = jwtUtil;
      this.userRepository = userRepository;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generateTTS(
            @RequestHeader("Authorization") String token,
            @RequestBody Map<String, Object> request) {
        try {
            // Authenticate user
            User user = getUserFromToken(token);

            // Extract parameters
            String text = (String) request.get("text");
            String voiceName = (String) request.get("voiceName");
            String languageCode = (String) request.get("languageCode");

            @SuppressWarnings("unchecked")
            Map<String, String> ssmlConfig = (Map<String, String>) request.get("ssmlConfig");

            // Validate parameters
            if (text == null || text.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Text is required and cannot be empty");
            }
            if (voiceName == null || voiceName.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Voice name is required");
            }
            if (languageCode == null || languageCode.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Language code is required");
            }

            // Generate TTS
            SoleTTS soleTTS = soleTTSService.generateTTS(user, text, voiceName, languageCode, ssmlConfig);

            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("id", soleTTS.getId());
            response.put("audioPath", soleTTS.getAudioPath());
            response.put("createdAt", soleTTS.getCreatedAt());

            return ResponseEntity.ok(response);
        } catch (IOException | InterruptedException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error generating TTS audio: " + e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Unauthorized: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Unexpected error: " + e.getMessage());
        }
    }

    public User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));
    }
}