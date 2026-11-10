package com.example.medibook.repositories;

import com.example.medibook.models.BookingLock;
import com.example.medibook.utils.ReservationIdentity;
import com.google.firebase.Timestamp;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public final class BookingLockMapper {
    private BookingLockMapper() { }

    public static BookingLock fromMap(String lockId, long generation, String slotId, Map<String, Object> data) {
        if (generation < 1 || generation > 9_999_999_999L || !("g" + generation).equals(lockId)
                || data == null || !data.keySet().equals(new HashSet<>(Arrays.asList(
                "appointmentId", "patientUid", "scheduleId", "startAt", "endAt", "createdAt")))) throw invalid();
        String id = InventorySlotMapper.text(data, "appointmentId"), uid = InventorySlotMapper.text(data, "patientUid");
        String schedule = InventorySlotMapper.text(data, "scheduleId");
        Timestamp start = InventorySlotMapper.time(data, "startAt"), end = InventorySlotMapper.time(data, "endAt");
        Timestamp created = InventorySlotMapper.time(data, "createdAt");
        if (!DoctorMapper.isValidId(id) || !DoctorMapper.isValidId(uid) || !DoctorMapper.isValidId(schedule)
                || start.getNanoseconds() != 0 || end.getNanoseconds() != 0
                || !ReservationIdentity.slotId(start.toDate().getTime(), end.toDate().getTime()).equals(slotId)) throw invalid();
        return new BookingLock(id, uid, schedule, start, end, created);
    }

    public static Map<String, Object> writeMap(BookingLock lock, Object serverTime) {
        if (lock == null || lock.startAt == null || lock.endAt == null || serverTime == null) throw invalid();
        Map<String, Object> map = new HashMap<>();
        map.put("appointmentId", lock.appointmentId); map.put("patientUid", lock.patientUid);
        map.put("scheduleId", lock.scheduleId); map.put("startAt", lock.startAt); map.put("endAt", lock.endAt);
        map.put("createdAt", lock.createdAt);
        fromMap("g1", 1, ReservationIdentity.slotId(lock.startAt.toDate().getTime(), lock.endAt.toDate().getTime()), map);
        map.put("createdAt", serverTime); return map;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid booking lock"); }
}
