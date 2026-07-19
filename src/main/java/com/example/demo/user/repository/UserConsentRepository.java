package com.example.demo.user.repository;

import com.example.demo.user.entity.ConsentType;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {

    Optional<UserConsent> findTopByUserAndConsentTypeOrderByIdDesc(User user, ConsentType consentType);

}
