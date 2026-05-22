package com.weekendplanner.mock;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock 订单系统 - 模拟餐厅预订、门票购买、通知发送
 *
 * 支持类2PC事务: tryLock → commit / rollback
 */
@Component
public class MockOrderSystem {

    private final Map<String, OrderRecord> orders = new ConcurrentHashMap<>();
    private final AtomicInteger ticketSeq = new AtomicInteger(1000);
    private final AtomicInteger orderGroupSeq = new AtomicInteger(700);

    /**
     * 预约餐厅/活动 - 返回预约单
     */
    public ReservationResult reserve(String poiId, int headcount, String time) {
        String reservationId = "RES-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        OrderRecord record = new OrderRecord(reservationId, poiId, "RESERVATION", "PENDING");
        orders.put(reservationId, record);
        return new ReservationResult(reservationId, true, "预订成功");
    }

    /**
     * 购买门票
     */
    public TicketResult buyTicket(String poiId, int num, String sessionTime) {
        int ticketNum = ticketSeq.incrementAndGet();
        String ticketId = "T" + ticketNum;
        OrderRecord record = new OrderRecord(ticketId, poiId, "TICKET", "CONFIRMED");
        orders.put(ticketId, record);
        return new TicketResult(ticketId, true, num * 80.0, "出票成功");
    }

    /**
     * 类2PC: 创建订单组
     */
    public String createOrderGroup() {
        return "G" + orderGroupSeq.incrementAndGet();
    }

    /**
     * 类2PC: 尝试锁定资源
     */
    public boolean tryLock(String orderId) {
        OrderRecord record = orders.get(orderId);
        if (record == null) return false;
        record.status = "LOCKED";
        return true;
    }

    /**
     * 类2PC: 提交
     */
    public void commit(String orderId) {
        OrderRecord record = orders.get(orderId);
        if (record != null) record.status = "CONFIRMED";
    }

    /**
     * 类2PC: 回滚
     */
    public void rollback(String orderId) {
        OrderRecord record = orders.get(orderId);
        if (record != null) record.status = "CANCELLED";
    }

    /**
     * 发送通知
     */
    public String sendNotification(String contactToken, String message) {
        return "MSG-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase() +
                "|" + contactToken + "|SENT";
    }

    // ---- 内部记录 ----

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

    // ---- 返回类型 ----

    public record ReservationResult(String reservationId, boolean success, String message) {}
    public record TicketResult(String ticketId, boolean success, double totalPrice, String message) {}
}
