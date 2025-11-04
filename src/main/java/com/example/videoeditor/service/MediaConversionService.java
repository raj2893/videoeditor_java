package com.example.videoeditor.service;

import com.example.videoeditor.entity.ConvertedMedia;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.ConvertedMediaRepository;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

@Service
public class MediaConversionService {
    private static final Logger logger = LoggerFactory.getLogger(MediaConversionService.class);
    private static final List<String> VIDEO_FORMATS = Arrays.asList("MP4", "AVI", "MKV", "MOV", "WEBM", "FLV", "WMV");
    private static final List<String> IMAGE_FORMATS = Arrays.asList("PNG", "JPG", "BMP", "GIF", "TIFF", "WEBP");

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final ConvertedMediaRepository convertedMediaRepository;

    @Value("${app.base-dir:D:\\Backend\\videoEditor-main}")
    private String baseDir;

    @Value("${python.path:C:\\Users\\raj.p\\AppData\\Local\\Programs\\Python\\Python311\\python.exe}")
    private String pythonPath;

    @Value("${app.ffmpeg-path:C:\\Users\\raj.p\\Downloads\\ffmpeg-2025-02-17-git-b92577405b-full_build\\bin\\ffmpeg.exe}")
    private String ffmpegPath;

    @Value("${app.conversion-script-path:D:\\Backend\\videoEditor-main\\scripts\\convert_media.py}")
    private String conversionScriptPath;

    public MediaConversionService(
            JwtUtil jwtUtil,
            UserRepository userRepository,
            ConvertedMediaRepository convertedMediaRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.convertedMediaRepository = convertedMediaRepository;
    }

    public ConvertedMedia uploadMedia(User user, MultipartFile mediaFile, String targetFormat) throws IOException {
        logger.info("Uploading media for user: {}", user.getId());

        String mediaType = determineMediaType(mediaFile);
        validateInputs(mediaFile, mediaType, targetFormat);

        String originalDirPath = baseDir + File.separator + "conversions" + File.separator + user.getId() + File.separator + "original";
        File originalDir = new File(originalDirPath);
        if (!originalDir.exists() && !originalDir.mkdirs()) {
            logger.error("Failed to create original directory: {}", originalDir.getAbsolutePath());
            throw new IOException("Failed to create original directory");
        }

        String originalFileName = mediaFile.getOriginalFilename();
        File inputFile = new File(originalDir, originalFileName);
        mediaFile.transferTo(inputFile);
        logger.debug("Saved input media to: {}", inputFile.getAbsolutePath());

        if (inputFile.length() == 0) {
            logger.error("Input file is empty: {}", inputFile.getAbsolutePath());
            throw new IOException("Input file is empty");
        }

        String originalPath = "conversions/" + user.getId() + "/original/" + originalFileName;
        String originalCdnUrl = "http://localhost:8080/" + originalPath;

        ConvertedMedia convertedMedia = new ConvertedMedia();
        convertedMedia.setUser(user);
        convertedMedia.setOriginalFileName(originalFileName);
        convertedMedia.setOriginalPath(originalPath);
        convertedMedia.setOriginalCdnUrl(originalCdnUrl);
        convertedMedia.setMediaType(mediaType);
        convertedMedia.setTargetFormat(targetFormat.toUpperCase());
        convertedMedia.setStatus("UPLOADED");
        convertedMediaRepository.save(convertedMedia);

        logger.info("Saved metadata for user: {}, media: {}, type: {}", user.getId(), originalFileName, mediaType);
        return convertedMedia;
    }

