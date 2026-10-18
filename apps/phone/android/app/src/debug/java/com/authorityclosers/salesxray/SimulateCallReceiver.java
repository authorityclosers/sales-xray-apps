package com.authorityclosers.salesxray;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Debug builds only (src/debug; a release build has no such class or receiver):
 * drives the call recorder's own start and stop, for phones without a SIM and
 * for the emulator test (orchestrator, 19 Oct).
 *
 *   adb shell am broadcast -n <package>/com.authorityclosers.salesxray.SimulateCallReceiver -e state start|end|voip-start|voip-end|want-on|want-off
 *
 * "voip-start"/"voip-end" put the phone in and out of "in communication" mode,
 * the signal WhatsApp, Meet and Telegram calls give; the recorder reacts to it
 * exactly as to theirs.
 *
 * "want-on" is the in-app switch (the seller's Turn on); Android's Accessibility
 * switch stays the person's own tap.
 */
public class SimulateCallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String state = intent.getStringExtra("state");
        String result;
        if ("voip-start".equals(state) || "voip-end".equals(state)) {
            android.media.AudioManager audio = context.getSystemService(android.media.AudioManager.class);
            audio.setMode("voip-start".equals(state) ? android.media.AudioManager.MODE_IN_COMMUNICATION : android.media.AudioManager.MODE_NORMAL);
            result = "mode " + audio.getMode();
        } else if ("want-on".equals(state) || "want-off".equals(state)) {
            CallRecorderService.setWanted(context, "want-on".equals(state));
            result = "wanted=" + "want-on".equals(state);
        } else {
            result = CallRecorderService.simulate("start".equals(state)) ? "sent " + state : "recorder not running (turn it on in Accessibility)";
        }
        Log.i("SXTEST", "simulate call: " + result + " status " + CallRecorderService.status(context));
        setResultData(result);
    }
}
