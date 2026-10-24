package com.authorityclosers.salesxray;

import androidx.annotation.NonNull;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

/** Receives Sales Xray's lead alerts and review alerts from Firebase Cloud Messaging. */
public class LeadAlertService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Map<String, String> data = message.getData();
        String type = data.get("type");
        if ("gold_review".equals(type)) {
            ReviewAlerts.show(getApplicationContext(), data.get("kind"), data.get("assignment"), count(data));
            return;
        }
        if (!"lead_desk".equals(type)) return;
        // urgency "urgent": a nudge back to work or an urgent alert (Lead OS), on the Urgent work channel.
        LeadAlerts.show(getApplicationContext(), data.get("kind"), data.get("lead"), count(data), data.get("urgency"));
    }

    private static int count(Map<String, String> data) {
        try {
            return Integer.parseInt(data.getOrDefault("count", "0"));
        } catch (NumberFormatException ignored) {
            return 0; // A bad count shows no number.
        }
    }

    @Override
    public void onNewToken(@NonNull String token) {
        // The page sends the new address to Sales Xray on its next visit.
        LeadAlerts.saveToken(getApplicationContext(), token);
    }
}
