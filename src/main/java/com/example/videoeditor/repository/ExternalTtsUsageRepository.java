// ExternalTtsUsageRepository.java
package com.example.videoeditor.repository;

import com.example.videoeditor.entity.ExternalTtsUsage;
import com.example.videoeditor.entity.SoleTTS.TtsProvider;
import com.example.videoeditor.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.YearMonth;
import java.util.Optional;

public interface ExternalTtsUsageRepository extends JpaRepository<ExternalTtsUsage, Long> {
    Optional<ExternalTtsUsage> findByUserAndProviderAndMonth(User user, TtsProvider provider, YearMonth month);
}