package com.authorityclosers.salesxray;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.provider.CallLog;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.webkit.CookieManager;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Logs every work call (UX study item 5), including ones made from the phone
 * dialer. When the app opens and every 15 minutes it reads the phone's call
 * history since the last look, keeps the calls on the work SIM whose number is
 * one of the seller's leads, and sends just those to Sales Xray on the seller's
 * own session. The leads' numbers come fresh from Sales Xray each time and are
 * not kept on the phone. A number that matches no lead never leaves the phone.
 * Calls placed from a lead in the app are skipped: the after-call sheet logs
 * those. A logged call that connected then waits for its recording
 * (RecordingQueue).
 */
final class CallLogSync {

    static final String WORK = "sx-call-log";
    private static final String PREFS = "sx_call_log";
    private static final String ON = "on";
    private static final String SIM = "sim";
    private static final String SIM_ALT = "sim_alt";
    private static final String SINCE = "since";
    private static final String DEVICE = "device";
    private static final String PERSON = "person";
    private static final String LAST_SYNC = "last_sync";
    private static final String LAST_LOGGED = "last_logged";
    private static final String STATE = "state";
    private static final String IN_APP = "in_app";
    private static final String UNKNOWN = "unknown";
    private static final String IGNORED = "ignored";
    private static final long UNKNOWN_KEEP_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int UNKNOWN_MAX = 30;
    private static final long RESUME_GAP_MS = 60_000;
    private static final int MAX_ROWS = 600;
    private static final int BATCH = 100;
    private static final int MAX_READ = 2 * 1024 * 1024;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Object LOCK = new Object();
    private static volatile long lastStart;

    private CallLogSync() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isOn(Context context) {
        return prefs(context).getBoolean(ON, false);
    }

    /** Call history, and the phone's SIMs (to tell the work SIM). */
    static String[] wanted() {
        return new String[] { Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE };
    }

    static boolean hasAccess(Context context) {
        for (String permission : wanted()) if (!granted(context, permission)) return false;
        return true;
    }

    private static boolean granted(Context context, String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Turns logging on or off. Turning it on starts from now, so calls made
     * before, or while it was off, are never sent. With more than one SIM it
     * stays off until the work SIM is chosen.
     */
    static boolean setOn(Context context, boolean on, String sim) {
        SharedPreferences prefs = prefs(context);
        String chosen = sim == null ? "" : sim;
        if (on && chosen.isEmpty() && sims(context).length() > 1) return false;
        SharedPreferences.Editor edit = prefs.edit().putBoolean(ON, on);
        if (on) {
            if (!prefs.getBoolean(ON, false)) edit.putLong(SINCE, System.currentTimeMillis()).putString(STATE, "never");
            if (prefs.getString(DEVICE, null) == null) edit.putString(DEVICE, UUID.randomUUID().toString());
            edit.putString(SIM, chosen).putString(SIM_ALT, subscriptionOf(context, chosen));
        }
        edit.apply();
        WorkManager work = WorkManager.getInstance(context);
        if (on) {
            Constraints online = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(CallLogWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(online)
                .build();
            work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request);
        } else {
            work.cancelUniqueWork(WORK);
        }
        return on;
    }

    static JSONObject status(Context context) {
        SharedPreferences prefs = prefs(context);
        JSONObject status = new JSONObject();
        try {
            status.put("on", prefs.getBoolean(ON, false));
            status.put("access", hasAccess(context));
            status.put("sims", sims(context));
            status.put("sim", prefs.getString(SIM, ""));
            long last = prefs.getLong(LAST_SYNC, 0);
            status.put("lastSyncMs", last > 0 ? last : JSONObject.NULL);
            status.put("lastLogged", prefs.getInt(LAST_LOGGED, 0));
            status.put("state", prefs.getString(STATE, "never"));
        } catch (JSONException ignored) {}
        return status;
    }

    /** The phone's SIMs that place calls, with their names (needs READ_PHONE_STATE). */
    static JSONArray sims(Context context) {
        JSONArray list = new JSONArray();
        if (!granted(context, Manifest.permission.READ_PHONE_STATE)) return list;
        try {
            TelecomManager telecom = context.getSystemService(TelecomManager.class);
            if (telecom == null) return list;
            int index = 0;
            for (PhoneAccountHandle handle : telecom.getCallCapablePhoneAccounts()) {
                PhoneAccount account = telecom.getPhoneAccount(handle);
                if (account == null || !account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) continue;
                index++;
                CharSequence label = account.getLabel();
                JSONObject sim = new JSONObject();
                sim.put("id", handle.getId());
                sim.put("label", label == null || label.length() == 0 ? "SIM " + index : label.toString());
                list.put(sim);
            }
        } catch (RuntimeException | JSONException ignored) {
            // No SIM list on this phone: every SIM counts.
        }
        return list;
    }

    /** Some phones write the SIM's subscription number in the call history instead of its id. */
    private static String subscriptionOf(Context context, String sim) {
        if (sim.isEmpty() || Build.VERSION.SDK_INT < 30 || !granted(context, Manifest.permission.READ_PHONE_STATE)) return "";
        try {
            TelecomManager telecom = context.getSystemService(TelecomManager.class);
            TelephonyManager telephony = context.getSystemService(TelephonyManager.class);
            if (telecom == null || telephony == null) return "";
            for (PhoneAccountHandle handle : telecom.getCallCapablePhoneAccounts()) {
                if (!sim.equals(handle.getId())) continue;
                int subscription = telephony.getSubscriptionId(handle);
                return subscription >= 0 ? String.valueOf(subscription) : "";
            }
        } catch (RuntimeException ignored) {
            // Unknown here: the SIM id alone is matched.
        }
        return "";
    }

    /** This install's own id (no phone or SIM number in it): call logging and the setup checklist use it. */
    static String deviceId(Context context) {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            String device = prefs.getString(DEVICE, null);
            if (device == null) {
                device = UUID.randomUUID().toString();
                prefs.edit().putString(DEVICE, device).apply();
            }
            return device;
        }
    }

