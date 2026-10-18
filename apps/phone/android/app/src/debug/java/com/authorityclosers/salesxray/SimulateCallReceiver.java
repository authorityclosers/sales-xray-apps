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
 *   adb shell am broadcast -n <package>/com.authorityclosers.salesxray.SimulateCallReceiver -e state start|end|want-on|want-off
 *
 * "want-on" is the in-app switch (the seller's Turn on); Android's Accessibility
 * switch stays the person's own tap.
 */
public class SimulateCallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String state = intent.getStringExtra("state");
        String result;
        if ("want-on".equals(state) || "want-off".equals(state)) {
            CallRecorderService.setWanted(context, "want-on".equals(state));
            result = "wanted=" + "want-on".equals(state);
        } else {
            result = CallRecorderService.simulate("start".equals(state)) ? "sent " + state : "recorder not running (turn it on in Accessibility)";
        }
        Log.i("SXTEST", "simulate call: " + result + " status " + CallRecorderService.status(context));
        setResultData(result);
    }
}
