CREATE INDEX ix_bookings_user_history ON bookings (user_id, created_at DESC, id);
