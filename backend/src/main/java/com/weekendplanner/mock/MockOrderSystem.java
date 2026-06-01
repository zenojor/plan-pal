package com.weekendplanner.mock;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class MockOrderSystem {

    private final Map<String, OrderRecord> orders = new ConcurrentHashMap<>();
    private final AtomicInteger ticketSeq = new AtomicInteger(1000);
    private final AtomicInteger orderGroupSeq = new AtomicInteger(700);

    public ReservationResult reserve(String poiId, int headcount, String time) {
        String reservationId = "RES-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        OrderRecord record = new OrderRecord(reservationId, poiId, "RESERVATION", "PENDING");
        orders.put(reservationId, record);
        return new ReservationResult(reservationId, true, "预订成功");
    }

    public TicketResult buyTicket(String poiId, int num, String sessionTime) {
        int ticketNum = ticketSeq.incrementAndGet();
        String ticketId = "T" + ticketNum;
        OrderRecord record = new OrderRecord(ticketId, poiId, "TICKET", "CONFIRMED");
        orders.put(ticketId, record);
        return new TicketResult(ticketId, true, num * 80.0, "出票成功");
    }

    public RideResult hailRide(String fromPoiName, String toPoiName, double distanceKm, String targetTime) {
        String rideId = "RIDE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        double totalPrice = Math.max(14.0, 14.0 + Math.max(0, distanceKm - 3.0) * 3.2);
        OrderRecord record = new OrderRecord(rideId, fromPoiName + "->" + toPoiName + "@" + targetTime, "RIDE_HAIL", "PENDING");
        orders.put(rideId, record);
        return new RideResult(rideId, true, Math.round(totalPrice * 10.0) / 10.0, "叫车成功");
    }

    public String createOrderGroup() {
        return "G" + orderGroupSeq.incrementAndGet();
    }

    public boolean tryLock(String orderId) {
        OrderRecord record = orders.get(orderId);
        if (record == null) return false;
        record.status = "LOCKED";
        return true;
    }

    public void commit(String orderId) {
        OrderRecord record = orders.get(orderId);
        if (record != null) record.status = "CONFIRMED";
    }

    public void rollback(String orderId) {
        OrderRecord record = orders.get(orderId);
        if (record != null) record.status = "CANCELLED";
    }

    public String sendNotification(String contactToken, String message) {
        return "MSG-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase() +
                "|" + contactToken + "|SENT";
    }

    private static class OrderRecord {
        String id;
        String poiId;
        String type;
        String status;

        OrderRecord(String id, String poiId, String type, String status) {
            this.id = id;
            this.poiId = poiId;
            this.type = type;
            this.status = status;
        }
    }

    public record ReservationResult(String reservationId, boolean success, String message) {}
    public record TicketResult(String ticketId, boolean success, double totalPrice, String message) {}
    public record RideResult(String rideId, boolean success, double totalPrice, String message) {}
}
