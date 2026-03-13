package com.example.videoeditor.controller.imagecontroller;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.imageentity.SoleImageGen;
import com.example.videoeditor.enums.ImageGenModel;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.security.JwtUtil;
import com.example.videoeditor.service.CreditService;
import com.example.videoeditor.service.imageservice.SoleImageGenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/sole-image-gen")
public class SoleImageGenController {

    private final SoleImageGenService soleImageGenService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final CreditService creditService;

    public SoleImageGenController(
            SoleImageGenService soleImageGenService,
            JwtUtil jwtUtil,
            UserRepository userRepository, CreditService creditService) {
        this.soleImageGenService = soleImageGenService;
        this.creditService = creditService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generateImages(
            @RequestHeader("Authorization") String token,
            @RequestBody Map<String, Object> request) {
        try {
            // Authenticate user
            User user = getUserFromToken(token);

            // Extract parameters
            String prompt = (String) request.get("prompt");
            String negativePrompt = (String) request.get("negativePrompt");

            // Validate
            if (prompt == null || prompt.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Prompt is required and cannot be empty");
            }

            // Generate images
            List<SoleImageGen> images = soleImageGenService.generateImages(user, prompt, negativePrompt);

            // Prepare response
            List<Map<String, Object>> imagesList = new ArrayList<>();
            for (SoleImageGen img : images) {
                Map<String, Object> imgData = new HashMap<>();
                imgData.put("id", img.getId());
                imgData.put("imagePath", img.getImagePath());
                imgData.put("prompt", img.getPrompt());
                imgData.put("resolution", img.getResolution());
                imgData.put("createdAt", img.getCreatedAt());
                imagesList.add(imgData);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("images", imagesList);
            response.put("count", images.size());

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error generating images: " + e.getMessage());
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

    @GetMapping("/usage")
    public ResponseEntity<?> getImageGenUsage(@RequestHeader("Authorization") String token) {
        try {
            User user = getUserFromToken(token);
            Map<String, Object> response = new HashMap<>();
            response.put("balance", creditService.getBalance(user));
            response.put("planType", user.getPlanType());
            response.put("expiresAt", user.getPlanExpiresAt());
            response.put("canGenerate", true);

            // All models with their credit costs — no per-plan filtering
            List<Map<String, Object>> models = Arrays.stream(
                            ImageGenModel.values())
                    .map(m -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("id", m.name());
                        map.put("displayName", m.getDisplayName());
                        map.put("creditsPerImage", m.getCreditsPerImage());
                        return map;
                    }).toList();
            response.put("availableModels", models);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Unexpected error: " + e.getMessage());
        }
    }

    @GetMapping("/history")
    public ResponseEntity<?> getUserHistory(@RequestHeader("Authorization") String token) {
        try {
            User user = getUserFromToken(token);
            List<SoleImageGen> generations = soleImageGenService.getUserGenerations(user);

            List<Map<String, Object>> imagesList = new ArrayList<>();
            for (SoleImageGen img : generations) {
                Map<String, Object> imgData = new HashMap<>();
                imgData.put("id", img.getId());
                imgData.put("imagePath", img.getImagePath());
                imgData.put("prompt", img.getPrompt());
                imgData.put("negativePrompt", img.getNegativePrompt());
                imgData.put("resolution", img.getResolution());
                imgData.put("createdAt", img.getCreatedAt());
                imagesList.add(imgData);
            }

            return ResponseEntity.ok(imagesList);

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