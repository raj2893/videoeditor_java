package com.example.videoeditor.repository;

import com.example.videoeditor.entity.SoleTTS;
import com.example.videoeditor.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SoleTTSRepository extends JpaRepository<SoleTTS, Long> {
    List<SoleTTS> findByUser(User user);
}