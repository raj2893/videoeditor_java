package com.example.videoeditor.controller;

import com.example.videoeditor.dto.VideoFilterJobRequest;
import com.example.videoeditor.dto.VideoFilterJobResponse;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.service.VideoFilterJobService;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/filter-jobs")
@RequiredArgsConstructor
public class VideoFilterJobController {

    private final VideoFilterJobService jobService;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    // ===================== CREATE FILTER JOB =====================
    @PostMapping("/from-upload/{uploadId}")
    public ResponseEntity<?> createJobFromUpload(
        @RequestHeader("Authorization") String token,
        @PathVariable Long uploadId,
        @RequestBody VideoFilterJobRequest request
    ) {
        try {
            User user = getUserFromToken(token);
            VideoFilterJobResponse response = jobService.createJobFromUpload(uploadId, request, user);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Failed to create job: " + e.getMessage());
        }
    }

    // ===================== GET SINGLE JOB =====================
    @GetMapping("/{id}")
    public ResponseEntity<?> getJob(
        @RequestHeader("Authorization") String token,
        @PathVariable Long id
    ) {
        try {
            User user = getUserFromToken(token);
            VideoFilterJobResponse job = jobService.getJob(id, user);
            return ResponseEntity.ok(job);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body("Unauthorized: " + e.getMessage());
        }
    }

    // ===================== LIST FILTER JOBS =====================
    @GetMapping("/my")
    public ResponseEntity<?> getJobsByUser(@RequestHeader("Authorization") String token) {
        try {
            User user = getUserFromToken(token);
            List<VideoFilterJobResponse> jobs = jobService.getJobsByUser(user);
            return ResponseEntity.ok(jobs);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body("Unauthorized: " + e.getMessage());
        }
    }

    // ===================== PROCESS FILTER JOB =====================
    @PostMapping("/{id}/process")
    public ResponseEntity<?> processJob(
        @RequestHeader("Authorization") String token,
        @PathVariable Long id
    ) {
        try {
            User user = getUserFromToken(token);
            jobService.processJob(id, user);
            return ResponseEntity.ok("Job processing started successfully");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Processing failed: " + e.getMessage());
        }
    }

    // ===================== UPDATE FILTER JOB =====================
    @PutMapping("/{id}")
    public ResponseEntity<?> updateJob(
        @RequestHeader("Authorization") String token,
        @PathVariable Long id,
        @RequestBody VideoFilterJobRequest request
    ) {
        try {
            User user = getUserFromToken(token);
            VideoFilterJobResponse response = jobService.updateJob(id, request, user);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Failed to update job: " + e.getMessage());
        }
    }


    // ===================== HELPER =====================
    private User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7)); // strip "Bearer "
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));
    }
}
