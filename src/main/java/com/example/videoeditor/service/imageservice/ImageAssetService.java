package com.example.videoeditor.service.imageservice;

import com.example.videoeditor.entity.imageentity.ImageAsset;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.imagerepository.ImageAssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class ImageAssetService {

    private static final Logger logger = LoggerFactory.getLogger(ImageAssetService.class);

    @Value("${app.base-dir:D:\\Backend\\videoeditor_java}")
    private String baseDir;

    String backgroundRemovalScriptPath = "D:\\Backend\\videoeditor_java\\scripts\\remove_background.py";
    String pythonPath = System.getenv().getOrDefault("PYTHON_PATH", "C:\\Users\\praj1\\AppData\\Local\\Programs\\Python\\Python311\\python.exe");

    private final ImageAssetRepository imageAssetRepository;

    public ImageAssetService(ImageAssetRepository imageAssetRepository) {
        this.imageAssetRepository = imageAssetRepository;
    }

    /**
     * Upload asset (image, icon, background)
     */
    public ImageAsset uploadAsset(User user, MultipartFile file, String assetType) throws IOException {
        logger.info("Uploading asset for user: {}, type: {}", user.getId(), assetType);

        // Validate file
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        
        // Validate image type
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("File must be an image");
        }

        // Create asset directory
        String assetDirPath = baseDir + File.separator + "image_editor" + File.separator + 
                            user.getId() + File.separator + "assets";
        File assetDir = new File(assetDirPath);
        if (!assetDir.exists() && !assetDir.mkdirs()) {
            throw new IOException("Failed to create asset directory");
        }

        // Generate unique filename
        String uniqueFilename = UUID.randomUUID().toString() + extension;
        File assetFile = new File(assetDir, uniqueFilename);
        file.transferTo(assetFile);

        // Get image dimensions
        BufferedImage image = ImageIO.read(assetFile);
        int width = 0;
        int height = 0;
        if (image != null) {
            width = image.getWidth();
            height = image.getHeight();
        }

        // Save to database
        ImageAsset asset = new ImageAsset();
        asset.setUser(user);
        asset.setAssetName(originalFilename);
        asset.setAssetType(assetType != null ? assetType.toUpperCase() : "IMAGE");
        asset.setOriginalFilename(originalFilename);
        asset.setFilePath("image_editor/" + user.getId() + "/assets/" + uniqueFilename);
        asset.setCdnUrl("http://localhost:8080/" + asset.getFilePath());
        asset.setFileSize(file.getSize());
        asset.setMimeType(contentType);
        asset.setWidth(width);
        asset.setHeight(height);

        imageAssetRepository.save(asset);
        logger.info("Asset uploaded successfully: {}", asset.getId());

        return asset;
    }

    /**
     * Get all assets for user
     */
    public List<ImageAsset> getUserAssets(User user) {
        return imageAssetRepository.findByUserOrderByCreatedAtDesc(user);
    }

    /**
     * Get assets by type
     */
    public List<ImageAsset> getUserAssetsByType(User user, String assetType) {
        return imageAssetRepository.findByUserAndAssetType(user, assetType.toUpperCase());
    }

    /**
     * Get single asset
     */
    public ImageAsset getAssetById(User user, Long assetId) {
        return imageAssetRepository.findByIdAndUser(assetId, user)
            .orElseThrow(() -> new IllegalArgumentException("Asset not found"));
    }

    /**
     * Delete asset
     */
    public void deleteAsset(User user, Long assetId) throws IOException {
        ImageAsset asset = imageAssetRepository.findByIdAndUser(assetId, user)
            .orElseThrow(() -> new IllegalArgumentException("Asset not found"));

        // Delete file
        String fullPath = baseDir + File.separator + asset.getFilePath();
        File file = new File(fullPath);
        if (file.exists()) {
            file.delete();
        }

        // Delete from database
        imageAssetRepository.delete(asset);
        logger.info("Asset deleted: {}", assetId);
    }

    /**
     * Process background removal for an existing asset
     */
    public ImageAsset removeBackground(User user, Long assetId) throws IOException, InterruptedException {
        // Get the original asset
        ImageAsset originalAsset = imageAssetRepository.findByIdAndUser(assetId, user)
                .orElseThrow(() -> new IllegalArgumentException("Asset not found"));

        if (!originalAsset.getAssetType().equals("IMAGE")) {
            throw new IllegalArgumentException("Background removal only supported for images");
        }

        // Prepare paths
        String originalPath = baseDir + File.separator + originalAsset.getFilePath();
        File originalFile = new File(originalPath);

        if (!originalFile.exists()) {
            throw new IOException("Original image file not found");
        }

        String assetDirPath = baseDir + File.separator + "image_editor" + File.separator +
                user.getId() + File.separator + "assets";
        File assetDir = new File(assetDirPath);

        if (!assetDir.exists() && !assetDir.mkdirs()) {
            throw new IOException("Failed to create asset directory");
        }

        String outputFileName = "bg_removed_" + UUID.randomUUID().toString() + ".png";
        File outputFile = new File(assetDir, outputFileName);

        // Build python command
        List<String> command = Arrays.asList(
                pythonPath,
                backgroundRemovalScriptPath,
                originalFile.getAbsolutePath(),
                outputFile.getAbsolutePath()
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Background removal failed with exit=" + exitCode + ", logs=" + output);
        }

        if (!outputFile.exists()) {
            throw new IOException("Output file not created: " + outputFile.getAbsolutePath());
        }

        // Get dimensions of processed image
        BufferedImage processedImage = ImageIO.read(outputFile);
        int width = processedImage != null ? processedImage.getWidth() : 0;
        int height = processedImage != null ? processedImage.getHeight() : 0;

        // Save as new asset
        ImageAsset newAsset = new ImageAsset();
        newAsset.setUser(user);
        newAsset.setAssetName("BG Removed - " + originalAsset.getAssetName());
        newAsset.setAssetType("IMAGE");
        newAsset.setOriginalFilename(outputFileName);
        newAsset.setFilePath("image_editor/" + user.getId() + "/assets/" + outputFileName);
        newAsset.setCdnUrl("http://localhost:8080/" + newAsset.getFilePath());
        newAsset.setFileSize(outputFile.length());
        newAsset.setMimeType("image/png");
        newAsset.setWidth(width);
        newAsset.setHeight(height);

        imageAssetRepository.save(newAsset);
        logger.info("Background removed successfully for asset: {}, new asset: {}", assetId, newAsset.getId());

        return newAsset;
    }
}