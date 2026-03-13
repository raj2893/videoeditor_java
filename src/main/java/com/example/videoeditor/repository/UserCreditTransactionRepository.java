// UserCreditTransactionRepository.java
package com.example.videoeditor.repository;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.UserCreditTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface UserCreditTransactionRepository extends JpaRepository<UserCreditTransaction, Long> {
    List<UserCreditTransaction> findByUserOrderByCreatedAtDesc(User user);
}