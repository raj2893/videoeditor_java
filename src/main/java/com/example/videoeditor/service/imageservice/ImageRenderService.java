package com.example.videoeditor.service.imageservice;

import com.example.videoeditor.dto.imagedto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class ImageRenderService {

    private static final Logger logger = LoggerFactory.getLogger(ImageRenderService.class);

    @Value("${app.base-dir:D:\\Backend\\videoEditor-main}")
    private String baseDir;

    @Value("${app.imagemagick-path:C:\\Program Files\\ImageMagick-7.1.2-Q16-HDRI\\magick.exe}")
    private String imageMagickPath;

    private final ObjectMapper objectMapper;

    public ImageRenderService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Main method to render design JSON to image
     */
    public String renderDesign(String designJson, String format, Integer quality, Long userId, Long projectId) 
            throws IOException, InterruptedException {
        
        logger.info("Starting render for project: {}, format: {}", projectId, format);
        System.setProperty("MAGICK_CONFIGURE_PATH", "");
        
        // Parse design JSON
        DesignDTO design = objectMapper.readValue(designJson, DesignDTO.class);
        
        // Create temp and output directories
        String tempDirPath = baseDir + File.separator + "image_editor" + File.separator + userId + 
                            File.separator + "temp_" + projectId;
        String outputDirPath = baseDir + File.separator + "image_editor" + File.separator + userId + 
                              File.separator + "exports";
        
        File tempDir = new File(tempDirPath);
        File outputDir = new File(outputDirPath);
        
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("Failed to create temp directory");
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Failed to create output directory");
        }
        
        // Sort layers by zIndex
        List<LayerDTO> sortedLayers = new ArrayList<>(design.getLayers());
        sortedLayers.sort(Comparator.comparingInt(l -> l.getZIndex() != null ? l.getZIndex() : 0));
        
        // Step 1: Create base canvas
        String baseCanvasPath = tempDirPath + File.separator + "base_canvas.png";
        createBaseCanvas(design.getCanvas(), baseCanvasPath);
        
        // Step 2: Process each layer and composite
        String currentImage = baseCanvasPath;
        int layerIndex = 0;
        
        for (LayerDTO layer : sortedLayers) {
            String layerOutputPath = tempDirPath + File.separator + "layer_" + layerIndex + "_composite.png";
            currentImage = processAndCompositeLayer(layer, currentImage, layerOutputPath, tempDirPath, 
                                                   design.getCanvas().getWidth(), design.getCanvas().getHeight());
            layerIndex++;
        }
        
        // Step 3: Export final image in desired format
        String outputFileName = "project_" + projectId + "_" + System.currentTimeMillis() + "." + format.toLowerCase();
        String finalOutputPath = outputDirPath + File.separator + outputFileName;
        
        exportFinalImage(currentImage, finalOutputPath, format, quality);
        
        // Cleanup temp files
        cleanupTempDirectory(tempDir);
        
        // Return relative path for CDN URL
        String relativePath = "image_editor/" + userId + "/exports/" + outputFileName;
        logger.info("Render completed successfully: {}", relativePath);
        
        return relativePath;
    }

    /**
     * Create base canvas with background color
     */
    private void createBaseCanvas(CanvasDTO canvas, String outputPath) throws IOException, InterruptedException {
        String bgColor = canvas.getBackgroundColor() != null ? canvas.getBackgroundColor() : "#FFFFFF";

        List<String> command = new ArrayList<>();
        command.add(imageMagickPath);
        command.add("-size");
        command.add(canvas.getWidth() + "x" + canvas.getHeight());
        command.add("xc:" + bgColor);
        command.add("-set");
        command.add("colorspace");
        command.add("sRGB");
        command.add(outputPath);

        executeImageMagickCommand(command, "Create base canvas");
    }

    /**
     * Process single layer and composite it onto current image
     */
    private String processAndCompositeLayer(LayerDTO layer, String baseImagePath, String outputPath, 
                                           String tempDirPath, Integer canvasWidth, Integer canvasHeight) 
            throws IOException, InterruptedException {
        
        logger.debug("Processing layer: {} of type: {}", layer.getId(), layer.getType());
        
        String layerImagePath;
        
        switch (layer.getType().toLowerCase()) {
            case "image":
                layerImagePath = processImageLayer(layer, tempDirPath, canvasWidth, canvasHeight);
                break;
            case "text":
                layerImagePath = processTextLayer(layer, tempDirPath, canvasWidth, canvasHeight);
                break;
            case "shape":
                layerImagePath = processShapeLayer(layer, tempDirPath, canvasWidth, canvasHeight);
                break;
            case "background":
                layerImagePath = processBackgroundLayer(layer, tempDirPath, canvasWidth, canvasHeight);
                break;
            default:
                logger.warn("Unknown layer type: {}, skipping", layer.getType());
                return baseImagePath;
        }
        
        // Composite layer onto base image
        compositeImages(baseImagePath, layerImagePath, outputPath, layer);
        
        // Delete intermediate layer file
        Files.deleteIfExists(Paths.get(layerImagePath));
        
        return outputPath;
    }

    /**
     * Process IMAGE layer
     */
    /**
     * Process IMAGE layer
     */
    private String processImageLayer(LayerDTO layer, String tempDirPath, Integer canvasWidth, Integer canvasHeight)
        throws IOException, InterruptedException {

        String outputPath = tempDirPath + File.separator + "layer_" + layer.getId() + "_image.png";
        String sourceImagePath = downloadOrCopyImage(layer.getSrc(), tempDirPath, layer.getId());

        List<String> command = new ArrayList<>();
        command.add(imageMagickPath);
        command.add(sourceImagePath);
        command.add("-set");
        command.add("colorspace");
        command.add("sRGB");

        // Get natural dimensions from source image
        BufferedImage sourceImg = ImageIO.read(new File(sourceImagePath));
        double naturalWidth = sourceImg.getWidth();
        double naturalHeight = sourceImg.getHeight();

        // Get scale and crop values
        double scale = layer.getScale() != null ? layer.getScale() : 1.0;
        double cropTop = layer.getCropTop() != null ? layer.getCropTop() : 0;
        double cropRight = layer.getCropRight() != null ? layer.getCropRight() : 0;
        double cropBottom = layer.getCropBottom() != null ? layer.getCropBottom() : 0;
        double cropLeft = layer.getCropLeft() != null ? layer.getCropLeft() : 0;

        // Calculate dimensions
        double cropHorizPercent = cropLeft + cropRight;
        double cropVertPercent = cropTop + cropBottom;
        double scaledWidthBeforeCrop = layer.getWidth() / ((100 - cropHorizPercent) / 100.0);
        double scaledHeightBeforeCrop = layer.getHeight() / ((100 - cropVertPercent) / 100.0);

        // Scale the image
        command.add("-resize");
        command.add(((int)Math.round(scaledWidthBeforeCrop)) + "x" + ((int)Math.round(scaledHeightBeforeCrop)) + "!");

        // Apply crop
        if (cropTop > 0 || cropRight > 0 || cropBottom > 0 || cropLeft > 0) {
            int cropX = (int)Math.round((cropLeft / 100.0) * scaledWidthBeforeCrop);
            int cropY = (int)Math.round((cropTop / 100.0) * scaledHeightBeforeCrop);
            int cropW = (int)Math.round(scaledWidthBeforeCrop * (1 - (cropLeft + cropRight) / 100.0));
            int cropH = (int)Math.round(scaledHeightBeforeCrop * (1 - (cropTop + cropBottom) / 100.0));

            command.add("-crop");
            command.add(cropW + "x" + cropH + "+" + cropX + "+" + cropY);
            command.add("+repage");
        }

        // **FIXED: Rotation without extent - let it expand naturally**
        if (layer.getRotation() != null && layer.getRotation() != 0) {
            command.add("-background");
            command.add("transparent");
            command.add("-rotate");
            command.add(String.valueOf(layer.getRotation()));
            // Don't use +repage here - we need the virtual canvas info
        }

        // Apply opacity
        if (layer.getOpacity() != null && layer.getOpacity() < 1.0) {
            command.add("-channel");
            command.add("Alpha");
            command.add("-evaluate");
            command.add("multiply");
            command.add(String.valueOf(layer.getOpacity()));
            command.add("+channel");
        }

        // Apply filters and shadow
        applyFilters(command, layer.getFilters());
        if (layer.getShadow() != null) {
            applyShadow(command, layer.getShadow());
        }

        command.add(outputPath);
        executeImageMagickCommand(command, "Process image layer");

        if (!sourceImagePath.equals(layer.getSrc())) {
            Files.deleteIfExists(Paths.get(sourceImagePath));
        }

        return outputPath;
    }

    /**
     * Process TEXT layer
     */
    private String processTextLayer(LayerDTO layer, String tempDirPath,
                                    Integer canvasWidth, Integer canvasHeight)
        throws IOException, InterruptedException {

        // Check if multi-color text
        if (layer.getTextSegments() != null && !layer.getTextSegments().isEmpty()) {
            return processMultiColorText(layer, tempDirPath, canvasWidth, canvasHeight);
        }

        String outputPath = tempDirPath + File.separator + "layer_" + layer.getId() + "_text.png";

        // Calculate dimensions based on font size and text content
        int fontSize = layer.getFontSize() != null ? layer.getFontSize() : 32;
        String text = layer.getText() != null ? layer.getText() : "Text";

        // Apply text transform to get actual displayed text
        if ("uppercase".equalsIgnoreCase(layer.getTextTransform())) text = text.toUpperCase();
        if ("lowercase".equalsIgnoreCase(layer.getTextTransform())) text = text.toLowerCase();
        if ("capitalize".equalsIgnoreCase(layer.getTextTransform())) text = capitalize(text);

        // Estimate text dimensions (width = chars × fontSize × 0.6, height = fontSize × 1.5)
        int estimatedWidth = (int)(text.length() * fontSize * 0.6);
        int estimatedHeight = (int)(fontSize * 1.5);

        // Use background dimensions if they exist, otherwise use estimated text dimensions
        int width = (layer.getBackgroundWidth() != null && layer.getBackgroundWidth() > 0)
            ? layer.getBackgroundWidth().intValue()
            : estimatedWidth;
        int height = (layer.getBackgroundHeight() != null && layer.getBackgroundHeight() > 0)
            ? layer.getBackgroundHeight().intValue()
            : estimatedHeight;

        // Calculate extra padding for underline
        int extraBottomPadding = 0;
        if ("underline".equalsIgnoreCase(layer.getTextDecoration())) {
            extraBottomPadding = Math.max(8, (layer.getFontSize() != null ? layer.getFontSize() : 32) / 4);
        }

        // Add extra padding for border stroke to prevent cutting
        int borderStrokeWidth = (layer.getBackgroundBorderWidth() != null) ? layer.getBackgroundBorderWidth() : 0;
        int extraPadding = borderStrokeWidth * 2; // Padding on all sides

        List<String> cmd = new ArrayList<>();
        cmd.add(imageMagickPath);
        cmd.add("-size");
        cmd.add((width + extraPadding) + "x" + (height + extraBottomPadding + extraPadding));
        cmd.add("xc:transparent");
        cmd.add("-set");
        cmd.add("colorspace");
        cmd.add("sRGB");

        // ----- background behind text with border (FIXED) -----
        if (layer.getBackgroundColor() != null && layer.getBackgroundOpacity() != null
            && layer.getBackgroundOpacity() > 0) {

            String bgColor = layer.getBackgroundColor() +
                String.format("%02x", (int)(layer.getBackgroundOpacity() * 255));

            int bgWidth = width;
            int bgHeight = height + extraBottomPadding;

            // Use custom background dimensions if provided
            if (layer.getBackgroundWidth() != null && layer.getBackgroundWidth() > 0) {
                bgWidth = layer.getBackgroundWidth().intValue();
                width = bgWidth;
            }
            if (layer.getBackgroundHeight() != null && layer.getBackgroundHeight() > 0) {
                bgHeight = layer.getBackgroundHeight().intValue();
                height = bgHeight;
            }

            int borderRadius = layer.getBackgroundBorderRadius() != null ?
                layer.getBackgroundBorderRadius() : 8;

            cmd.add("-fill");
            cmd.add(bgColor);

            // Add border if specified
            if (layer.getBackgroundBorder() != null && layer.getBackgroundBorderWidth() != null) {
                cmd.add("-stroke");
                cmd.add(layer.getBackgroundBorder());
                cmd.add("-strokewidth");
                cmd.add(String.valueOf(layer.getBackgroundBorderWidth()));
            } else {
                cmd.add("-stroke");
                cmd.add("none");
            }

            // FIXED: Offset the rectangle by half the stroke width to prevent cutting
            int offset = borderStrokeWidth;
            cmd.add("-draw");
            cmd.add(String.format("roundrectangle %d,%d %d,%d %d,%d",
                offset, offset,
                bgWidth + offset - 1, bgHeight + offset - 1,
                borderRadius, borderRadius));
        }

        // Reset stroke for text
        cmd.add("-stroke");
        cmd.add("none");

        // ----- font -----
        String fontPath = resolveFontPath(
            layer.getFontFamily(),
            layer.getFontWeight(),
            layer.getFontStyle());
        cmd.add("-font");
        cmd.add(fontPath);
        cmd.add("-pointsize");
        cmd.add(String.valueOf(layer.getFontSize() != null ? layer.getFontSize() : 32));

        // ----- weight / style (fallback if font file does not contain it) -----
        if ("bold".equalsIgnoreCase(layer.getFontWeight())) {
            cmd.add("-weight");
            cmd.add("bold");
        }
        if ("italic".equalsIgnoreCase(layer.getFontStyle())) {
            cmd.add("-style");
            cmd.add("italic");
        }

        // ----- outline (stroke) -----
        if (layer.getOutlineWidth() != null && layer.getOutlineWidth() > 0
            && layer.getOutlineColor() != null) {
            cmd.add("-stroke");
            cmd.add(layer.getOutlineColor());
            cmd.add("-strokewidth");
            cmd.add(String.valueOf(layer.getOutlineWidth().intValue()));
        } else {
            cmd.add("-stroke");
            cmd.add("none");
        }

        // ----- fill color -----
        cmd.add("-fill");
        cmd.add(layer.getColor() != null ? layer.getColor() : "#000000");

        // ----- Combined gravity (horizontal + vertical) -----
        String horizontalAlign = (layer.getTextAlign() != null ? layer.getTextAlign() : "center").toLowerCase();
        String verticalAlign = (layer.getVerticalAlign() != null ? layer.getVerticalAlign() : "middle").toLowerCase();

        String gravity = "Center"; // default center-middle

        // Map vertical alignment
        if ("top".equals(verticalAlign)) {
            gravity = switch (horizontalAlign) {
                case "left" -> "NorthWest";
                case "right" -> "NorthEast";
                default -> "North";
            };
        } else if ("bottom".equals(verticalAlign)) {
            gravity = switch (horizontalAlign) {
                case "left" -> "SouthWest";
                case "right" -> "SouthEast";
                default -> "South";
            };
        } else { // middle
            gravity = switch (horizontalAlign) {
                case "left" -> "West";
                case "right" -> "East";
                default -> "Center";
            };
        }

        cmd.add("-gravity");
        cmd.add(gravity);

        // ----- annotate -----
        cmd.add("-annotate");
        cmd.add("+0+0");
        cmd.add(text);

        if (layer.getCurveRadius() != null && layer.getCurveRadius() > 0) {
            cmd.add("-distort");
            cmd.add("Arc");
            cmd.add(String.valueOf(layer.getCurveRadius()));
        }

        cmd.add(outputPath);
        executeImageMagickCommand(cmd, "Process text layer");

        // ----- Apply text decorations AFTER text is rendered but BEFORE rotation -----
        if ("underline".equalsIgnoreCase(layer.getTextDecoration()) ||
            "line-through".equalsIgnoreCase(layer.getTextDecoration())) {

            String decoratedPath = tempDirPath + File.separator + "layer_" + layer.getId() + "_decorated.png";
            applyTextDecorationProperly(outputPath, decoratedPath, layer, width + extraPadding, height + extraBottomPadding + extraPadding);
            Files.deleteIfExists(Paths.get(outputPath));
            outputPath = decoratedPath;
        }

        // ----- rotation AFTER decoration -----
        if (layer.getRotation() != null && layer.getRotation() != 0) {
            String rotatedPath = tempDirPath + File.separator + "layer_" + layer.getId() + "_rotated.png";
            List<String> rotateCmd = new ArrayList<>();
            rotateCmd.add(imageMagickPath);
            rotateCmd.add(outputPath);
            rotateCmd.add("-background");
            rotateCmd.add("transparent");
            rotateCmd.add("-rotate");
            rotateCmd.add(String.valueOf(layer.getRotation()));
            // Don't use +repage here - we need the virtual canvas info for correct positioning

            // Apply opacity if exists
            if (layer.getOpacity() != null && layer.getOpacity() < 1.0) {
                rotateCmd.add("-channel");
                rotateCmd.add("Alpha");
                rotateCmd.add("-evaluate");
                rotateCmd.add("multiply");
                rotateCmd.add(String.valueOf(layer.getOpacity()));
                rotateCmd.add("+channel");
            }

            // Apply shadow if exists
            if (layer.getShadow() != null) {
                applyShadow(rotateCmd, layer.getShadow());
            }

            rotateCmd.add(rotatedPath);
            executeImageMagickCommand(rotateCmd, "Rotate text layer");
            Files.deleteIfExists(Paths.get(outputPath));
            return rotatedPath;
        }

        // ----- opacity (if no rotation) -----
        if (layer.getOpacity() != null && layer.getOpacity() < 1.0) {
            cmd = new ArrayList<>();
            cmd.add(imageMagickPath);
            cmd.add(outputPath);
            cmd.add("-channel");
            cmd.add("Alpha");
            cmd.add("-evaluate");
            cmd.add("multiply");
            cmd.add(String.valueOf(layer.getOpacity()));
            cmd.add("+channel");
            cmd.add(outputPath);
            executeImageMagickCommand(cmd, "Apply opacity to text");
        }

        // ----- shadow (if no rotation) -----
        if (layer.getShadow() != null) {
            cmd = new ArrayList<>();
            cmd.add(imageMagickPath);
            cmd.add(outputPath);
            applyShadow(cmd, layer.getShadow());
            cmd.add(outputPath);
            executeImageMagickCommand(cmd, "Apply shadow to text");
        }

        return outputPath;
    }

    private String processMultiColorText(LayerDTO layer, String tempDirPath, int canvasWidth, int canvasHeight)
        throws IOException, InterruptedException {

        String outputPath = tempDirPath + File.separator + "layer_" + layer.getId() + "_multicolor.png";

        // Calculate dimensions based on font size and text content
        int fontSize = layer.getFontSize() != null ? layer.getFontSize() : 32;
        String text = layer.getText() != null ? layer.getText() : "Text";

        int estimatedWidth = (int)(text.length() * fontSize * 0.6);
        int estimatedHeight = (int)(fontSize * 1.5);

        int width = (layer.getBackgroundWidth() != null && layer.getBackgroundWidth() > 0)
            ? layer.getBackgroundWidth().intValue()
            : estimatedWidth;
        int height = (layer.getBackgroundHeight() != null && layer.getBackgroundHeight() > 0)
            ? layer.getBackgroundHeight().intValue()
            : estimatedHeight;

        // Calculate extra padding for underline
        int extraBottomPadding = 0;
        if ("underline".equalsIgnoreCase(layer.getTextDecoration())) {
            extraBottomPadding = Math.max(8, (layer.getFontSize() != null ? layer.getFontSize() : 32) / 4);
        }

        // Add extra padding for border stroke to prevent cutting
        int borderStrokeWidth = (layer.getBackgroundBorderWidth() != null) ? layer.getBackgroundBorderWidth() : 0;
        int extraPadding = borderStrokeWidth * 2;

        // Create base with background (if any)
        List<String> baseCmd = new ArrayList<>();
        baseCmd.add(imageMagickPath);
        baseCmd.add("-size");
        baseCmd.add((width + extraPadding) + "x" + (height + extraBottomPadding + extraPadding));
        baseCmd.add("xc:transparent");
        baseCmd.add("-set");
        baseCmd.add("colorspace");
        baseCmd.add("sRGB");

        // Add background with border if specified (FIXED)
        if (layer.getBackgroundColor() != null && layer.getBackgroundOpacity() != null
            && layer.getBackgroundOpacity() > 0) {

            String bgColor = layer.getBackgroundColor() +
                String.format("%02x", (int)(layer.getBackgroundOpacity() * 255));

            int bgWidth = width;
            int bgHeight = height + extraBottomPadding;

            if (layer.getBackgroundWidth() != null && layer.getBackgroundWidth() > 0) {
                bgWidth = layer.getBackgroundWidth().intValue();
                width = bgWidth;
            }
            if (layer.getBackgroundHeight() != null && layer.getBackgroundHeight() > 0) {
                bgHeight = layer.getBackgroundHeight().intValue();
                height = bgHeight;
            }

            int borderRadius = layer.getBackgroundBorderRadius() != null ?
                layer.getBackgroundBorderRadius() : 8;

            baseCmd.add("-fill");
            baseCmd.add(bgColor);

            if (layer.getBackgroundBorder() != null && layer.getBackgroundBorderWidth() != null) {
                baseCmd.add("-stroke");
                baseCmd.add(layer.getBackgroundBorder());
                baseCmd.add("-strokewidth");
                baseCmd.add(String.valueOf(layer.getBackgroundBorderWidth()));
            } else {
                baseCmd.add("-stroke");
                baseCmd.add("none");
            }

            // FIXED: Offset the rectangle by the stroke width
            int offset = borderStrokeWidth;
            baseCmd.add("-draw");
            baseCmd.add(String.format("roundrectangle %d,%d %d,%d %d,%d",
                offset, offset,
                bgWidth + offset - 1, bgHeight + offset - 1,
                borderRadius, borderRadius));
        }

        baseCmd.add(outputPath);
        executeImageMagickCommand(baseCmd, "Create base for multi-color text");

        // Get font properties
        String fontPath = resolveFontPath(
            layer.getFontFamily(),
            layer.getFontWeight(),
            layer.getFontStyle());

        // Process segments
        List<LayerDTO.TextSegmentDTO> segments = layer.getTextSegments();
        if (segments == null || segments.isEmpty()) {
            return processTextLayer(layer, tempDirPath, canvasWidth, canvasHeight);
        }

        // Sort segments by start index
        segments.sort((a, b) -> a.getStartIndex().compareTo(b.getStartIndex()));

        String fullText = layer.getText() != null ? layer.getText() : "";
        String horizontalAlign = (layer.getTextAlign() != null ? layer.getTextAlign() : "center").toLowerCase();
        String verticalAlign = (layer.getVerticalAlign() != null ? layer.getVerticalAlign() : "middle").toLowerCase();

        // Calculate total text width to determine starting X position
        int totalTextWidth = estimateTextWidth(fullText, fontSize, fontPath);
        int startX = 0;

        // Account for padding when calculating alignment
        int effectiveWidth = width + extraPadding;
        int effectiveHeight = height + extraBottomPadding + extraPadding;

        if ("center".equals(horizontalAlign)) {
            startX = (effectiveWidth - totalTextWidth) / 2;
        } else if ("right".equals(horizontalAlign)) {
            startX = effectiveWidth - totalTextWidth - 10;
        } else {
            startX = 10 + (extraPadding / 2);
        }

        // Calculate Y position based on vertical alignment
        int baseY = 0;
        if ("top".equals(verticalAlign)) {
            baseY = fontSize + (extraPadding / 2);
        } else if ("bottom".equals(verticalAlign)) {
            baseY = effectiveHeight - 10;
        } else { // middle
            baseY = (effectiveHeight / 2) + (fontSize / 3);
        }

        // Render each segment
        int currentX = startX;
        int lastIndex = 0;

        for (int i = 0; i <= segments.size(); i++) {
            if (i < segments.size()) {
                LayerDTO.TextSegmentDTO segment = segments.get(i);

                // Render text before this segment with default color
                if (segment.getStartIndex() > lastIndex) {
                    String beforeText = fullText.substring(lastIndex, segment.getStartIndex());
                    currentX = renderTextSegment(outputPath, beforeText, currentX, baseY,
                        layer.getColor() != null ? layer.getColor() : "#000000",
                        fontPath, fontSize, layer);
                }

                // Render the colored segment
                currentX = renderTextSegment(outputPath, segment.getText(), currentX, baseY,
                    segment.getColor(), fontPath, fontSize, layer);

                lastIndex = segment.getEndIndex();
            } else {
                // Render remaining text after last segment
                if (lastIndex < fullText.length()) {
                    String remainingText = fullText.substring(lastIndex);
                    renderTextSegment(outputPath, remainingText, currentX, baseY,
                        layer.getColor() != null ? layer.getColor() : "#000000",
                        fontPath, fontSize, layer);
                }
            }
        }

        // Apply text decorations if needed (use the actual width and height with padding)
        if ("underline".equalsIgnoreCase(layer.getTextDecoration()) ||
            "line-through".equalsIgnoreCase(layer.getTextDecoration())) {
            String decoratedPath = tempDirPath + File.separator + "layer_" + layer.getId() + "_decorated.png";
            applyTextDecorationProperly(outputPath, decoratedPath, layer, effectiveWidth, effectiveHeight);
            Files.deleteIfExists(Paths.get(outputPath));
            outputPath = decoratedPath;
        }

        // ----- Apply rotation AFTER decoration -----
        if (layer.getRotation() != null && layer.getRotation() != 0) {
            String rotatedPath = tempDirPath + File.separator + "layer_" + layer.getId() + "_rotated.png";
            List<String> rotateCmd = new ArrayList<>();
            rotateCmd.add(imageMagickPath);
            rotateCmd.add(outputPath);
            rotateCmd.add("-background");
            rotateCmd.add("transparent");
            rotateCmd.add("-rotate");
            rotateCmd.add(String.valueOf(layer.getRotation()));
            // Don't use +repage here - we need the virtual canvas info for correct positioning

            // Apply opacity if exists
            if (layer.getOpacity() != null && layer.getOpacity() < 1.0) {
                rotateCmd.add("-channel");
                rotateCmd.add("Alpha");
                rotateCmd.add("-evaluate");
                rotateCmd.add("multiply");
                rotateCmd.add(String.valueOf(layer.getOpacity()));
                rotateCmd.add("+channel");
            }

            // Apply shadow if exists
            if (layer.getShadow() != null) {
                applyShadow(rotateCmd, layer.getShadow());
            }

            rotateCmd.add(rotatedPath);
            executeImageMagickCommand(rotateCmd, "Rotate multi-color text layer");
            Files.deleteIfExists(Paths.get(outputPath));
            return rotatedPath;
        }

        // ----- Apply opacity (if no rotation) -----
        if (layer.getOpacity() != null && layer.getOpacity() < 1.0) {
            List<String> opacityCmd = new ArrayList<>();
            opacityCmd.add(imageMagickPath);
            opacityCmd.add(outputPath);
            opacityCmd.add("-channel");
            opacityCmd.add("Alpha");
            opacityCmd.add("-evaluate");
            opacityCmd.add("multiply");
            opacityCmd.add(String.valueOf(layer.getOpacity()));
            opacityCmd.add("+channel");
            opacityCmd.add(outputPath);
            executeImageMagickCommand(opacityCmd, "Apply opacity to multi-color text");
        }

        // ----- Apply shadow (if no rotation) -----
        if (layer.getShadow() != null) {
            List<String> shadowCmd = new ArrayList<>();
            shadowCmd.add(imageMagickPath);
            shadowCmd.add(outputPath);
            applyShadow(shadowCmd, layer.getShadow());
            shadowCmd.add(outputPath);
            executeImageMagickCommand(shadowCmd, "Apply shadow to multi-color text");
        }

        return outputPath;
    }

    private int estimateTextWidth(String text, int fontSize, String fontPath) {
        // More accurate estimation based on average character width
        return (int)(text.length() * fontSize * 0.55);
    }

    private int renderTextSegment(String baseImagePath, String text, int x, int y,
                                  String color, String fontPath, int fontSize, LayerDTO layer)
        throws IOException, InterruptedException {

        if (text == null || text.isEmpty()) return x;

        List<String> cmd = new ArrayList<>();
        cmd.add(imageMagickPath);
        cmd.add(baseImagePath);
        cmd.add("-set");
        cmd.add("colorspace");
        cmd.add("sRGB");
        cmd.add("-font");
        cmd.add(fontPath);
        cmd.add("-pointsize");
        cmd.add(String.valueOf(fontSize));

        // Apply text transform
        String transformedText = text;
        if ("uppercase".equalsIgnoreCase(layer.getTextTransform())) {
            transformedText = text.toUpperCase();
        } else if ("lowercase".equalsIgnoreCase(layer.getTextTransform())) {
            transformedText = text.toLowerCase();
        } else if ("capitalize".equalsIgnoreCase(layer.getTextTransform())) {
            transformedText = capitalize(text);
        }

        // Apply outline if specified
        if (layer.getOutlineWidth() != null && layer.getOutlineWidth() > 0
            && layer.getOutlineColor() != null) {
            cmd.add("-stroke");
            cmd.add(layer.getOutlineColor());
            cmd.add("-strokewidth");
            cmd.add(String.valueOf(layer.getOutlineWidth().intValue()));
        } else {
            cmd.add("-stroke");
            cmd.add("none");
        }

        cmd.add("-fill");
        cmd.add(color);
        cmd.add("-annotate");
        cmd.add("+" + x + "+" + y);
        cmd.add(transformedText);
        cmd.add(baseImagePath);

        executeImageMagickCommand(cmd, "Render text segment");

        // Return new X position
        return x + estimateTextWidth(transformedText, fontSize, fontPath);
    }

    /**
     * Apply text decoration properly - measure text bounds and draw line accordingly
     */
    private void applyTextDecorationProperly(String inputPath, String outputPath, LayerDTO layer, int width, int height)
        throws IOException, InterruptedException {

        // Use ImageMagick to detect actual content bounds (non-transparent pixels)
        List<String> identifyCmd = new ArrayList<>();
        identifyCmd.add(imageMagickPath);
        identifyCmd.add("identify");
        identifyCmd.add("-format");
        identifyCmd.add("%@");  // Returns: WIDTHxHEIGHT+X+Y
        identifyCmd.add(inputPath);

        ProcessBuilder pb = new ProcessBuilder(identifyCmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            logger.warn("Identify command timed out");
        }

        String trimBox = output.toString().trim();
        logger.debug("Trim box result: {}", trimBox);

        // Parse trim box: WIDTHxHEIGHT+X+Y
        int textX = 0;
        int textY = 0;
        int textWidth = width;
        int textHeight = height;

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+)x(\\d+)\\+(\\d+)\\+(\\d+)");
        java.util.regex.Matcher matcher = pattern.matcher(trimBox);

        if (matcher.find()) {
            textWidth = Integer.parseInt(matcher.group(1));
            textHeight = Integer.parseInt(matcher.group(2));
            textX = Integer.parseInt(matcher.group(3));
            textY = Integer.parseInt(matcher.group(4));

            logger.debug("Detected text bounds: width={}, height={}, x={}, y={}", textWidth, textHeight, textX, textY);
        } else {
            logger.warn("Could not parse trim box: '{}'. Using full dimensions.", trimBox);
            // Fallback: try to estimate based on alignment
            int fontSize = layer.getFontSize() != null ? layer.getFontSize() : 32;
            String text = layer.getText() != null ? layer.getText() : "";

            // Rough estimation
            textWidth = (int)(text.length() * fontSize * 0.55);
            textHeight = (int)(fontSize * 1.2);

            String horizontalAlign = layer.getTextAlign() != null ? layer.getTextAlign().toLowerCase() : "center";
            String verticalAlign = layer.getVerticalAlign() != null ? layer.getVerticalAlign().toLowerCase() : "middle";

            // Estimate position based on alignment
            if ("center".equals(horizontalAlign)) {
                textX = (width - textWidth) / 2;
            } else if ("right".equals(horizontalAlign)) {
                textX = width - textWidth - 10;
            } else {
                textX = 10;
            }

            if ("middle".equals(verticalAlign)) {
                textY = (height - textHeight) / 2;
            } else if ("top".equals(verticalAlign)) {
                textY = 10;
            } else {
                textY = height - textHeight - 10;
            }
        }

        // Draw the decoration line
        List<String> cmd = new ArrayList<>();
        cmd.add(imageMagickPath);
        cmd.add(inputPath);
        cmd.add("-set");
        cmd.add("colorspace");
        cmd.add("sRGB");

        // Determine color
        String color = layer.getColor() != null ? layer.getColor() : "#000000";
        if ("line-through".equalsIgnoreCase(layer.getTextDecoration()) && layer.getLinethroughColor() != null) {
            color = layer.getLinethroughColor();
        }

        int fontSize = layer.getFontSize() != null ? layer.getFontSize() : 32;
        int strokeWidth = Math.max(2, fontSize / 16);

        // Calculate horizontal extent of the line
        int leftMargin = textX;
        int rightMargin = textX + textWidth;

        if ("underline".equalsIgnoreCase(layer.getTextDecoration())) {
            // Position underline below text with proper gap
            int gap = Math.max(3, fontSize / 10);
            int yPosition = textY + textHeight + gap;

            cmd.add("-stroke");
            cmd.add(color);
            cmd.add("-strokewidth");
            cmd.add(String.valueOf(strokeWidth));
            cmd.add("-draw");
            cmd.add(String.format("line %d,%d %d,%d", leftMargin, yPosition, rightMargin, yPosition));

        } else if ("line-through".equalsIgnoreCase(layer.getTextDecoration())) {
            // FIXED: Position at vertical center of actual text
            // Use 45% from top to account for typical font metrics
            int yPosition = textY + (int)(textHeight * 0.45);

            cmd.add("-stroke");
            cmd.add(color);
            cmd.add("-strokewidth");
            cmd.add(String.valueOf(strokeWidth));
            cmd.add("-draw");
            cmd.add(String.format("line %d,%d %d,%d", leftMargin, yPosition, rightMargin, yPosition));
        }

        cmd.add(outputPath);
        executeImageMagickCommand(cmd, "Apply text decoration");
    }

    private String capitalize(String s) {
        return Arrays.stream(s.split("\\s+"))
            .map(word -> word.isEmpty() ? word :
                Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase())
            .collect(Collectors.joining(" "));
    }

    /**
     * Process SHAPE layer
     */
    private String processShapeLayer(LayerDTO layer, String tempDirPath, Integer canvasWidth, Integer canvasHeight)
        throws IOException, InterruptedException {

        String outputPath = tempDirPath + File.separator + "layer_" + layer.getId() + "_shape.png";

        int width = layer.getWidth() != null ? layer.getWidth().intValue() : 100;
        int height = layer.getHeight() != null ? layer.getHeight().intValue() : 100;
        String fill = layer.getFill() != null ? layer.getFill() : "#000000";
        String stroke = layer.getStroke();
        Integer strokeWidth = layer.getStrokeWidth() != null ? layer.getStrokeWidth() : 0;

        List<String> command = new ArrayList<>();
        command.add(imageMagickPath);
        command.add("-size");
        command.add(width + "x" + height);
        command.add("xc:transparent");
        command.add("-set");
        command.add("colorspace");
        command.add("sRGB");
        command.add("-fill");
        command.add(fill);

        if (stroke != null && strokeWidth > 0) {
            command.add("-stroke");
            command.add(stroke);
            command.add("-strokewidth");
            command.add(String.valueOf(strokeWidth));
        } else {
            command.add("-stroke");
            command.add("none");
        }

        // Draw shape based on type
        String shape = layer.getShape() != null ? layer.getShape().toLowerCase() : "rectangle";
        switch (shape) {
            case "rectangle":
                Integer borderRadius = layer.getBorderRadius();
                if (borderRadius != null && borderRadius > 0) {
                    command.add("-draw");
                    command.add("roundrectangle 0,0 " + (width-1) + "," + (height-1) + " " +
                        borderRadius + "," + borderRadius);
                } else {
                    command.add("-draw");
                    command.add("rectangle 0,0 " + (width-1) + "," + (height-1));
                }
                break;
            case "circle":
                int radius = Math.min(width, height) / 2;
                command.add("-draw");
                command.add("circle " + radius + "," + radius + " " + radius + ",0");
                break;
            case "ellipse":
                int rx = width / 2;
                int ry = height / 2;
                command.add("-draw");
                command.add("ellipse " + rx + "," + ry + " " + rx + "," + ry + " 0,360");
                break;
            case "triangle":
                command.add("-draw");
                command.add("polygon " + (width/2) + ",0 " + width + "," + height + " 0," + height);
                break;
            default:
                command.add("-draw");
                command.add("rectangle 0,0 " + (width-1) + "," + (height-1));
        }

        // Apply rotation
        if (layer.getRotation() != null && layer.getRotation() != 0) {
            command.add("-background");
            command.add("transparent");
            command.add("-rotate");
            command.add(String.valueOf(layer.getRotation()));
            command.add("+repage");
        }

        // Apply opacity
        if (layer.getOpacity() != null && layer.getOpacity() < 1.0) {
            command.add("-channel");
            command.add("Alpha");
            command.add("-evaluate");
            command.add("multiply");
            command.add(String.valueOf(layer.getOpacity()));
            command.add("+channel");
        }

        // Apply shadow if exists
        if (layer.getShadow() != null) {
            applyShadow(command, layer.getShadow());
        }

        command.add(outputPath);

        executeImageMagickCommand(command, "Process shape layer");

        return outputPath;
    }

    /**
     * Process BACKGROUND layer (similar to image but fills entire canvas)
     */
    private String processBackgroundLayer(LayerDTO layer, String tempDirPath, Integer canvasWidth, Integer canvasHeight) 
            throws IOException, InterruptedException {
        
        // Reuse image layer logic but force it to canvas size
        layer.setWidth((double) canvasWidth);
        layer.setHeight((double) canvasHeight);
        layer.setX(0.0);
        layer.setY(0.0);
        
        return processImageLayer(layer, tempDirPath, canvasWidth, canvasHeight);
    }

    /**
     * Composite layer image onto base image at specified position
     */
    private void compositeImages(String baseImagePath, String layerImagePath, String outputPath, LayerDTO layer)
        throws IOException, InterruptedException {

        int x = layer.getX() != null ? layer.getX().intValue() : 0;
        int y = layer.getY() != null ? layer.getY().intValue() : 0;

        // **FIXED: Calculate rotation offset**
        if (layer.getRotation() != null && layer.getRotation() != 0) {
            // Get the dimensions of the rotated image
            BufferedImage rotatedImg = ImageIO.read(new File(layerImagePath));
            int rotatedWidth = rotatedImg.getWidth();
            int rotatedHeight = rotatedImg.getHeight();

            // Original dimensions before rotation
            double origWidth = layer.getWidth();
            double origHeight = layer.getHeight();

            // Calculate the center point of the original image
            double centerX = x + origWidth / 2;
            double centerY = y + origHeight / 2;

            // The rotated image is larger, so we need to offset to keep the same center
            int newX = (int)Math.round(centerX - rotatedWidth / 2);
            int newY = (int)Math.round(centerY - rotatedHeight / 2);

            x = newX;
            y = newY;
        }

        List<String> command = new ArrayList<>();
        command.add(imageMagickPath);
        command.add(baseImagePath);
        command.add("-set");
        command.add("colorspace");
        command.add("sRGB");
        command.add(layerImagePath);
        command.add("-set");
        command.add("colorspace");
        command.add("sRGB");
        command.add("-geometry");
        command.add("+" + x + "+" + y);
        command.add("-compose");
        command.add("Over");
        command.add("-composite");
        command.add(outputPath);

        executeImageMagickCommand(command, "Composite layer");
    }

    /**
     * Apply filters to image
     */
    private void applyFilters(List<String> command, List<FilterDTO> filters) {
        if (filters == null || filters.isEmpty()) return;
        
        for (FilterDTO filter : filters) {
            String type = filter.getType().toLowerCase();
            double value = filter.getValue();
            
            switch (type) {
                case "blur":
                    command.add("-blur");
                    command.add("0x" + value);
                    break;
                case "brightness":
                    command.add("-modulate");
                    command.add((value * 100) + ",100,100");
                    break;
                case "contrast":
                    int contrastValue = (int) ((value - 1) * 100);
                    command.add("-brightness-contrast");
                    command.add("0x" + contrastValue);
                    break;
                case "grayscale":
                    command.add("-colorspace");
                    command.add("Gray");
                    break;
                case "sepia":
                    command.add("-sepia-tone");
                    command.add(((int)(value * 100)) + "%");
                    break;
                case "saturate":
                    command.add("-modulate");
                    command.add("100," + (value * 100) + ",100");
                    break;
                case "hue-rotate":
                    command.add("-modulate");
                    command.add("100,100," + (100 + value));
                    break;
                case "invert":
                    command.add("-negate");
                    break;
            }
        }
    }

    /**
     * Apply shadow effect
     */
    private void applyShadow(List<String> command, ShadowDTO shadow) {
        command.add("(");
        command.add("+clone");
        command.add("-background");
        command.add(shadow.getColor() != null ? shadow.getColor() : "#000000");
        command.add("-shadow");
        command.add("80x" + shadow.getBlur() + "+" + 
                   shadow.getOffsetX().intValue() + "+" + shadow.getOffsetY().intValue());
        command.add(")");
        command.add("+swap");
        command.add("-background");
        command.add("none");
        command.add("-layers");
        command.add("merge");
    }

    private String resolveFontPath(String fontFamily, String fontWeight, String fontStyle) {
        // 1. build a key that contains weight & style
        String key = fontFamily;
        if ("bold".equalsIgnoreCase(fontWeight)) key += " Bold";
        if ("italic".equalsIgnoreCase(fontStyle)) key += " Italic";
        if ("bold".equalsIgnoreCase(fontWeight) && "italic".equalsIgnoreCase(fontStyle)) key += " Bold Italic";

        // 2. reuse the huge map you already have (getFontPathByFamily)
        return getFontPathByFamily(key);      // <-- call your existing method
    }

    private String getFontPathByFamily(String fontFamily) {
        final String FONTS_RESOURCE_PATH = "/fonts/";
        final String TEMP_FONT_DIR = System.getProperty("java.io.tmpdir") + "/scenith-fonts/";

        File tempDir = new File(TEMP_FONT_DIR);
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            logger.error("Failed to create temporary font directory: {}", TEMP_FONT_DIR);
            return "C:/Windows/Fonts/Arial.ttf";
        }

        String defaultFontPath = getFontFilePath("arial.ttf", FONTS_RESOURCE_PATH, TEMP_FONT_DIR);

        if (fontFamily == null || fontFamily.trim().isEmpty()) {
            logger.warn("Font family is null or empty. Using default font: arial.ttf");
            return defaultFontPath;
        }

        Map<String, String> fontMap = new HashMap<>();
        fontMap.put("Arial", "arial.ttf");
        fontMap.put("Times New Roman", "times.ttf");
        fontMap.put("Courier New", "cour.ttf");
        fontMap.put("Calibri", "calibri.ttf");
        fontMap.put("Verdana", "verdana.ttf");
        fontMap.put("Georgia", "georgia.ttf");
        fontMap.put("Comic Sans MS", "comic.ttf");
        fontMap.put("Impact", "impact.ttf");
        fontMap.put("Tahoma", "tahoma.ttf");

        // Arial variants
        fontMap.put("Arial Bold", "arialbd.ttf");
        fontMap.put("Arial Italic", "ariali.ttf");
        fontMap.put("Arial Bold Italic", "arialbi.ttf");
        fontMap.put("Arial Black", "ariblk.ttf");

        // Georgia variants
        fontMap.put("Georgia Bold", "georgiab.ttf");
        fontMap.put("Georgia Italic", "georgiai.ttf");
        fontMap.put("Georgia Bold Italic", "georgiaz.ttf");

        // Times New Roman variants
        fontMap.put("Times New Roman Bold", "timesbd.ttf");
        fontMap.put("Times New Roman Italic", "timesi.ttf");
        fontMap.put("Times New Roman Bold Italic", "timesbi.ttf");

        // Alumni Sans Pinstripe
        fontMap.put("Alumni Sans Pinstripe", "AlumniSansPinstripe-Regular.ttf");

        // Lexend Giga variants
        fontMap.put("Lexend Giga", "LexendGiga-Regular.ttf");
        fontMap.put("Lexend Giga Black", "LexendGiga-Black.ttf");
        fontMap.put("Lexend Giga Bold", "LexendGiga-Bold.ttf");


        // Montserrat Alternates variants
        fontMap.put("Montserrat Alternates", "MontserratAlternates-ExtraLight.ttf");
        fontMap.put("Montserrat Alternates Black", "MontserratAlternates-Black.ttf");
        fontMap.put("Montserrat Alternates Medium Italic", "MontserratAlternates-MediumItalic.ttf");

        // Noto Sans Mono variants
        fontMap.put("Noto Sans Mono", "NotoSansMono-Regular.ttf");
        fontMap.put("Noto Sans Mono Bold", "NotoSansMono-Bold.ttf");


        // Poiret One
        fontMap.put("Poiret One", "PoiretOne-Regular.ttf");

        // Arimo variants
        fontMap.put("Arimo", "Arimo-Regular.ttf");
        fontMap.put("Arimo Bold", "Arimo-Bold.ttf");
        fontMap.put("Arimo Bold Italic", "Arimo-BoldItalic.ttf");
        fontMap.put("Arimo Italic", "Arimo-Italic.ttf");


        // Carlito variants
        fontMap.put("Carlito", "Carlito-Regular.ttf");
        fontMap.put("Carlito Bold", "Carlito-Bold.ttf");
        fontMap.put("Carlito Bold Italic", "Carlito-BoldItalic.ttf");
        fontMap.put("Carlito Italic", "Carlito-Italic.ttf");

        // Comic Neue variants
        fontMap.put("Comic Neue", "ComicNeue-Regular.ttf");
        fontMap.put("Comic Neue Bold", "ComicNeue-Bold.ttf");
        fontMap.put("Comic Neue Bold Italic", "ComicNeue-BoldItalic.ttf");
        fontMap.put("Comic Neue Italic", "ComicNeue-Italic.ttf");


        // Courier Prime variants
        fontMap.put("Courier Prime", "CourierPrime-Regular.ttf");
        fontMap.put("Courier Prime Bold", "CourierPrime-Bold.ttf");
        fontMap.put("Courier Prime Bold Italic", "CourierPrime-BoldItalic.ttf");
        fontMap.put("Courier Prime Italic", "CourierPrime-Italic.ttf");

        // Gelasio variants
        fontMap.put("Gelasio", "Gelasio-Regular.ttf");
        fontMap.put("Gelasio Bold", "Gelasio-Bold.ttf");
        fontMap.put("Gelasio Bold Italic", "Gelasio-BoldItalic.ttf");
        fontMap.put("Gelasio Italic", "Gelasio-Italic.ttf");


        // Tinos variants
        fontMap.put("Tinos", "Tinos-Regular.ttf");
        fontMap.put("Tinos Bold", "Tinos-Bold.ttf");
        fontMap.put("Tinos Bold Italic", "Tinos-BoldItalic.ttf");
        fontMap.put("Tinos Italic", "Tinos-Italic.ttf");

        // Amatic SC variants
        fontMap.put("Amatic SC", "AmaticSC-Regular.ttf");
        fontMap.put("Amatic SC Bold", "AmaticSC-Bold.ttf");

