package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ReservationRequestService {
    private final ReservationRequestRepository requestRepo;
    private final ReservationRepository reservationRepo;
    private final AllocationRepository allocationRepo;

    public ReservationRequestService(ReservationRequestRepository requestRepo,
                                     ReservationRepository reservationRepo,
                                     AllocationRepository allocationRepo) {
        this.requestRepo = requestRepo;
        this.reservationRepo = reservationRepo;
        this.allocationRepo = allocationRepo;
    }

    public List<ReservationRequest> findAll() { return requestRepo.findAll(); }
    public Optional<ReservationRequest> findById(Long id) { return requestRepo.findById(id); }
    public ReservationRequest save(ReservationRequest request) { return requestRepo.save(request); }

    @Transactional
    public void deleteDraftRequest(Long requestId) {
        ReservationRequest request = requestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        if (request.getStatus() != ReservationRequest.Status.DRAFT) {
            throw new IllegalStateException("Only DRAFT requests can be deleted");
        }
        List<Reservation> reservations = reservationRepo.findByRequestId(requestId);
        if (!reservations.isEmpty()) {
            List<Long> reservationIds = reservations.stream().map(Reservation::getId).toList();
            allocationRepo.deleteByReservationIdIn(reservationIds);
            reservationRepo.deleteAll(reservations);
        }
        requestRepo.deleteById(requestId);
    }
}
