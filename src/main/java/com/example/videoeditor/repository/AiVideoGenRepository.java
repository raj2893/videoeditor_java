package com.example.videoeditor.repository;

import com.example.videoeditor.entity.AiVideoGen;
import com.example.videoeditor.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiVideoGenRepository extends JpaRepository<AiVideoGen, Long> {

    Optional<AiVideoGen> findByFalRequestId(String falRequestId);

    List<AiVideoGen> findByUserOrderByCreatedAtDesc(User user);

    List<AiVideoGen> findByUserAndStatusOrderByCreatedAtDesc(User user, AiVideoGen.Status status);
}