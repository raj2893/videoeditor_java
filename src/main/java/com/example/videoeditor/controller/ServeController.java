package com.example.videoeditor.controller;

import com.example.videoeditor.entity.Project;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.ProjectRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
public class ServeController {

    private final ProjectController projectController;
    private final ProjectRepository projectRepository;

    public ServeController(ProjectController projectController, ProjectRepository projectRepository) {
        this.projectController = projectController;
        this.projectRepository = projectRepository;
    }

    @GetMapping("videos/projects/{projectId}/{filename:.+}")
    public ResponseEntity<Resource> serveVideo(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable Long projectId,
            @PathVariable String filename) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                user = projectController.getUserFromToken(token);
            }

            // Verify project exists
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

            // If token is provided, verify user has access
            if (user != null && !project.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
            }

            // Define video file path
            String videoDirectory = "videos/projects/" + projectId + "/";
            Path videoPath = Paths.get(videoDirectory).resolve(filename).normalize();
            Resource resource = new UrlResource(videoPath.toUri());

            // Verify file existence
            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            // Set content type
            String contentType = "video/mp4";

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .body(resource);
        } catch (RuntimeException e) {
            System.err.println("Error serving video: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (Exception e) {
            System.err.println("Error serving video: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @GetMapping("elements/{filename:.+}")
    public ResponseEntity<Resource> serveElement(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable String filename) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                user = projectController.getUserFromToken(token);
            }

            // Define elements file path
            String elementsDirectory = "D:/Backend/videoEditor-main/elements/";
            Path elementsPath = Paths.get(elementsDirectory).resolve(filename).normalize();
            Resource resource = new UrlResource(elementsPath.toUri());

            // Verify file existence
            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            String contentType = projectController.determineContentType(filename);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(resource);
        } catch (RuntimeException e) {
            System.err.println("Error serving element: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (Exception e) {
            System.err.println("Error serving element: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @GetMapping("image/projects/{projectId}/{filename:.+}")
    public ResponseEntity<Resource> serveImage(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable Long projectId,
            @PathVariable String filename) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                user = projectController.getUserFromToken(token);
            }

            // Verify project exists
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

            // If token is provided, verify user has access
            if (user != null && !project.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
            }

            // Define project-specific image file path
            String projectImageDirectory = "images/projects/" + projectId + "/";
            Path projectImagePath = Paths.get(projectImageDirectory).resolve(filename).normalize();
            Resource resource = new UrlResource(projectImagePath.toUri());

            // Verify file existence
            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            String contentType = projectController.determineContentType(filename);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(resource);
        } catch (RuntimeException e) {
            System.err.println("Error serving image: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (Exception e) {
            System.err.println("Error serving image: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @GetMapping("audio/projects/{projectId}/{filename:.+}")
    public ResponseEntity<Resource> serveAudio(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable Long projectId,
            @PathVariable String filename) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                user = projectController.getUserFromToken(token);
            }

            // Verify project exists
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

            // If token is provided, verify user has access
            if (user != null && !project.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
            }

            // Define audio file path
            String baseAudioDirectory = "audio/projects/" + projectId + "/";
            File audioFile = new File(baseAudioDirectory, filename);

            // Verify file existence
            if (!audioFile.exists() || !audioFile.isFile()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            // Create resource and determine content type
            Resource resource = new FileSystemResource(audioFile);
            String contentType = projectController.determineAudioContentType(filename);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(resource);
        } catch (RuntimeException e) {
            System.err.println("Error serving audio: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (Exception e) {
            System.err.println("Error serving audio: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @GetMapping("audio/projects/{projectId}/extracted/{filename:.+}")
    public ResponseEntity<Resource> serveExtractedAudio(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable Long projectId,
            @PathVariable String filename) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                user = projectController.getUserFromToken(token);
            }

            // Verify project exists
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

            // If token is provided, verify user has access
            if (user != null && !project.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
            }

            // Define extracted audio file path
            String extractedAudioDirectory = "audio/projects/" + projectId + "/extracted/";
            File audioFile = new File(extractedAudioDirectory, filename);

            // Verify file existence
            if (!audioFile.exists() || !audioFile.isFile()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            // Create resource and determine content type
            Resource resource = new FileSystemResource(audioFile);
            String contentType = projectController.determineAudioContentType(filename);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(resource);
        } catch (RuntimeException e) {
            System.err.println("Error serving extracted audio: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (Exception e) {
            System.err.println("Error serving extracted audio: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @GetMapping("audio/projects/{projectId}/waveforms/{filename:.+}")
    public ResponseEntity<Resource> serveWaveform(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable Long projectId,
            @PathVariable String filename) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                user = projectController.getUserFromToken(token);
            }

            // Verify project exists
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

            // If token is provided, verify user has access
            if (user != null && !project.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
            }

            // Define waveform file path
            String waveformDirectory = "audio/projects/" + projectId + "/waveforms/";
            File waveformFile = new File(waveformDirectory, filename);

            // Verify file existence
            if (!waveformFile.exists() || !waveformFile.isFile()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            // Create resource and set content type
            Resource resource = new FileSystemResource(waveformFile);
            String contentType = "image/png"; // Waveforms are PNG files

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(resource);
        } catch (RuntimeException e) {
            System.err.println("Error serving waveform: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (Exception e) {
            System.err.println("Error serving waveform: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @GetMapping("audio/sole_tts/{userId}/{filename:.+}")
    public ResponseEntity<Resource> serveTTSAudio(
        @RequestHeader(value = "Authorization", required = false) String token,
        @PathVariable Long userId,
        @PathVariable String filename) {
        try {

            // Define TTS audio file path
            String ttsAudioDirectory = "audio/sole_tts/" + userId + "/";
            File audioFile = new File(ttsAudioDirectory, filename);

            // Verify file existence
            if (!audioFile.exists() || !audioFile.isFile()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            Resource resource = new FileSystemResource(audioFile);
            String contentType = projectController.determineAudioContentType(filename);

            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(resource);
        } catch (RuntimeException e) {
            System.err.println("Error serving TTS audio: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (Exception e) {
            System.err.println("Error serving TTS audio: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @GetMapping("videos/filtered/{userId}/original/{filename:.+}")
    public ResponseEntity<Resource> serveFilteredOriginal(
        @RequestHeader(value = "Authorization", required = false) String token,
        @PathVariable Long userId,
        @PathVariable String filename) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                user = projectController.getUserFromToken(token);
            }

            // If token is provided, verify user has access
            if (user != null && !user.getId().equals(userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
            }

            // Define filtered original video file path
            String filteredDirectory = "videos/filtered/" + userId + "/original/";
            File videoFile = new File(filteredDirectory, filename);

            // Verify file existence
            if (!videoFile.exists() || !videoFile.isFile()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            // Create resource and set content type
            Resource resource = new FileSystemResource(videoFile);
            String contentType = "video/mp4"; // Assuming MP4 for filtered videos

            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(resource);
        } catch (RuntimeException e) {
            System.err.println("Error serving filtered original video: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (Exception e) {
            System.err.println("Error serving filtered original video: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @GetMapping("/speed-videos/{userId}/{fileName:.+}")
    public ResponseEntity<Resource> serveSpeedVideo(
        @RequestHeader(value = "Authorization", required = false) String token,
        @PathVariable Long userId,
        @PathVariable String fileName) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                user = projectController.getUserFromToken(token);
            }

            // If token is provided, verify user has access
            if (user != null && !user.getId().equals(userId)) {
                System.err.println("Unauthorized access to speed video: userId=" + user.getId() + ", requested userId=" + userId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
            }

            // Define speed video file path
            String speedVideoDirectory = "D:/Backend/videoEditor-main/speed-videos/" + userId + "/";
            File videoFile = new File(speedVideoDirectory, fileName);

            // Verify file existence
            if (!videoFile.exists() || !videoFile.isFile()) {
                System.err.println("Speed video file not found: " + speedVideoDirectory + fileName);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            // Create resource and set content type
            Resource resource = new FileSystemResource(videoFile);
            String contentType = "video/mp4";

            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
                .body(resource);
        } catch (RuntimeException e) {
            System.err.println("Error serving speed video: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (Exception e) {
            System.err.println("Error serving speed video: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @GetMapping("subtitles/{userId}/original/{name:.+}")
    public ResponseEntity<Resource> serveSubtitleFile(
        @RequestHeader(value = "Authorization", required = false) String token,
        @PathVariable Long userId,
        @PathVariable String name) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                user = projectController.getUserFromToken(token);
            }

            // If token is provided, verify user has access
            if (user != null && !user.getId().equals(userId)) {
                System.err.println("Unauthorized access to subtitle file: userId=" + user.getId() + ", requested userId=" + userId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
            }

            // Define subtitle file path
            String subtitleDirectory = "subtitles/" + userId + "/original/";
            File subtitleFile = new File(subtitleDirectory, name);

            // Verify file existence
            if (!subtitleFile.exists() || !subtitleFile.isFile()) {
                System.err.println("Subtitle file not found: " + subtitleDirectory + name);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            // Create resource
            Resource resource = new FileSystemResource(subtitleFile);

            // Determine content type (most likely text/vtt or text/plain)
            String contentType = "text/vtt";
            if (name.endsWith(".srt")) {
                contentType = "application/x-subrip";
            } else if (name.endsWith(".txt")) {
                contentType = "text/plain";
            }

            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
                .body(resource);

        } catch (RuntimeException e) {
            System.err.println("Error serving subtitle file: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (Exception e) {
            System.err.println("Error serving subtitle file: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @GetMapping("aspect_ratio/{userId}/original/{filename:.+}")
    public ResponseEntity<Resource> serveAspectRatioFile(
        @RequestHeader(value = "Authorization", required = false) String token,
        @PathVariable Long userId,
        @PathVariable String filename) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                user = projectController.getUserFromToken(token);
            }

            // If token is provided, verify user has access
            if (user != null && !user.getId().equals(userId)) {
                System.err.println("Unauthorized access to aspect ratio file: userId=" + user.getId() + ", requested userId=" + userId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
            }

            // Define aspect ratio file path
            String aspectRatioDirectory = "aspect_ratio/" + userId + "/original/";
            File aspectRatioFile = new File(aspectRatioDirectory, filename);

            // Verify file existence
            if (!aspectRatioFile.exists() || !aspectRatioFile.isFile()) {
                System.err.println("Aspect ratio file not found: " + aspectRatioDirectory + filename);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            // Create resource
            Resource resource = new FileSystemResource(aspectRatioFile);

            // Determine content type dynamically
            String contentType = projectController.determineContentType(filename);

            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(resource);

        } catch (RuntimeException e) {
            System.err.println("Error serving aspect ratio file: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (Exception e) {
            System.err.println("Error serving aspect ratio file: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @GetMapping("image_editor/{userId}/assets/{filename:.+}")
    public ResponseEntity<Resource> serveImageEditorAsset(
        @RequestHeader(value = "Authorization", required = false) String token,
        @PathVariable Long userId,
        @PathVariable String filename) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                user = projectController.getUserFromToken(token);
            }

            // If token is provided, verify user has access
            if (user != null && !user.getId().equals(userId)) {
                System.err.println("Unauthorized access to image editor asset: userId=" + user.getId() + ", requested userId=" + userId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
            }

            // Define asset file path
            String assetsDirectory = "image_editor/" + userId + "/assets/";
            File assetFile = new File(assetsDirectory, filename);

            // Verify file existence
            if (!assetFile.exists() || !assetFile.isFile()) {
                System.err.println("Image editor asset not found: " + assetsDirectory + filename);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            // Create resource and determine content type
            Resource resource = new FileSystemResource(assetFile);
            String contentType = projectController.determineContentType(filename);

            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(resource);

        } catch (RuntimeException e) {
            System.err.println("Error serving image editor asset: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (Exception e) {
            System.err.println("Error serving image editor asset: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @GetMapping("image_editor/{userId}/exports/{filename:.+}")
    public ResponseEntity<Resource> serveImageEditorExport(
        @RequestHeader(value = "Authorization", required = false) String token,
        @PathVariable Long userId,
        @PathVariable String filename) {

        try {
            User currentUser = null;
            if (token != null && !token.isEmpty()) {
                currentUser = projectController.getUserFromToken(token);
            }

            if (currentUser != null && !currentUser.getId().equals(userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
            }

            String exportDirectory = "image_editor/" + userId + "/exports/";
            File exportFile = new File(exportDirectory, filename);

            if (!exportFile.exists() || !exportFile.isFile()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            Resource resource = new FileSystemResource(exportFile);

            String contentType = projectController.determineContentType(filename);
            if (contentType == null || contentType.isEmpty()) {
                contentType = "application/octet-stream"; // safe fallback
            }

            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                // ---- DOWNLOAD ----
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + "\"")
                // ---- CORS (required for fetch → blob → download) ----
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                    HttpHeaders.CONTENT_DISPOSITION + ", " + HttpHeaders.CONTENT_TYPE)
                .body(resource);

        } catch (RuntimeException e) {
            System.err.println("Error serving image-editor export: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (Exception e) {
            System.err.println("Unexpected error serving export: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @GetMapping("image_editor/elements/{filename:.+}")
    public ResponseEntity<Resource> serveImageEditorElement(
        @PathVariable String filename) {

        try {
            String elementsDirectory = "image_editor/elements/";
            File elementFile = new File(elementsDirectory, filename);

            if (!elementFile.exists() || !elementFile.isFile()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            Resource resource = new FileSystemResource(elementFile);

            String contentType = projectController.determineContentType(filename);
            if (contentType == null || contentType.isEmpty()) {
                contentType = "application/octet-stream"; // safe fallback
            }

            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                // ---- DOWNLOAD ----
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + "\"")
                // ---- CORS (required for fetch → blob → download) ----
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                    HttpHeaders.CONTENT_DISPOSITION + ", " + HttpHeaders.CONTENT_TYPE)
                .body(resource);

        } catch (RuntimeException e) {
            System.err.println("Error serving image-editor element: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (Exception e) {
            System.err.println("Unexpected error serving element: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }
}