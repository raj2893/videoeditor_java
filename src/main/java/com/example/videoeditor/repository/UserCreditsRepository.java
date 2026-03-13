// UserCreditsRepository.java
package com.example.videoeditor.repository;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.UserCredits;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface UserCreditsRepository extends JpaRepository<UserCredits, Long> {
    Optional<UserCredits> findByUser(User user);
}