package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.Season;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SeasonRepository extends JpaRepository<Season, Long> {
    List<Season> findBySeasonCalendarIdOrderByDateFromAscIdAsc(Long seasonCalendarId);

    List<Season> findBySeasonCalendarIdInOrderByDateFromAscIdAsc(Collection<Long> seasonCalendarIds);

    Optional<Season> findByIdAndTenantId(Long id, Long tenantId);

    List<Season> findByIdIn(Collection<Long> ids);

    long countBySeasonCalendarId(Long seasonCalendarId);

    @Query("""
            select s from Season s
            where s.seasonCalendarId = :calendarId
              and s.dateFrom <= :date and s.dateTo >= :date
            """)
    Optional<Season> findOnDate(@Param("calendarId") Long calendarId, @Param("date") LocalDate date);

    @Query("""
            select s from Season s
            where s.seasonCalendarId in :calendarIds
              and s.dateFrom <= :date and s.dateTo >= :date
            """)
    List<Season> findOnDateIn(@Param("calendarIds") Collection<Long> calendarIds, @Param("date") LocalDate date);

    @Query("""
            select s from Season s
            where s.seasonCalendarId = :calendarId
              and s.dateFrom <= :to and s.dateTo >= :from
            order by s.dateFrom, s.id
            """)
    List<Season> findOverlapping(@Param("calendarId") Long calendarId,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to);
}