    public ConvertedMedia convertMedia(User user, Long mediaId) throws IOException, InterruptedException {
        logger.info("Converting media for user: {}, mediaId: {}", user.getId(), mediaId);

        ConvertedMedia convertedMedia = convertedMediaRepository.findById(mediaId)
            .orElseThrow(() -> {
                logger.error("Media not found for id: {}", mediaId);
                return new IllegalArgumentException("Media not found");
            });

        if (!convertedMedia.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to convert media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to convert this media");
        }

        convertedMedia.setStatus("PROCESSING");
        convertedMediaRepository.save(convertedMedia);

        String inputFilePath = baseDir + File.separator + convertedMedia.getOriginalPath();
        File inputFile = new File(inputFilePath);
        if (!inputFile.exists() || inputFile.length() == 0) {
            logger.error("Input file is missing or empty: {}", inputFilePath);
            convertedMedia.setStatus("FAILED");
            convertedMedia.setErrorMessage("Input file is missing or empty");
            convertedMediaRepository.save(convertedMedia);
            throw new IOException("Input file is missing or empty");
        }

        String processedDirPath = baseDir + File.separator + "conversions" + File.separator + user.getId() + File.separator + "processed";
        File processedDir = new File(processedDirPath);
        if (!processedDir.exists() && !processedDir.mkdirs()) {
            logger.error("Failed to create processed directory: {}", processedDir.getAbsolutePath());
            convertedMedia.setStatus("FAILED");
            convertedMedia.setErrorMessage("Failed to create processed directory");
            convertedMediaRepository.save(convertedMedia);
            throw new IOException("Failed to create processed directory");
        }

        String originalNameWithoutExt = convertedMedia.getOriginalFileName().replaceFirst("[.][^.]+$", "");
        String outputFileName;
        if (convertedMedia.getMediaType().equals("VIDEO")) {
            outputFileName = "converted_" + originalNameWithoutExt + "." + convertedMedia.getTargetFormat().toLowerCase();
        } else {
            outputFileName = "converted_" + convertedMedia.getOriginalFileName();
        }
        String outputFilePath = processedDirPath + File.separator + outputFileName;
        File outputFile = new File(outputFilePath);

        if (convertedMedia.getMediaType().equals("VIDEO")) {
            convertVideo(inputFile, outputFile, convertedMedia.getTargetFormat(), mediaId);
        } else {
            convertImage(inputFile, outputFile, convertedMedia.getTargetFormat(), mediaId);
        }

        boolean fileReady = false;
        int retries = 5;
        long waitTimeMs = 1000;
        for (int i = 0; i < retries; i++) {
            if (outputFile.exists() && outputFile.length() > 0 && outputFile.canRead()) {
                fileReady = true;
                break;
            }
            logger.debug("Output file not ready, retrying ({}/{}): {}", i + 1, retries, outputFile.getAbsolutePath());
            Thread.sleep(waitTimeMs);
        }

        if (!fileReady) {
            logger.error("Output file not created or empty after retries: {}", outputFile.getAbsolutePath());
            convertedMedia.setStatus("FAILED");
            convertedMedia.setErrorMessage("Output file not created or empty after retries");
            convertedMediaRepository.save(convertedMedia);
            throw new IOException("Output file not created or empty after retries");
        }

        String processedPath = "conversions/" + user.getId() + "/processed/" + outputFileName;
        String processedCdnUrl = "http://localhost:8080/" + processedPath;

        convertedMedia.setProcessedFileName(outputFileName);
        convertedMedia.setProcessedPath(processedPath);
        convertedMedia.setProcessedCdnUrl(processedCdnUrl);
        convertedMedia.setStatus("SUCCESS");
        convertedMediaRepository.save(convertedMedia);

        logger.info("Successfully converted media for user: {}, mediaId: {}", user.getId(), mediaId);
        return convertedMedia;
    }

    public List<ConvertedMedia> getUserConvertedMedia(User user) {
        return convertedMediaRepository.findByUser(user);
    }

