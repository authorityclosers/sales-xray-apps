package com.authorityclosers.salesxray;

import androidx.annotation.NonNull;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

/** Receives Sales Xray's lead alerts from Firebase Cloud Messaging. */
public class LeadAlertService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Map<String, String> data = message.getData();
        if (!"lead_desk".equals(data.get("type"))) return;
        int waiting = 0;
        try {
            waiting = Integer.parseInt(data.getOrDefault("count", "0"));
        } catch (NumberFormatException ignored) {
            // A bad count shows no number.
        }
        LeadAlerts.show(getApplicationContext(), data.get("kind"), data.get("lead"), waiting);
    }

    @Override
    public void onNewToken(@NonNull String token) {
        // The page sends the new address to Sales Xray on its next visit.
        LeadAlerts.saveToken(getApplicationContext(), token);
    }
}
