package org.cardanofoundation.cip113.util;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.signify.app.Notifying;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.cesr.util.Utils;

import java.util.List;
import java.util.Objects;

@Slf4j
public class IpexNotificationHelper {

    private static final int MAX_RETRIES = 20;
    private static final long POLL_INTERVAL_MS = 2000;

    public static Notification waitForNotification(SignifyClient client, String route) throws Exception {
        for (int i = 0; i < MAX_RETRIES; i++) {
            Notifying.Notifications.NotificationListResponse response = client.notifications().list();
            List<Notification> notes = Utils.fromJson(response.notes(), new TypeReference<>() {});

            List<Notification> matching = notes.stream()
                    .filter(n -> Objects.equals(route, n.a.r) && !Boolean.TRUE.equals(n.r))
                    .toList();

            if (!matching.isEmpty()) {
                log.debug("Received notification for route={}", route);
                return matching.getFirst();
            }

            log.info("Waiting for notification: {} (attempt {}/{})", route, i + 1, MAX_RETRIES);
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new RuntimeException("Timed out waiting for notification: " + route);
    }

    public static void markAndDelete(SignifyClient client, Notification note) throws Exception {
        client.notifications().mark(note.i);
        client.notifications().delete(note.i);
    }

    public static class Notification {
        public String i;
        public Boolean r;
        public NotificationBody a;

        public static class NotificationBody {
            public String r;
            public String d;
        }
    }
}