    public User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    logger.error("User not found for email extracted from token");
                    return new RuntimeException("User not found");
                });
    }

    private String determineMediaType(MultipartFile mediaFile) throws IOException {
        String contentType = mediaFile.getContentType();
        if (contentType == null) {
            throw new IllegalArgumentException("Cannot determine media type");
        }
        if (contentType.startsWith("video/")) {
            return "VIDEO";
        } else if (contentType.startsWith("image/")) {
            return "IMAGE";
        } else {
            throw new IllegalArgumentException("Unsupported media type: " + contentType);
        }
    }

    private void validateInputs(MultipartFile mediaFile, String mediaType, String targetFormat) {
        if (mediaFile == null || mediaFile.isEmpty()) {
            logger.error("MultipartFile is null or empty");
            throw new IllegalArgumentException("Media file is null or empty");
        }
        if (targetFormat == null || targetFormat.trim().isEmpty()) {
            logger.error("Target format is null or empty");
            throw new IllegalArgumentException("Target format is required");
        }
        String formatUpper = targetFormat.toUpperCase();
        if (mediaType.equals("VIDEO") && !VIDEO_FORMATS.contains(formatUpper)) {
            logger.error("Invalid video format: {}", targetFormat);
            throw new IllegalArgumentException("Invalid video format. Supported: " + String.join(", ", VIDEO_FORMATS));
        }
        if (mediaType.equals("IMAGE") && !IMAGE_FORMATS.contains(formatUpper)) {
            logger.error("Invalid image format: {}", targetFormat);
            throw new IllegalArgumentException("Invalid image format. Supported: " + String.join(", ", IMAGE_FORMATS));
        }
    }

    private void convertVideo(File inputFile, File outputFile, String targetFormat, Long mediaId) throws IOException, InterruptedException {
        // Validate Python executable
        File pythonFile = new File(pythonPath);
        if (!pythonFile.exists()) {
            logger.error("Python executable not found: {}", pythonPath);
            throw new IOException("Python executable not found: " + pythonPath);
        }

        // Validate conversion script
        File scriptFile = new File(conversionScriptPath);
        if (!scriptFile.exists()) {
            logger.error("Conversion script not found: {}", scriptFile.getAbsolutePath());
            throw new IOException("Conversion script not found");
        }

        // Validate FFmpeg executable
        File ffmpegFile = new File(ffmpegPath);
        if (!ffmpegFile.exists()) {
            logger.error("FFmpeg executable not found: {}", ffmpegPath);
            throw new IOException("FFmpeg executable not found: " + ffmpegPath);
        }

        // Ensure output file has correct extension for target format
        String outputPath = outputFile.getAbsolutePath();
        String targetExt = targetFormat.toLowerCase();
        if (!outputPath.toLowerCase().endsWith("." + targetExt)) {
            outputPath = outputPath.substring(0, outputPath.lastIndexOf('.')) + "." + targetExt;
            outputFile = new File(outputPath);
        }

        List<String> command = Arrays.asList(
            pythonPath,
            conversionScriptPath,
            inputFile.getAbsolutePath(),
            outputFile.getAbsolutePath(),
            targetFormat.toUpperCase()
        );

        logger.debug("Executing video conversion command: {}", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        // Set working directory to help with any relative path issues
        pb.directory(scriptFile.getParentFile());

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        StringBuilder errorOutput = new StringBuilder();

        try (BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
             BufferedReader stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {

            String line;
            while ((line = stdoutReader.readLine()) != null) {
                output.append(line).append("\n");
                logger.debug("Python stdout: {}", line);
            }
            while ((line = stderrReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
                logger.debug("Python stderr: {}", line);
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            logger.error("Video conversion failed for mediaId: {}, exit code: {}", mediaId, exitCode);
            logger.error("Error output: {}", errorOutput.toString());
            throw new IOException("Video conversion failed with exit code: " + exitCode + "\nError: " + errorOutput.toString());
        }

        // Verify output file was created successfully
        if (!outputFile.exists() || outputFile.length() == 0) {
            logger.error("Output video file not created or empty for mediaId: {}", mediaId);
            throw new IOException("Output video file not created or empty after conversion");
        }

        logger.info("Video conversion successful for mediaId: {}, output size: {} bytes", mediaId, outputFile.length());
    }

    private void convertImage(File inputFile, File outputFile, String targetFormat, Long mediaId) throws IOException {
        String format = targetFormat.toLowerCase().replace("jpg", "jpeg"); // Normalize JPG to JPEG
        try {
            BufferedImage image = ImageIO.read(inputFile);
            if (image == null) {
                logger.error("Failed to read image for mediaId: {}", mediaId);
                throw new IOException("Failed to read image");
            }

            // Convert to RGB color space to avoid colorspace issues (e.g., CMYK)
            BufferedImage rgbImage = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB
            );
            Graphics2D g2d = rgbImage.createGraphics();
            g2d.drawImage(image, 0, 0, null);
            g2d.dispose();

            // Ensure output file has correct extension
            String outputPath = outputFile.getAbsolutePath();
            String ext = format.equals("jpeg") ? "jpg" : format.toLowerCase();
            if (!outputPath.toLowerCase().endsWith("." + ext)) {
                outputPath = outputPath.substring(0, outputPath.lastIndexOf('.')) + "." + ext;
                outputFile = new File(outputPath);
            }

            // Ensure parent directories exist
            File parentDir = outputFile.getParentFile();
            if (!parentDir.exists() && !parentDir.mkdirs()) {
                logger.error("Failed to create parent directories for: {}", outputPath);
                throw new IOException("Failed to create parent directories");
            }

            // Write image
            if (format.equals("jpeg")) {
                Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
                if (!writers.hasNext()) {
                    logger.error("No JPEG writer found for mediaId: {}", mediaId);
                    throw new IOException("No JPEG writer available");
                }
                ImageWriter writer = writers.next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.9f); // Set quality to 90%
                try (FileImageOutputStream output = new FileImageOutputStream(outputFile)) {
                    writer.setOutput(output);
                    writer.write(null, new IIOImage(rgbImage, null, null), param);
                    writer.dispose();
                }
            } else {
                boolean written = ImageIO.write(rgbImage, format, outputFile);
                if (!written) {
                    logger.error("Failed to write image for mediaId: {}, format: {}", mediaId, format);
                    throw new IOException("Failed to write image in format: " + format);
                }
            }

            // Verify file exists and is not empty
            if (!outputFile.exists() || outputFile.length() == 0) {
                logger.error("Output image file not created or empty for mediaId: {}", mediaId);
                throw new IOException("Output image file not created or empty");
            }
        } catch (IOException e) {
            logger.error("Image conversion failed for mediaId: {}: {}", mediaId, e.getMessage());
            throw new IOException("Image conversion failed: " + e.getMessage());
        }
    }

    public ConvertedMedia updateTargetFormat(User user, Long mediaId, String newTargetFormat) throws IOException {
        logger.info("Updating target format for user: {}, mediaId: {}, newTargetFormat: {}", user.getId(), mediaId, newTargetFormat);

        ConvertedMedia convertedMedia = convertedMediaRepository.findById(mediaId)
            .orElseThrow(() -> {
                logger.error("Media not found for id: {}", mediaId);
                return new IllegalArgumentException("Media not found");
            });

        if (!convertedMedia.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to update media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to update this media");
        }

        String mediaType = convertedMedia.getMediaType();
        String formatUpper = newTargetFormat.toUpperCase();
        if (mediaType.equals("VIDEO") && !VIDEO_FORMATS.contains(formatUpper)) {
            logger.error("Invalid video format: {}", newTargetFormat);
            throw new IllegalArgumentException("Invalid video format. Supported: " + String.join(", ", VIDEO_FORMATS));
        }
        if (mediaType.equals("IMAGE") && !IMAGE_FORMATS.contains(formatUpper)) {
            logger.error("Invalid image format: {}", newTargetFormat);
            throw new IllegalArgumentException("Invalid image format. Supported: " + String.join(", ", IMAGE_FORMATS));
        }

        convertedMedia.setTargetFormat(formatUpper);
        convertedMediaRepository.save(convertedMedia);

        logger.info("Successfully updated target format for mediaId: {} to {}", mediaId, newTargetFormat);
        return convertedMedia;
    }
}