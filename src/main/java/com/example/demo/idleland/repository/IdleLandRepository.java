package com.example.demo.idleland.repository;

import com.example.demo.idleland.entity.IdleLand;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface IdleLandRepository extends JpaRepository<IdleLand, Long>, JpaSpecificationExecutor<IdleLand> {
    @Modifying
    @Transactional
    @Query(value = "TRUNCATE TABLE idle_land", nativeQuery = true)
    void truncateTable();

    @Query("select c.solarReadinessScore from IdleLand c "
            + "where c.assetTypeNorm = :assetTypeNorm and c.solarReadinessScore is not null")
    List<Double> findSolarReadinessScoresByAssetTypeNorm(String assetTypeNorm);


    @Query("select c.estimatedPanelCount from IdleLand c "
            + "where c.assetTypeNorm = :assetTypeNorm and c.estimatedPanelCount is not null")
    List<Integer> findEstimatedPanelCountsByAssetTypeNorm(String assetTypeNorm);
}