    /** Work calls with numbers that aren't leads, newest first (they never leave the phone by themselves). */
    static JSONArray unknown(Context context) {
        try {
            JSONArray stored = new JSONArray(prefs(context).getString(UNKNOWN, "[]"));
            return CallLogRules.keepUnknown(stored, null, null, 0, null, 0, System.currentTimeMillis(), UNKNOWN_KEEP_MS, UNKNOWN_MAX);
        } catch (JSONException error) {
            return new JSONArray();
        }
    }

    /** "Not a lead": never offer this number again. */
    static void ignore(Context context, String number) {
        String key = CallLogRules.key(number);
        if (key.length() < 6) return;
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            String ignored = prefs.getString(IGNORED, "");
            String kept = CallLogRules.addIgnored(ignored, key, 200);
            prefs.edit().putString(IGNORED, kept).putString(UNKNOWN, CallLogRules.dropUnknown(unknown(context), key).toString()).apply();
        }
    }

    /** Saved as a lead: the next sync logs its calls on the lead, so drop it here. */
    static void forget(Context context, String number) {
        String key = CallLogRules.key(number);
        synchronized (LOCK) {
            prefs(context).edit().putString(UNKNOWN, CallLogRules.dropUnknown(unknown(context), key).toString()).apply();
        }
    }

    /** A call placed from a lead in the app: its after-call sheet logs it, so this skips it. */
    static void rememberInApp(Context context, String number, long startedMs) {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            String line = CallLogRules.inAppLine(number, startedMs);
            prefs.edit().putString(IN_APP, CallLogRules.keepInApp(prefs.getString(IN_APP, ""), line, System.currentTimeMillis())).apply();
        }
    }

    /** Soon after the app opens, at most once a minute, off the main thread. */
    static void soon(Context context) {
        Context app = context.getApplicationContext();
        if (!isOn(app)) return;
        long now = System.currentTimeMillis();
        if (now - lastStart < RESUME_GAP_MS) return;
        lastStart = now;
        IO.execute(() -> run(app));
    }

    /** Looks now (blocking) and says how it went. */
    static JSONObject run(Context context) {
        synchronized (LOCK) {
            int logged = sync(context);
            if (logged > 0) PhoneBridge.signalActive("calls");
        }
        // Lead calls that connected look for their recording (owner, 22 Oct).
        if (RecordingQueue.look(context) > 0) {
            PhoneBridge.signalActive("recordings");
            RecordingQueue.announce(context, !MainActivity.inFront);
        }
        return status(context);
    }

    private static int sync(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(ON, false)) return 0;
        if (!granted(context, Manifest.permission.READ_CALL_LOG)) return state(context, "no_access");
        String base = LeadAlerts.serverUrl(context);
        if (base == null) return state(context, "error");
        String cookies;
        try {
            cookies = CookieManager.getInstance().getCookie(base);
        } catch (RuntimeException error) {
            cookies = null;
        }
        if (cookies == null || cookies.isEmpty()) return state(context, "signed_out");

        Response book = request("GET", base + "/v1/lead-desk/phone-book", base, cookies, null);
        if (book.code != 200 || book.body == null) return state(context, failure(book.code));
        Map<String, String> numbers = new HashMap<>();
        String person;
        int[] workDays = new int[0];
        int workStart = 0;
        int workEnd = 24 * 60;
        try {
            JSONObject json = new JSONObject(book.body);
            person = json.getString("person_id");
            // The team's work hours: a call outside them stays on the phone.
            JSONObject hours = json.optJSONObject("work_hours");
            if (hours != null) {
                JSONArray days = hours.optJSONArray("days");
                int start = CallLogRules.minutes(hours.optString("start"));
                int end = CallLogRules.minutes(hours.optString("end"));
                if (days != null && days.length() > 0 && start >= 0 && end > start) {
                    workDays = new int[days.length()];
                    for (int i = 0; i < days.length(); i++) workDays[i] = days.optInt(i, -1);
                    workStart = start;
                    workEnd = end;
                }
            }
            JSONArray list = json.getJSONArray("numbers");
            for (int i = 0; i < list.length(); i++) {
                String number = list.optString(i);
                String key = CallLogRules.key(number);
                if (key.length() >= 6) numbers.put(key, number);
            }
        } catch (JSONException error) {
            return state(context, "error");
        }
        long now = System.currentTimeMillis();
        if (!person.equals(prefs.getString(PERSON, person))) {
            // Someone else signed in on this phone: their logging starts now.
            prefs.edit().putString(PERSON, person).putLong(SINCE, now).apply();
            return state(context, "ok");
        }
        prefs.edit().putString(PERSON, person).apply();

        long since = prefs.getLong(SINCE, now);
        String sim = prefs.getString(SIM, "");
        String alt = prefs.getString(SIM_ALT, "");
        String inApp = prefs.getString(IN_APP, "");
        List<JSONObject> calls = new ArrayList<>();
        // What each sent row was, to queue the connected ones for their recording.
        Map<String, JSONObject> sentRows = new HashMap<>();
        JSONArray unknown = unknown(context);
        String notLeads = prefs.getString(IGNORED, "");
        long newest = since;
        String[] columns = {
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
            CallLog.Calls.PHONE_ACCOUNT_ID,
        };
        try (
            Cursor cursor = context
                .getContentResolver()
                .query(
                    CallLog.Calls.CONTENT_URI,
                    columns,
                    CallLog.Calls.DATE + " > ?",
                    new String[] { String.valueOf(since) },
                    CallLog.Calls.DATE + " ASC"
                )
        ) {
            int rows = 0;
            while (cursor != null && rows < MAX_ROWS && cursor.moveToNext()) {
                rows++;
                long date = cursor.getLong(2);
                newest = Math.max(newest, date);
                String direction = CallLogRules.direction(cursor.getInt(4));
                if (direction == null) continue;
                if (!CallLogRules.inWorkHours(date, workDays, workStart, workEnd)) continue;
                String account = cursor.isNull(5) ? "" : cursor.getString(5);
                if (!CallLogRules.onWorkSim(account, sim, alt)) continue;
                String key = CallLogRules.key(cursor.getString(1));
                // A number that is not a lead's never leaves the phone. A real
                // conversation with one is kept here to offer "Save as lead?".
                String number = key.length() >= 6 ? numbers.get(key) : null;
                if (number == null) {
                    // The setup test call is placed from the app to a colleague: not a lead to save.
                    if (
                        !CallLogRules.placedInApp(inApp, key, date) &&
                        CallLogRules.worthSaving(key, direction, cursor.getLong(3), notLeads)
                    ) {
                        unknown = CallLogRules.keepUnknown(unknown, cursor.getString(1), key, date, direction, cursor.getLong(3), now, UNKNOWN_KEEP_MS, UNKNOWN_MAX);
                    }
                    continue;
                }
                if ("outgoing".equals(direction) && CallLogRules.placedInApp(inApp, key, date)) continue;
                JSONObject call = new JSONObject();
                call.put("log_id", String.valueOf(cursor.getLong(0)));
                call.put("number", number);
                call.put("started_at", CallLogRules.iso(date));
                call.put("duration_seconds", Math.max(0, Math.min(14400, cursor.getLong(3))));
                call.put("direction", direction);
                calls.add(call);
                sentRows.put(
                    String.valueOf(cursor.getLong(0)),
                    new JSONObject()
                        .put("key", key)
                        .put("startedMs", date)
                        .put("seconds", Math.max(0, Math.min(14400, cursor.getLong(3))))
                        .put("direction", direction)
                );
            }
        } catch (RuntimeException | JSONException error) {
            return state(context, "error");
        }

        int logged = 0;
        List<JSONObject> waiting = new ArrayList<>();
        String device = deviceId(context);
        for (int start = 0; start < calls.size(); start += BATCH) {
            JSONObject body = new JSONObject();
            try {
                body.put("device_id", device);
                body.put("calls", new JSONArray(calls.subList(start, Math.min(calls.size(), start + BATCH))));
            } catch (JSONException error) {
                return state(context, "error");
            }
            Response sent = request("POST", base + "/v1/lead-desk/phone-log", base, cookies, body.toString());
            // Not sent: the same rows go again next time, and Sales Xray skips any it has.
            if (sent.code != 200 || sent.body == null) return state(context, failure(sent.code));
            try {
                JSONObject answer = new JSONObject(sent.body);
                logged += answer.optInt("logged");
                waiting.addAll(CallLogRules.waitingCalls(answer.optJSONArray("calls"), sentRows));
            } catch (JSONException ignored) {
                // Sent; the count is only for the screen.
            }
        }
        RecordingQueue.add(context, waiting);
        prefs
            .edit()
            .putString(UNKNOWN, unknown.toString())
            .putLong(SINCE, newest)
            .putLong(LAST_SYNC, now)
            .putInt(LAST_LOGGED, logged)
            .putString(STATE, "ok")
            .apply();
        return logged;
    }

    private static String failure(int code) {
        if (code == 401) return "signed_out";
        if (code <= 0) return "offline";
        return "error";
    }

    private static int state(Context context, String state) {
        prefs(context).edit().putString(STATE, state).apply();
        return 0;
    }

    private static final class Response {

        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    /** A JSON request on the seller's session (the web view's cookies), as the page would send it. */
    private static Response request(String method, String address, String origin, String cookies, String body) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            SessionCookies.attach(connection, cookies);
            connection.setRequestProperty("User-Agent", "SalesXrayApp/0.3 (call-log)");
            if (body != null) {
                // Sales Xray takes a signed-in change only from its own site.
                connection.setRequestProperty("Origin", origin);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = connection.getResponseCode();
            if (code != 200) return new Response(code, null);
            try (InputStream stream = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                int total = 0;
                while ((count = stream.read(buffer)) > 0) {
                    total += count;
                    if (total > MAX_READ) return new Response(-1, null);
                    out.write(buffer, 0, count);
                }
                return new Response(code, out.toString(StandardCharsets.UTF_8.name()));
            }
        } catch (IOException | RuntimeException error) {
            return new Response(0, null);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
