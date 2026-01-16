package com.example.videoeditor.service.pdf;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.pdf.DocumentUpload;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.repository.pdf.DocumentUploadRepository;
import com.example.videoeditor.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DocumentUploadService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentUploadService.class);

    private final JwtUtil jwtUtil;

    private final UserRepository userRepository;

    private final DocumentUploadRepository documentUploadRepository;

    @Value("${app.base-dir:/tmp}")
    private String baseDir;

    @Value("${app.use-local-storage:false}")
    private boolean useLocalStorage;

    private String getLocalStoragePath(String r2Path) {
        return Paths.get(baseDir, "r2-local-mock", r2Path).toString();
    }

    public DocumentUploadService(JwtUtil jwtUtil, UserRepository userRepository, DocumentUploadRepository documentUploadRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.documentUploadRepository = documentUploadRepository;
    }

    public List<DocumentUpload> uploadDocuments(User user, List<MultipartFile> files) throws IOException {
        List<DocumentUpload> uploads = new ArrayList<>();
        String timestamp = String.valueOf(System.currentTimeMillis());

        String uploadBaseDir = Paths.get(baseDir, "documents", String.valueOf(user.getId()), "uploads", timestamp).toString();
        Files.createDirectories(Paths.get(uploadBaseDir));

        for (MultipartFile file : files) {
            String fileName = file.getOriginalFilename();
            if (fileName == null) continue;

            String fileType = determineFileType(fileName);

            String localPath = uploadBaseDir + File.separator + fileName;

            // Save file locally
            file.transferTo(Paths.get(localPath));

            String relativePath = "documents/" + user.getId() + "/uploads/" + timestamp + "/" + fileName;

            String cdnUrl = "http://localhost:8080/" + relativePath;  // or just use relativePath

            DocumentUpload upload = DocumentUpload.builder()
                    .fileName(fileName)
                    .filePath(relativePath)           // ← we store relative path
                    .cdnUrl(cdnUrl)
                    .presignedUrl(cdnUrl)             // same for local
                    .fileType(fileType)
                    .fileSizeBytes(file.getSize())
                    .user(user)
                    .build();

            uploads.add(documentUploadRepository.save(upload));
            logger.info("Uploaded document locally: {} for user: {}", fileName, user.getId());
        }

        return uploads;
    }

    /**
     * Get user documents
     */
    public List<DocumentUpload> getUserDocuments(User user, String fileType) {
        if (fileType != null && !fileType.isEmpty()) {
            return documentUploadRepository.findByUserAndFileType(user, fileType);
        }
        return documentUploadRepository.findByUserOrderByCreatedAtDesc(user);
    }

    /**
     * Delete document
     */
    public void deleteDocument(User user, Long id) throws IOException {
        DocumentUpload upload = documentUploadRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Document not found"));

        if (!upload.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized access");
        }

        // Local development: delete physical file
        String fullPath = Paths.get(baseDir, upload.getFilePath()).toString();
        Path fileToDelete = Paths.get(fullPath);

        if (Files.exists(fileToDelete)) {
            Files.delete(fileToDelete);
            logger.info("Deleted local file: {}", fullPath);
        } else {
            logger.warn("Local file not found, skipping deletion: {}", fullPath);
        }

        // Delete from database
        documentUploadRepository.delete(upload);

        logger.info("Deleted document record {} for user {}", id, user.getId());
    }

    /**
     * Get user from token
     */
    public User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    /**
     * Determine file type from filename
     */
    private String determineFileType(String fileName) {
        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(".pdf")) {
            return "PDF";
        } else if (lowerName.matches(".*\\.(jpg|jpeg|png|gif|bmp|tiff)$")) {
            return "IMAGE";
        } else if (lowerName.endsWith(".docx") || lowerName.endsWith(".doc")) {
            return "DOCX";
        }
        return "OTHER";
    }
}