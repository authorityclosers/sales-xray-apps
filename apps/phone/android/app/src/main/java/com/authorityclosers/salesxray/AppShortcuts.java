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
 * Calls and Chats, each with its own glyph, each opening that screen in the app (MainActivity's
 * sx_open path). Set from code, so the Dev build's own package works too.
 */
final class AppShortcuts {

    private AppShortcuts() {}

    static void publish(Context context) {
        List<ShortcutInfoCompat> shortcuts = new ArrayList<>();
        shortcuts.add(shortcut(context, "add_lead", "Add lead", "Add a lead", "/leads#add=lead", R.drawable.ic_shortcut_add_lead));
        shortcuts.add(shortcut(context, "calls", "Calls", "Calls", "/analysis/calls", R.drawable.ic_shortcut_calls));
        shortcuts.add(shortcut(context, "chats", "Chats", "Chats", "/chats", R.drawable.ic_shortcut_chats));
        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts);
        } catch (RuntimeException ignored) {
            // A launcher without shortcuts, or too many: the app works the same.
        }
    }

    private static ShortcutInfoCompat shortcut(Context context, String id, String label, String longLabel, String path, int icon) {
        Intent open = new Intent(context, MainActivity.class)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(LeadAlerts.EXTRA_OPEN, path);
        return new ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label)
            .setLongLabel(longLabel)
            // Each its own glyph (Redmi audit, 23 Oct 2026), not the app icon.
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(open)
            .build();
    }
}
