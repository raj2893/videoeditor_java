package com.example.videoeditor.service.imageservice;

import com.example.videoeditor.entity.imageentity.ImageElement;
import com.example.videoeditor.repository.imagerepository.ImageElementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Service
public class ImageElementService {

    private static final Logger logger = LoggerFactory.getLogger(ImageElementService.class);

    @Value("${app.base-dir:D:\\Backend\\videoEditor-main}")
    private String baseDir;

    private final ImageElementRepository elementRepository;

    public ImageElementService(ImageElementRepository elementRepository) {
        this.elementRepository = elementRepository;
    }

    /**
     * Upload new element (Admin only)
     */
    @Transactional
    public ImageElement uploadElement(MultipartFile file, String name, String category, String tags) throws IOException {
        logger.info("Uploading element: {}", name);

        // Validate file
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new IllegalArgumentException("Invalid filename");
        }

        // Get file extension
        String extension = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        if (!extension.matches("\\.(png|jpg|jpeg|svg)")) {
            throw new IllegalArgumentException("Only PNG, JPG, and SVG files are allowed");
        }

        // Create elements directory
        String elementsDir = baseDir + File.separator + "image_editor" + File.separator + "elements";
        File directory = new File(elementsDir);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Failed to create elements directory");
        }

        // Generate unique filename
        String filename = UUID.randomUUID().toString() + extension;
        String filePath = "image_editor/elements/" + filename;
        Path targetPath = Paths.get(baseDir, "image_editor", "elements", filename);

        // Save file
        Files.copy(file.getInputStream(), targetPath);

        // Get image dimensions
        Integer width = null;
        Integer height = null;
        if (!extension.equals(".svg")) {
            try {
                BufferedImage img = ImageIO.read(targetPath.toFile());
                if (img != null) {
                    width = img.getWidth();
                    height = img.getHeight();
                }
            } catch (Exception e) {
                logger.warn("Could not read image dimensions: {}", e.getMessage());
            }
        }

        // Create element record
        ImageElement element = new ImageElement();
        element.setName(name != null ? name : originalFilename);
        element.setCategory(category != null ? category : "general");
        element.setFilePath(filePath);
        element.setCdnUrl("http://localhost:8080/" + filePath);
        element.setFileFormat(extension.substring(1).toUpperCase());
        element.setWidth(width);
        element.setHeight(height);
        element.setFileSize(file.getSize());
        element.setTags(tags);
        element.setIsActive(true);

        elementRepository.save(element);
        logger.info("Element uploaded successfully: {}", element.getId());

        return element;
    }

    /**
     * Get all active elements
     */
    public List<ImageElement> getAllActiveElements() {
        return elementRepository.findByIsActiveTrueOrderByDisplayOrderAscCreatedAtDesc();
    }

    /**
     * Get elements by category
     */
    public List<ImageElement> getElementsByCategory(String category) {
        return elementRepository.findByCategoryAndIsActiveTrueOrderByDisplayOrderAsc(category);
    }

    /**
     * Get element by ID
     */
    public ImageElement getElementById(Long id) {
        return elementRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Element not found"));
    }

    /**
     * Update element
     */
    @Transactional
    public ImageElement updateElement(Long id, String name, String category, String tags, Boolean isActive, Integer displayOrder) {
        ImageElement element = getElementById(id);

        if (name != null) element.setName(name);
        if (category != null) element.setCategory(category);
        if (tags != null) element.setTags(tags);
        if (isActive != null) element.setIsActive(isActive);
        if (displayOrder != null) element.setDisplayOrder(displayOrder);

        return elementRepository.save(element);
    }

    /**
     * Delete element
     */
    @Transactional
    public void deleteElement(Long id) throws IOException {
        ImageElement element = getElementById(id);

        // Delete file
        Path filePath = Paths.get(baseDir, element.getFilePath());
        Files.deleteIfExists(filePath);

        // Delete record
        elementRepository.delete(element);
        logger.info("Element deleted: {}", id);
    }

    /**
     * Get all elements (including inactive) - Admin only
     */
    public List<ImageElement> getAllElements() {
        return elementRepository.findAll();
    }
}