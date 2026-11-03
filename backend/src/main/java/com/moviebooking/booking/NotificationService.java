package com.moviebooking.booking;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moviebooking.security.AuthenticatedUser;

@Service
@Transactional(readOnly = true)
public class NotificationService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final NotificationInboxRepository inbox;

    public NotificationService(NotificationInboxRepository inbox) {
        this.inbox = inbox;
    }

    public NotificationPage listForCustomer(AuthenticatedUser customer, BookingPageRequest request) {
        Page<NotificationInbox> page = inbox.findByCustomerId(customer.id(),
                PageRequest.of(request.page(), request.size(), NEWEST_FIRST));
        List<NotificationView> items = page.getContent().stream().map(NotificationView::from).toList();
        return new NotificationPage(items, request.page(), request.size(), page.getTotalElements(),
                page.getTotalPages());
    }

    public record NotificationView(UUID id, UUID eventId, UUID bookingId, String eventType, String reference,
            Instant createdAt) {

        static NotificationView from(NotificationInbox row) {
            Map<String, Object> payload = row.getPayload();
            Object reference = payload == null ? null : payload.get("reference");
            return new NotificationView(row.getId(), row.getEventId(), row.getBookingId(), row.getEventType(),
                    reference == null ? null : reference.toString(), row.getCreatedAt());
        }
    }

    public record NotificationPage(List<NotificationView> items, int page, int size, long totalItems, int totalPages) {
    }
}
