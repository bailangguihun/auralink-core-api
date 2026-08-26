package com.auralink.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.auralink.entity.GenerationLog;
import com.auralink.entity.User;

@Repository
public interface GenerationLogRepository extends JpaRepository<GenerationLog, Long> {

    List<GenerationLog> findByUserOrderByCreatedAtDesc(User user);

    Page<GenerationLog> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    List<GenerationLog> findByTaskTypeAndUserOrderByCreatedAtDesc(String taskType, User user);

    Page<GenerationLog> findByTaskTypeAndUserOrderByCreatedAtDesc(String taskType, User user, Pageable pageable);
}