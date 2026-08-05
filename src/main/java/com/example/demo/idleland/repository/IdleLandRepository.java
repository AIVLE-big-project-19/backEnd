package com.example.demo.idleland.repository;

import com.example.demo.idleland.entity.IdleLand;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface IdleLandRepository extends JpaRepository<IdleLand, Long>, JpaSpecificationExecutor<IdleLand> {
    @Modifying
    @Transactional
    @Query(value = "TRUNCATE TABLE idle_land", nativeQuery = true)
    void truncateTable();
}