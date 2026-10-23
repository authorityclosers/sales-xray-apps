package com.authorityclosers.salesxray;

import android.content.Context;
import android.content.Intent;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import java.util.ArrayList;
import java.util.List;

/**
 * Long-press the app's icon (owner, 23 Oct 2026: app-like feel): Add lead,
 * Calls and Chats, each opening that screen in the app (MainActivity's
 * sx_open path). Set from code, so the Dev build's own package works too.
 */
final class AppShortcuts {

    private AppShortcuts() {}

    static void publish(Context context) {
        List<ShortcutInfoCompat> shortcuts = new ArrayList<>();
        shortcuts.add(shortcut(context, "add_lead", "Add lead", "Add a lead", "/leads#add=lead"));
        shortcuts.add(shortcut(context, "calls", "Calls", "Your analysed calls", "/analysis/calls"));
        shortcuts.add(shortcut(context, "chats", "Chats", "Your chats with leads", "/chats"));
        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts);
        } catch (RuntimeException ignored) {
            // A launcher without shortcuts, or too many: the app works the same.
        }
    }

    private static ShortcutInfoCompat shortcut(Context context, String id, String label, String longLabel, String path) {
        Intent open = new Intent(context, MainActivity.class)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(LeadAlerts.EXTRA_OPEN, path);
        return new ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label)
            .setLongLabel(longLabel)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(open)
            .build();
    }
}
