package com.example.videoeditor.repository.imagerepository;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.imageentity.SoleImageGen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SoleImageGenRepository extends JpaRepository<SoleImageGen, Long> {
    List<SoleImageGen> findByUserOrderByCreatedAtDesc(User user);
}