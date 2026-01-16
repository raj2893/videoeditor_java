package com.example.videoeditor.repository.pdf;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.pdf.DocumentConversion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DocumentConversionRepository extends JpaRepository<DocumentConversion, Long> {
    List<DocumentConversion> findByUser(User user);
    List<DocumentConversion> findByUserOrderByCreatedAtDesc(User user);
    List<DocumentConversion> findByUserAndOperationType(User user, String operationType);
    List<DocumentConversion> findByUserAndStatus(User user, String status);
    List<DocumentConversion> findByCreatedAtBefore(LocalDateTime dateTime);
    long countByUserAndCreatedAtAfter(User user, LocalDateTime dateTime);
}