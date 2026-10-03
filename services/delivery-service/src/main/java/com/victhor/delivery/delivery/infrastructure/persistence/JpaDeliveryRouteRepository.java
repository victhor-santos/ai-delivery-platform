package com.victhor.delivery.delivery.infrastructure.persistence;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.victhor.delivery.delivery.application.DeliveryRoutePlan;
import com.victhor.delivery.delivery.application.DeliveryRouteRepository;
import com.victhor.delivery.delivery.application.DeliveryRoutingSnapshot;
import com.victhor.delivery.delivery.application.OptimizedRoute;
import com.victhor.delivery.delivery.application.StaleRoutePlanException;
import tools.jackson.databind.ObjectMapper;

@Repository
@Transactional(readOnly = true)
public class JpaDeliveryRouteRepository implements DeliveryRouteRepository {

    private final SpringDataDeliveryRepository deliveries;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JpaDeliveryRouteRepository(SpringDataDeliveryRepository deliveries, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.deliveries = deliveries;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<DeliveryRoutingSnapshot> findSnapshot(UUID deliveryId) {
        return deliveries.findById(deliveryId).map(entity -> new DeliveryRoutingSnapshot(entity.toDomain(), entity.version()));
    }

    @Override
    public Optional<DeliveryRoutePlan> findPlan(UUID deliveryId) {
        return jdbc.query("SELECT * FROM delivery_route_plans WHERE delivery_id = ?", (row, index) ->
                new DeliveryRoutePlan(row.getObject("id", UUID.class), row.getObject("delivery_id", UUID.class),
                        row.getTimestamp("departure_at").toInstant(), row.getTimestamp("planned_at").toInstant(),
                        row.getLong("delivery_version"), mapper.readValue(row.getString("optimized_route"), OptimizedRoute.class)),
                deliveryId).stream().findFirst();
    }

    @Override
    @Transactional
    public DeliveryRoutePlan save(DeliveryRoutingSnapshot snapshot, DeliveryRoutePlan plan) {
        snapshot.requirePlannable();
        if (!snapshot.delivery().id().equals(plan.deliveryId()) || plan.deliveryVersion() != snapshot.version() + 1) {
            throw new IllegalArgumentException("Route plan does not match its delivery snapshot");
        }
        plan.optimizedRoute().validateEndpoints(snapshot.delivery().origin().point(), snapshot.delivery().destination().point());
        String route = mapper.writeValueAsString(plan.optimizedRoute());
        int changed = jdbc.update("""
                UPDATE deliveries SET version = version + 1
                WHERE id = ? AND version = ? AND status IN ('CREATED', 'ASSIGNED', 'PICKED_UP')
                """, plan.deliveryId(), snapshot.version());
        if (changed != 1) {
            throw new StaleRoutePlanException();
        }
        jdbc.update("""
                INSERT INTO delivery_route_plans (delivery_id, id, departure_at, planned_at, delivery_version, optimized_route)
                VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB))
                ON CONFLICT (delivery_id) DO UPDATE SET id = EXCLUDED.id, departure_at = EXCLUDED.departure_at,
                    planned_at = EXCLUDED.planned_at, delivery_version = EXCLUDED.delivery_version,
                    optimized_route = EXCLUDED.optimized_route
                """, plan.deliveryId(), plan.id(), Timestamp.from(plan.departureAt()), Timestamp.from(plan.plannedAt()),
                plan.deliveryVersion(), route);
        return plan;
    }
}
