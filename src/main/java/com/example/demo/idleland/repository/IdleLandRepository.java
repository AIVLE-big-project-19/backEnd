package com.example.demo.idleland.repository;

import com.example.demo.idleland.entity.IdleLand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface IdleLandRepository extends JpaRepository<IdleLand, Long>, JpaSpecificationExecutor<IdleLand> {
}
