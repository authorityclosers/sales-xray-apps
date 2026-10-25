package com.authorityclosers.salesxray;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * The page's microphone, the Play-safe way (owner, 26 Oct 2026: "Add Microphone access ... carefully to
 * get app approval in playstore"). Only our own sites may ask, only for audio, and only while the seller
 * records a voice note: Android's permission is asked at that moment, behind one line saying why, and the
 * recording stops when the app goes to the background. No microphone service, nothing in the background,
 * never a call. Plain Java, for JVM tests; WebMic asks Android and answers the page.
 */
final class MicRules {

    /** PermissionRequest.RESOURCE_AUDIO_CAPTURE. */
    static final String AUDIO = "android.webkit.resource.AUDIO_CAPTURE";
    static final String RATIONALE = "Sales X-Ray uses the microphone only while you record a voice note.";

    enum Answer {
        /** Android has allowed it: the page gets the microphone. */
        GRANT,
        /** Say why, then Android's own prompt. */
        ASK,
        /** Android won't ask again: offer the app's settings, and the page hears no. */
        OPEN_SETTINGS,
        /** Not our site, not only audio, or no microphone in this build. */
        DENY,
    }

    private MicRules() {}

    /** "https://" and one of our hosts, on the default port, with nothing before the host. */
    static boolean ours(String origin, Set<String> hosts) {
        if (origin == null) return false;
        try {
            URI uri = new URI(origin);
            return (
                "https".equals(uri.getScheme()) &&
                uri.getRawUserInfo() == null &&
                uri.getPort() == -1 &&
                uri.getHost() != null &&
                hosts.contains(uri.getHost())
            );
        } catch (URISyntaxException bad) {
            return false;
        }
    }

    /** The microphone and nothing else: never the camera or protected media. */
    static boolean audioOnly(String[] resources) {
        if (resources == null || resources.length == 0) return false;
        for (String resource : resources) if (!AUDIO.equals(resource)) return false;
        return true;
    }

    static Answer answer(String origin, String[] resources, Set<String> hosts, boolean declared, boolean granted, boolean blocked) {
        if (!declared || !ours(origin, hosts) || !audioOnly(resources)) return Answer.DENY;
        if (granted) return Answer.GRANT;
        return blocked ? Answer.OPEN_SETTINGS : Answer.ASK;
    }

    /**
     * Before our pages' own scripts: remembers the microphone streams and recorders the page makes, so
     * the app can stop them when it goes to the background (a recorder that stops hands the page what
     * it has, as if the seller had tapped Stop).
     */
    static String script() {
        return "(function(){if(window.__sxMicStop)return;var streams=[],recorders=[];"
            + "var md=navigator.mediaDevices;"
            + "if(md&&md.getUserMedia){var get=md.getUserMedia.bind(md);md.getUserMedia=function(c){"
            + "return get(c).then(function(s){streams=streams.filter(function(x){return x.active});streams.push(s);return s})}}"
            + "var MR=window.MediaRecorder;"
            + "if(MR){window.MediaRecorder=class extends MR{constructor(s,o){super(s,o);"
            + "recorders=recorders.filter(function(r){return r.state!==\"inactive\"});recorders.push(this)}}}"
            + "window.__sxMicStop=function(){var n=0;"
            + "recorders.splice(0).forEach(function(r){if(r.state!==\"inactive\"){try{r.stop();n++}catch(e){}}});"
            + "streams.splice(0).forEach(function(s){s.getTracks().forEach(function(t){if(t.readyState===\"live\"){t.stop();n++}})});"
            + "return n}})();";
    }

    /** What the app runs when it goes to the background. */
    static final String STOP = "window.__sxMicStop&&window.__sxMicStop()";
}
