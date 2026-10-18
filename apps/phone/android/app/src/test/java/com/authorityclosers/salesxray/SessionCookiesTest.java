package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Call logging and lead alerts send the seller's session once (production 401, 18 Oct). */
public class SessionCookiesTest {

    private static final String SESSION = "ac_session=abc123";

    private ServerSocket server;
    private final List<String> seen = Collections.synchronizedList(new ArrayList<>());
    private CookieHandler before;

    @Before
    public void start() throws IOException {
        before = CookieHandler.getDefault();
        server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Thread answer = new Thread(() -> {
            // One plain HTTP answer per request: note every Cookie header, reply 200.
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        int colon = line.indexOf(':');
                        if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Cookie")) seen.add(line.substring(colon + 1).trim());
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                } catch (IOException closed) {
                    return;
                }
            }
        });
        answer.setDaemon(true);
        answer.start();
    }

    @After
    public void stop() throws IOException {
        server.close();
        CookieHandler.setDefault(before);
    }

    /** Like Capacitor's CapacitorCookieManager: hands the web view's cookies to every connection. */
    private static final class WebViewCookies extends CookieHandler {

        @Override
        public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) {
            return Collections.singletonMap("Cookie", Collections.singletonList(SESSION));
        }

        @Override
        public void put(URI uri, Map<String, List<String>> headers) {}
    }

    private void send() throws IOException {
        URL url = new URL("http://127.0.0.1:" + server.getLocalPort() + "/v1/lead-desk/phone-book");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        SessionCookies.attach(connection, SESSION);
        assertEquals(200, connection.getResponseCode());
        connection.disconnect();
    }

    private int sessions() {
        int count = 0;
        for (String header : seen) {
            for (String part : header.split(";")) if (part.trim().startsWith("ac_session=")) count++;
        }
        return count;
    }

    @Test
    public void withTheWebViewsCookieHandlerTheSessionGoesOnce() throws IOException {
        CookieHandler.setDefault(new WebViewCookies());
        send();
        assertEquals(1, sessions());
    }

    @Test
    public void withoutACookieHandlerTheAppSetsTheSessionItself() throws IOException {
        CookieHandler.setDefault(null);
        send();
        assertEquals(1, sessions());
    }
}