// Barriecito
        fontMap.put("Barriecito", "Barriecito-Regular.ttf");

// Barrio
        fontMap.put("Barrio", "Barrio-Regular.ttf");

// Birthstone
        fontMap.put("Birthstone", "Birthstone-Regular.ttf");

// Bungee Hairline
        fontMap.put("Bungee Hairline", "BungeeHairline-Regular.ttf");

// Butcherman
        fontMap.put("Butcherman", "Butcherman-Regular.ttf");

// Doto variants
        fontMap.put("Doto Black", "Doto-Black.ttf");
        fontMap.put("Doto ExtraBold", "Doto-ExtraBold.ttf");
        fontMap.put("Doto Rounded Bold", "Doto_Rounded-Bold.ttf");

// Fascinate Inline
        fontMap.put("Fascinate Inline", "FascinateInline-Regular.ttf");

// Freckle Face
        fontMap.put("Freckle Face", "FreckleFace-Regular.ttf");

// Fredericka the Great
        fontMap.put("Fredericka the Great", "FrederickatheGreat-Regular.ttf");

// Imperial Script
        fontMap.put("Imperial Script", "ImperialScript-Regular.ttf");

// Kings
        fontMap.put("Kings", "Kings-Regular.ttf");

// Kirang Haerang
        fontMap.put("Kirang Haerang", "KirangHaerang-Regular.ttf");

