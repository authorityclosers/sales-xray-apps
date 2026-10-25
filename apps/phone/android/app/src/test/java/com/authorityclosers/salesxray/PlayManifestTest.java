package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * What the Play build may hold (Play's microphone and foreground-service rules): from 1.0.35 the
 * microphone for voice notes, but no microphone service, no recorder services and no camera.
 */
public class PlayManifestTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String TOOLS = "http://schemas.android.com/tools";

    private static Document read(String flavour) throws Exception {
        File file = new File("src/" + flavour + "/AndroidManifest.xml");
        if (!file.exists()) file = new File("app/src/" + flavour + "/AndroidManifest.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(file);
    }

    /** Each element's android:name, to its tools:node ("" when it has none). */
    private static Map<String, String> named(Document manifest, String tag) {
        Map<String, String> found = new HashMap<>();
        NodeList nodes = manifest.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            found.put(element.getAttributeNS(ANDROID, "name"), element.getAttributeNS(TOOLS, "node"));
        }
        return found;
    }

    @Test
    public void playKeepsTheMicrophoneButNoMicrophoneService() throws Exception {
        Map<String, String> play = named(read("play"), "uses-permission");
        assertFalse("RECORD_AUDIO stays in the Play build", play.containsKey("android.permission.RECORD_AUDIO"));
        assertEquals("remove", play.get("android.permission.FOREGROUND_SERVICE_MICROPHONE"));
        assertEquals("remove", play.get("android.permission.FOREGROUND_SERVICE"));
        Map<String, String> services = named(read("play"), "service");
        assertEquals("remove", services.get("com.authorityclosers.salesxray.AppRecorder"));
        assertEquals("remove", services.get("com.authorityclosers.salesxray.CallRecorderService"));
    }

    @Test
    public void noBuildAsksForTheCamera() throws Exception {
        Map<String, String> main = named(read("main"), "uses-permission");
        assertTrue(main.containsKey("android.permission.RECORD_AUDIO"));
        assertFalse(main.containsKey("android.permission.CAMERA"));
        assertFalse(named(read("play"), "uses-permission").containsKey("android.permission.CAMERA"));
    }
}
