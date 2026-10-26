package com.example.hotel.booking.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static com.example.hotel.booking.domain.BookingReleaseReason.COMPENSATION;
import static com.example.hotel.booking.domain.BookingStatus.CANCELLED;
import static com.example.hotel.booking.domain.BookingStatus.CONFIRMED;
import static com.example.hotel.booking.domain.BookingStatus.PENDING;
import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.FetchType.LAZY;
import static jakarta.persistence.GenerationType.IDENTITY;
import static lombok.AccessLevel.PROTECTED;

@Getter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(name = "bookings", uniqueConstraints = @UniqueConstraint(
    name = "uk_bookings_user_request", columnNames = {"user_id", "request_id"}))
public class Booking {
    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false,
        foreignKey = @ForeignKey(name = "fk_bookings_user"))
    private User user;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "auto_select", nullable = false, updatable = false)
    private boolean autoSelect;

    @Column(name = "hotel_id", updatable = false)
    private Long hotelId;

    @Column(name = "room_id")
    private Long roomId;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false, updatable = false)
    private LocalDate endDate;

    @Enumerated(STRING)
    @Column(nullable = false, length = 16)
    private BookingStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deadline_at", nullable = false, updatable = false)
    private Instant deadlineAt;

    @Column(name = "release_pending", nullable = false)
    private boolean releasePending;

    @Enumerated(STRING)
    @Column(name = "release_reason", length = 32)
    private BookingReleaseReason releaseReason;

    @Column(name = "next_recovery_at")
    private Instant nextRecoveryAt;

    @Version
    @Column(nullable = false)
    private long version;

    public Booking(User user, UUID requestId, boolean autoSelect, Long hotelId, Long roomId,
                   LocalDate startDate, LocalDate endDate, Instant createdAt, Instant deadlineAt) {
        this.user = user;
        this.requestId = requestId;
        this.autoSelect = autoSelect;
        this.hotelId = hotelId;
        this.roomId = roomId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = PENDING;
        this.createdAt = createdAt;
        this.deadlineAt = deadlineAt;
        this.nextRecoveryAt = deadlineAt;
    }

    public void confirm(Instant now) {
        if (status == PENDING && deadlineAt.isAfter(now)) {
            status = CONFIRMED;
            nextRecoveryAt = null;
        }
    }

    public void cancelPending() {
        if (status == PENDING) {
            status = CANCELLED;
            releasePending = roomId != null;
            releaseReason = COMPENSATION;
            if (!releasePending) {
                nextRecoveryAt = null;
            }
        }
    }

    public void completeRelease() {
        if (status == CANCELLED) {
            releasePending = false;
            nextRecoveryAt = null;
        }
    }

    public boolean claimRecovery(Instant now, Instant retryAt) {
        final var recoveryIsDue = nextRecoveryAt != null && !nextRecoveryAt.isAfter(now);
        if (!recoveryIsDue) {
            return false;
        }

        final var pendingIsExpired = status == PENDING && !deadlineAt.isAfter(now);
        if (pendingIsExpired) {
            cancelPending();
        }

        final var releaseIsRequired = status == CANCELLED && releasePending;
        if (!releaseIsRequired) {
            return false;
        }
        nextRecoveryAt = retryAt;
        return true;
    }
}
