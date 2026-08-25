package com.growingpots.domain.university.repository;

import com.growingpots.domain.university.entity.School;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolRepository extends JpaRepository<School, Long> {
    Optional<School> findByName(String name);
}