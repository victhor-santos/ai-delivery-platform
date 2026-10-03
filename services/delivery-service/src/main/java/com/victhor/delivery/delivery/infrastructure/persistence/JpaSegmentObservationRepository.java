package com.victhor.delivery.delivery.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.victhor.delivery.delivery.application.DeliveryNotFoundException;
import com.victhor.delivery.delivery.application.DeliveryRouteRepository;
import com.victhor.delivery.delivery.application.RoutePlanNotFoundException;
import com.victhor.delivery.delivery.application.SegmentObservation;
import com.victhor.delivery.delivery.application.SegmentObservationPolicy;
import com.victhor.delivery.delivery.application.SegmentObservationRepository;
import com.victhor.delivery.delivery.application.TraversalEvent;
import com.victhor.delivery.delivery.application.TraversalPrediction;
import com.victhor.delivery.delivery.domain.Delivery;
import com.victhor.delivery.delivery.domain.SegmentTraversal;
import tools.jackson.databind.ObjectMapper;

@Repository
@Transactional(readOnly = true)
public class JpaSegmentObservationRepository implements SegmentObservationRepository {

    private final JdbcTemplate jdbc;
    private final SpringDataDeliveryRepository deliveries;
    private final DeliveryRouteRepository routes;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JpaSegmentObservationRepository(JdbcTemplate jdbc, SpringDataDeliveryRepository deliveries,
            DeliveryRouteRepository routes, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.deliveries = deliveries;
        this.routes = routes;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SegmentObservation enter(UUID deliveryId, int sequence, TraversalEvent event) {
        Delivery delivery = lockDelivery(deliveryId);
        var existing = findByDelivery(deliveryId);
        var plan = routes.findPlan(deliveryId).orElseThrow(RoutePlanNotFoundException::new);
        var observation = SegmentObservationPolicy.enter(delivery, plan, existing, sequence, event, clock.instant());
        if (existing.contains(observation)) {
            return observation;
        }
        var traversal = observation.traversal();
        jdbc.update("""
                INSERT INTO delivery_segment_observations
                    (id, delivery_id, route_plan_id, sequence, data_origin, entered_at, entry_recorded_at, prediction_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB))
                """, traversal.id(), deliveryId, plan.id(), sequence, traversal.dataOrigin(), Timestamp.from(traversal.enteredAt()),
                Timestamp.from(traversal.entryRecordedAt()), mapper.writeValueAsString(observation.prediction()));
        incrementVersion(deliveryId);
        return observation;
    }

    @Override
    @Transactional
    public SegmentObservation exit(UUID deliveryId, int sequence, TraversalEvent event) {
        Delivery delivery = lockDelivery(deliveryId);
        var existing = findByDelivery(deliveryId);
        var observation = SegmentObservationPolicy.exit(delivery, existing, sequence, event, clock.instant());
        if (!existing.contains(observation)) {
            var completed = observation.traversal();
            jdbc.update("UPDATE delivery_segment_observations SET exited_at = ?, label_available_at = ? WHERE id = ?",
                    Timestamp.from(completed.exitedAt()), Timestamp.from(completed.labelAvailableAt()), completed.id());
            incrementVersion(deliveryId);
        }
        return observation;
    }

    @Override
    public List<SegmentObservation> findByDelivery(UUID deliveryId) {
        if (!deliveries.existsById(deliveryId)) {
            throw new DeliveryNotFoundException();
        }
        return jdbc.query("SELECT * FROM delivery_segment_observations WHERE delivery_id = ? ORDER BY sequence", (row, index) ->
                new SegmentObservation(new SegmentTraversal(row.getObject("id", UUID.class), row.getObject("delivery_id", UUID.class),
                        row.getObject("route_plan_id", UUID.class), row.getInt("sequence"), row.getString("data_origin"),
                        row.getTimestamp("entered_at").toInstant(), row.getTimestamp("entry_recorded_at").toInstant(),
                        row.getTimestamp("exited_at") == null ? null : row.getTimestamp("exited_at").toInstant(),
                        row.getTimestamp("label_available_at") == null ? null : row.getTimestamp("label_available_at").toInstant()),
                        mapper.readValue(row.getString("prediction_snapshot"), TraversalPrediction.class)), deliveryId);
    }

    private Delivery lockDelivery(UUID id) {
        var locked = jdbc.query("SELECT id FROM deliveries WHERE id = ? FOR UPDATE", (row, index) -> row.getObject(1, UUID.class), id);
        if (locked.isEmpty()) {
            throw new DeliveryNotFoundException();
        }
        return deliveries.findById(id).orElseThrow(DeliveryNotFoundException::new).toDomain();
    }

    private void incrementVersion(UUID id) {
        jdbc.update("UPDATE deliveries SET version = version + 1 WHERE id = ?", id);
    }
}