// Lavishly Yours
        fontMap.put("Lavishly Yours", "LavishlyYours-Regular.ttf");

// Mountains of Christmas variants
        fontMap.put("Mountains of Christmas", "MountainsofChristmas-Regular.ttf");
        fontMap.put("Mountains of Christmas Bold", "MountainsofChristmas-Bold.ttf");

// Rampart One
        fontMap.put("Rampart One", "RampartOne-Regular.ttf");

// Rubik Wet Paint
        fontMap.put("Rubik Wet Paint", "RubikWetPaint-Regular.ttf");

// Tangerine variants
        fontMap.put("Tangerine", "Tangerine-Regular.ttf");
        fontMap.put("Tangerine Bold", "Tangerine-Bold.ttf");

// Yesteryear
        fontMap.put("Yesteryear", "Yesteryear-Regular.ttf");

        String processedFontFamily = fontFamily.trim();
        if (fontMap.containsKey(processedFontFamily)) {
            String fontFileName = fontMap.get(processedFontFamily);
            String fontPath = getFontFilePath(fontFileName, FONTS_RESOURCE_PATH, TEMP_FONT_DIR);
            logger.debug("Found font match for: {} -> {}", processedFontFamily, fontPath);
            return fontPath;
        }

        for (Map.Entry<String, String> entry : fontMap.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(processedFontFamily)) {
                String fontFileName = entry.getValue();
                String fontPath = getFontFilePath(fontFileName, FONTS_RESOURCE_PATH, TEMP_FONT_DIR);
                logger.debug("Found case-insensitive font match for: {} -> {}", processedFontFamily, fontPath);
                return fontPath;
            }
        }

        logger.warn("Font family '{}' not found. Using default: arial.ttf", fontFamily);
        return defaultFontPath;
    }

    private String getFontFilePath(String fontFileName, String fontsResourcePath, String tempFontDir) {
        try {
            File tempFontFile = new File(tempFontDir + fontFileName);
            if (tempFontFile.exists()) {
                return tempFontFile.getAbsolutePath();
            }

            String resourcePath = fontsResourcePath + fontFileName;
            InputStream fontStream = getClass().getResourceAsStream(resourcePath);
            if (fontStream == null) {
                logger.error("Font file not found in resources: {}", resourcePath);
                return "C:/Windows/Fonts/Arial.ttf";
            }

            Path tempPath = tempFontFile.toPath();
            Files.copy(fontStream, tempPath, StandardCopyOption.REPLACE_EXISTING);
            fontStream.close();

            logger.debug("Extracted font to: {}", tempFontFile.getAbsolutePath());
            return tempFontFile.getAbsolutePath();
        } catch (IOException e) {
            logger.error("Error accessing font file: {}. Error: {}", fontFileName, e.getMessage());
            return "C:/Windows/Fonts/Arial.ttf";
        }
    }

    /**
     * Export final image in specified format
     */
    private void exportFinalImage(String inputPath, String outputPath, String format, Integer quality)
        throws IOException, InterruptedException {

        List<String> command = new ArrayList<>();
        command.add(imageMagickPath);
        command.add(inputPath);
        command.add("-set");
        command.add("colorspace");
        command.add("sRGB");

        if ("JPG".equalsIgnoreCase(format) || "JPEG".equalsIgnoreCase(format)) {
            command.add("-background");
            command.add("white");
            command.add("-flatten");
            command.add("-quality");
            command.add(String.valueOf(quality != null ? quality : 90));
        } else if ("PDF".equalsIgnoreCase(format)) {
            command.add("-density");
            command.add("300");
        }

        command.add(outputPath);

        executeImageMagickCommand(command, "Export final image");
    }

    /**
     * Download image from URL or copy from local path
     */
    private String downloadOrCopyImage(String src, String tempDirPath, String layerId) throws IOException {
        String outputPath = tempDirPath + File.separator + "source_" + layerId + ".png";
        
        if (src.startsWith("http://") || src.startsWith("https://")) {
            // Download from URL
            logger.debug("Downloading image from: {}", src);
            try (InputStream in = new URL(src).openStream()) {
                Files.copy(in, Paths.get(outputPath), StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            // Copy from local path
            String fullPath = baseDir + File.separator + src;
            Files.copy(Paths.get(fullPath), Paths.get(outputPath), StandardCopyOption.REPLACE_EXISTING);
        }
        
        return outputPath;
    }

    /**
     * Execute ImageMagick command
     */
    private void executeImageMagickCommand(List<String> command, String operationName)
        throws IOException, InterruptedException {

        logger.debug("Executing {}: {}", operationName, String.join(" ", command));

        // FORCE sRGB colorspace - prevent auto-grayscale
        List<String> finalCommand = new ArrayList<>();
        finalCommand.add(command.get(0)); // imageMagickPath
        finalCommand.add("-define");
        finalCommand.add("colorspace:auto-grayscale=off");
        finalCommand.addAll(command.subList(1, command.size()));

        ProcessBuilder processBuilder = new ProcessBuilder(finalCommand);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                logger.debug("ImageMagick: {}", line);
            }
        }

        boolean completed = process.waitFor(2, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            throw new RuntimeException("ImageMagick process timed out for " + operationName + ": " + output.toString());
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("ImageMagick failed for " + operationName + " with exit code " +
                exitCode + ": " + output.toString());
        }
    }

    /**
     * Cleanup temp directory
     */
    private void cleanupTempDirectory(File tempDir) {
        try {
            if (tempDir.exists() && tempDir.isDirectory()) {
                File[] files = tempDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        Files.deleteIfExists(file.toPath());
                    }
                }
                Files.deleteIfExists(tempDir.toPath());
                logger.debug("Cleaned up temp directory: {}", tempDir.getAbsolutePath());
            }
        } catch (IOException e) {
            logger.warn("Failed to cleanup temp directory: {}", e.getMessage());
        }
    }
}