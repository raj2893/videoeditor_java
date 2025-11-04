package com.example.videoeditor.service;
import com.example.videoeditor.entity.CompressedMedia;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.CompressedMediaRepository;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
public class CompressionService {

    private static final Logger logger = LoggerFactory.getLogger(CompressionService.class);

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final CompressedMediaRepository compressedMediaRepository;

    @Value("${app.base-dir:D:\\Backend\\videoEditor-main}")
    private String baseDir;

    @Value("${python.path:C:\\Users\\raj.p\\AppData\\Local\\Programs\\Python\\Python311\\python.exe}")
    private String pythonPath;

    @Value("${app.compression-script-path:D:\\Backend\\videoEditor-main\\scripts\\compress_media.py}")
    private String compressionScriptPath;

    public CompressionService(
            JwtUtil jwtUtil,
            UserRepository userRepository,
            CompressedMediaRepository compressedMediaRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.compressedMediaRepository = compressedMediaRepository;
    }

    public CompressedMedia uploadMedia(User user, MultipartFile mediaFile, String targetSize) throws IOException {
        logger.info("Uploading media for user: {}", user.getId());

        if (mediaFile == null || mediaFile.isEmpty()) {
            logger.error("MultipartFile is null or empty for user: {}", user.getId());
            throw new IllegalArgumentException("Media file is null or empty");
        }
        if (targetSize == null || !targetSize.matches("\\d+(KB|MB)")) {
            logger.error("Invalid target size format: {}", targetSize);
            throw new IllegalArgumentException("Target size must be in format 'numberKB' or 'numberMB'");
        }

        // Create directories
        String originalDirPath = baseDir + File.separator + "compression" + File.separator + user.getId() + File.separator + "original";
        String processedDirPath = baseDir + File.separator + "compression" + File.separator + user.getId() + File.separator + "processed";

        File originalDir = new File(originalDirPath);
        File processedDir = new File(processedDirPath);

        if (!originalDir.exists() && !originalDir.mkdirs()) {
            logger.error("Failed to create original directory: {}", originalDir.getAbsolutePath());
            throw new IOException("Failed to create original directory");
        }
        if (!processedDir.exists() && !processedDir.mkdirs()) {
            logger.error("Failed to create processed directory: {}", processedDir.getAbsolutePath());
            throw new IOException("Failed to create processed directory");
        }

        // Save input file
        String originalFileName = mediaFile.getOriginalFilename();
        File inputFile = new File(originalDir, originalFileName);
        mediaFile.transferTo(inputFile);
        logger.debug("Saved input media to: {}", inputFile.getAbsolutePath());

        if (inputFile.length() == 0) {
            logger.error("Input file is empty: {}", inputFile.getAbsolutePath());
            throw new IOException("Input file is empty");
        }

        // Generate local URL
        String originalPath = "compression/" + user.getId() + "/original/" + originalFileName;
        String originalCdnUrl = "http://localhost:8080/" + originalPath;

        // Save metadata
        CompressedMedia compressedMedia = new CompressedMedia();
        compressedMedia.setUser(user);
        compressedMedia.setOriginalFileName(originalFileName);
        compressedMedia.setOriginalPath(originalPath);
        compressedMedia.setOriginalCdnUrl(originalCdnUrl);
        compressedMedia.setTargetSize(targetSize);
        compressedMedia.setStatus("UPLOADED");
        compressedMediaRepository.save(compressedMedia);

        logger.info("Saved metadata for user: {}, media: {}", user.getId(), originalFileName);
        return compressedMedia;
    }

    public CompressedMedia compressMedia(User user, Long mediaId) throws IOException, InterruptedException {
        logger.info("Compressing media for user: {}, mediaId: {}", user.getId(), mediaId);

        CompressedMedia compressedMedia = compressedMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    logger.error("Media not found for id: {}", mediaId);
                    return new IllegalArgumentException("Media not found");
                });

        if (!compressedMedia.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to compress media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to compress this media");
        }

        // Prepare file paths
        String inputFilePath = baseDir + File.separator + compressedMedia.getOriginalPath();
        String outputFileName = "compressed_" + compressedMedia.getOriginalFileName();
        String outputFilePath = baseDir + File.separator + "compression" + File.separator + user.getId() + File.separator + "processed" + File.separator + outputFileName;

        File inputFile = new File(inputFilePath);
        File outputFile = new File(outputFilePath);

        if (!inputFile.exists() || inputFile.length() == 0) {
            logger.error("Input file is missing or empty: {}", inputFilePath);
            throw new IOException("Input file is missing or empty");
        }

        File scriptFile = new File(compressionScriptPath);
        if (!scriptFile.exists()) {
            logger.error("Compression script not found: {}", compressionScriptPath);
            throw new IOException("Compression script not found");
        }

        // Create output directory
        Files.createDirectories(outputFile.toPath().getParent());

        // Execute compression script
        List<String> command = Arrays.asList(
                pythonPath,
                compressionScriptPath,
            inputFile.getAbsolutePath().replace("\\", "/"),
            outputFile.getAbsolutePath(),
                compressedMedia.getTargetSize()
        );

        logger.debug("Executing command: {}", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                logger.debug("Process output: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            logger.error("Compression failed for user: {}, mediaId: {}, exit code: {}, output: {}", user.getId(), mediaId, exitCode, output);
            compressedMedia.setStatus("FAILED");
            compressedMedia.setErrorMessage("Compression failed: " + output);
            compressedMediaRepository.save(compressedMedia);
            throw new IOException("Compression failed with exit code: " + exitCode);
        }

        if (!outputFile.exists()) {
            logger.error("Output file not created: {}", outputFile.getAbsolutePath());
            compressedMedia.setStatus("FAILED");
            compressedMedia.setErrorMessage("Output file not created");
            compressedMediaRepository.save(compressedMedia);
            throw new IOException("Output file not created");
        }

        // Generate processed URL
        String processedPath = "compression/" + user.getId() + "/processed/" + outputFileName;
        String processedCdnUrl = "http://localhost:8080/" + processedPath;

        // Update metadata
        compressedMedia.setProcessedFileName(outputFileName);
        compressedMedia.setProcessedPath(processedPath);
        compressedMedia.setProcessedCdnUrl(processedCdnUrl);
        compressedMedia.setStatus("SUCCESS");
        compressedMediaRepository.save(compressedMedia);

        logger.info("Successfully compressed media for user: {}, mediaId: {}", user.getId(), mediaId);
        return compressedMedia;
    }

    public User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    logger.error("User not found for email extracted from token");
                    return new RuntimeException("User not found");
                });
    }

    public List<CompressedMedia> getUserCompressedMedia(User user) {
        return compressedMediaRepository.findByUser(user);
    }

    public CompressedMedia updateTargetSize(User user, Long mediaId, String newTargetSize) throws IllegalArgumentException {

        if (newTargetSize == null || !newTargetSize.matches("\\d+(KB|MB)")) {
            throw new IllegalArgumentException("Target size must be in format 'numberKB' or 'numberMB'");
        }

        CompressedMedia compressedMedia = compressedMediaRepository.findById(mediaId)
            .orElseThrow(() -> {
                return new IllegalArgumentException("Media not found");
            });

        if (!compressedMedia.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Not authorized to update this media");
        }

        if (!compressedMedia.getStatus().equals("UPLOADED")) {
            throw new IllegalStateException("Media target size can only be updated in UPLOADED state");
        }

        compressedMedia.setTargetSize(newTargetSize);
        compressedMediaRepository.save(compressedMedia);
        return compressedMedia;
    }
}