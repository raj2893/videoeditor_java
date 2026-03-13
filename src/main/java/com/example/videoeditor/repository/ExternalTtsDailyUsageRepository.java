package com.example.videoeditor.repository;

import com.example.videoeditor.entity.ExternalTtsDailyUsage;
import com.example.videoeditor.entity.SoleTTS.TtsProvider;
import com.example.videoeditor.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

public interface ExternalTtsDailyUsageRepository extends JpaRepository<ExternalTtsDailyUsage, Long> {
    Optional<ExternalTtsDailyUsage> findByUserAndProviderAndUsageDate(User user, TtsProvider provider, LocalDate date);
}