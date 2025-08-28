package com.example.videoeditor.repository;

import com.example.videoeditor.entity.StandaloneImage;
import com.example.videoeditor.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StandaloneImageRepository extends JpaRepository<StandaloneImage, Long> {
    List<StandaloneImage> findByUser(User user);
}