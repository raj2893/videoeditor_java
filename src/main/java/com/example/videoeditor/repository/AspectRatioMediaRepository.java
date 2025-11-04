package com.example.videoeditor.repository;

import com.example.videoeditor.entity.AspectRatioMedia;
import com.example.videoeditor.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AspectRatioMediaRepository extends JpaRepository<AspectRatioMedia, Long> {
    List<AspectRatioMedia> findByUser(User user);
}