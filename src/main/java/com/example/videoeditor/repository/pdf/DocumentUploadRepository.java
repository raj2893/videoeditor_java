package com.example.videoeditor.repository.pdf;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.pdf.DocumentUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DocumentUploadRepository extends JpaRepository<DocumentUpload, Long> {
    List<DocumentUpload> findByUserOrderByCreatedAtDesc(User user);
    List<DocumentUpload> findByUserAndFileType(User user, String fileType);
    List<DocumentUpload> findByCreatedAtBefore(LocalDateTime dateTime);
    long countByUserAndCreatedAtAfter(User user, LocalDateTime dateTime);
}