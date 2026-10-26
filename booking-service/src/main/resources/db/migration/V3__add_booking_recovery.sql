ALTER TABLE bookings
    ADD COLUMN next_recovery_at TIMESTAMP WITH TIME ZONE;

UPDATE bookings
SET next_recovery_at = deadline_at
WHERE status = 'PENDING'
   OR (status = 'CANCELLED' AND release_pending = TRUE);

CREATE INDEX ix_bookings_recovery ON bookings (next_recovery_at, id);
