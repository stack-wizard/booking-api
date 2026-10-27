package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.SeasonOperating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SeasonOperatingRepository extends JpaRepository<SeasonOperating, Long> {
    List<SeasonOperating> findBySeasonIdOrderByLocationNodeIdAscIdAsc(Long seasonId);

    List<SeasonOperating> findBySeasonIdIn(Collection<Long> seasonIds);

    @Modifying
    @Query("delete from SeasonOperating o where o.seasonId = :seasonId")
    void deleteBySeasonId(@Param("seasonId") Long seasonId);
}
