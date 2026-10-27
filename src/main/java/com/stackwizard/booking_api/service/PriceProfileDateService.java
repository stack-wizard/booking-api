package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.PriceProfileDate;
import com.stackwizard.booking_api.model.Season;
import com.stackwizard.booking_api.repository.PriceProfileDateRepository;
import com.stackwizard.booking_api.repository.SeasonRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class PriceProfileDateService {
    private final PriceProfileDateRepository repo;
    private final SeasonRepository seasonRepo;

    public PriceProfileDateService(PriceProfileDateRepository repo, SeasonRepository seasonRepo) {
        this.repo = repo;
        this.seasonRepo = seasonRepo;
    }

    public List<PriceProfileDate> findAll() { return repo.findAll(); }
    public Optional<PriceProfileDate> findById(Long id) { return repo.findById(id); }

    public PriceProfileDate save(PriceProfileDate profileDate) {
        validate(profileDate);
        return repo.save(profileDate);
    }

    public void deleteById(Long id) { repo.deleteById(id); }

    /**
     * Period kind defaults to SEASON. SUB_PERIOD needs a season; SUB_PERIOD and DATES must lie inside their season.
     */
    void validate(PriceProfileDate period) {
        if (period.getDateFrom() == null || period.getDateTo() == null) {
            throw new IllegalArgumentException("dateFrom and dateTo are required");
        }
        if (period.getDateTo().isBefore(period.getDateFrom())) {
            throw new IllegalArgumentException("dateTo must not be before dateFrom");
        }
        if (period.getPeriodKind() == null) {
            period.setPeriodKind(PriceProfileDate.PeriodKind.SEASON);
        }
        if (period.getSeasonId() == null) {
            if (period.getPeriodKind() == PriceProfileDate.PeriodKind.SUB_PERIOD) {
                throw new IllegalArgumentException("seasonId is required for a SUB_PERIOD price period");
            }
            return;
        }
        Season season = seasonRepo.findById(period.getSeasonId())
                .orElseThrow(() -> new IllegalArgumentException("seasonId: season " + period.getSeasonId() + " not found"));
        if (period.getPeriodKind() != PriceProfileDate.PeriodKind.SEASON
                && (period.getDateFrom().isBefore(season.getDateFrom()) || period.getDateTo().isAfter(season.getDateTo()))) {
            throw new IllegalArgumentException(period.getPeriodKind() + " period " + period.getDateFrom() + " - "
                    + period.getDateTo() + " must lie inside season '" + season.getName() + "' ("
                    + season.getDateFrom() + " - " + season.getDateTo() + ")");
        }
    }
}
