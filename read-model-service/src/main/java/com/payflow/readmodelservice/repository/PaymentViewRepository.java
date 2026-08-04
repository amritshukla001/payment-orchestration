package com.payflow.readmodelservice.repository;

import com.payflow.readmodelservice.domain.PaymentView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PaymentViewRepository extends JpaRepository<PaymentView, UUID> {
    List<PaymentView> findAllByOrderByUpdatedAtDesc();

    /**
     * Each row: [PaymentState state, Long count, Long amountCents]. Backs
     * the merchant analytics view's settled/failed/reversed rates and
     * settled-volume total -- amount is included here (not a separate
     * query) so "how much actually settled" doesn't need its own round trip.
     */
    @Query("SELECT p.state, COUNT(p), SUM(p.amountCents) FROM PaymentView p GROUP BY p.state")
    List<Object[]> countByState();

    /** Each row: [String paymentMethod, Long count, Long amountCents]. */
    @Query("SELECT p.paymentMethod, COUNT(p), SUM(p.amountCents) FROM PaymentView p GROUP BY p.paymentMethod")
    List<Object[]> aggregateByMethod();

    /**
     * Each row: [Timestamp day, Long count, Long amountCents], one per
     * calendar day since `since` that had at least one payment. Native
     * SQL (date_trunc) rather than JPQL -- this project is Postgres-only
     * in practice, and JPQL has no portable date-truncation function.
     * Buckets by payment_view.updated_at, the only timestamp this
     * projection carries (overwritten on every transition) -- acceptable
     * here since this system's sagas settle in well under a second, so
     * "last updated" and "initiated" land on the same day in practice.
     */
    @Query(value = "SELECT date_trunc('day', updated_at) AS day, COUNT(*), COALESCE(SUM(amount_cents), 0) "
            + "FROM payment_view WHERE updated_at >= :since GROUP BY day ORDER BY day",
            nativeQuery = true)
    List<Object[]> dailyVolumeSince(@Param("since") Instant since);
}
