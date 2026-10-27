import UIKit
import WebKit

/// The simulator check in iphone.yml (launch argument `-SXSelfTest`, never set by a person): once the
/// bundled web app is up, it asks the page where it came from, calls the API through the native path,
/// asks the phone channel for its status, plays a haptic and opens the share sheet, then writes what it
/// saw to Documents/sx-selftest.json for the workflow to read.
enum SXSelfTest {
    static let file = "sx-selftest.json"

    static func schedule(on controller: SXBridgeViewController) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 20) { [weak controller] in
            guard let controller, let webView = controller.webView else { return }
            webView.callAsyncJavaScript(script, arguments: [:], in: nil, in: .page) { result in
                var report: [String: Any] = ["ranAtMs": Int(Date().timeIntervalSince1970 * 1000)]
                switch result {
                case .success(let value):
                    if let text = value as? String,
                       let data = text.data(using: .utf8),
                       let page = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
                        report["page"] = page
                    }
                case .failure(let error):
                    report["error"] = String(describing: error)
                }
                // Give the page's haptic a moment to reach the native side before counting.
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                    report["hapticsPlayed"] = controller.hapticsPlayed
                    write(report)
                    controller.share(text: "Sales X-Ray: the iPhone app's share sheet", url: SXBridgeViewController.server)
                }
            }
        }
    }

    private static func write(_ report: [String: Any]) {
        guard let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first,
              let data = try? JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys]) else { return }
        try? data.write(to: folder.appendingPathComponent(file))
        NSLog("SXSelfTest %@", String(data: data, encoding: .utf8) ?? "")
    }

    private static let script = """
    const out = {};
    out.href = location.href;
    out.bundled = location.protocol === "capacitor:";
    out.title = document.title;
    out.inApp = document.documentElement.hasAttribute("data-sx-app");
    out.capacitor = typeof Capacitor !== "undefined" ? Capacitor.getPlatform() : null;
    try {
      const leads = await fetch("/leads/");
      out.leadsScreen = leads.status;
      out.leadsBytes = (await leads.text()).length;
    } catch (e) { out.leadsError = String(e); }
    try {
      const api = await fetch("/v1/auth/email-code/config?surface=sales_xray", { credentials: "same-origin" });
      out.apiStatus = api.status;
      const body = await api.json();
      out.apiKeys = Object.keys(body).sort().slice(0, 8);
    } catch (e) { out.apiError = String(e); }
    out.phoneStatus = await new Promise((resolve) => {
      const id = "selftest-status";
      SXPhone.addEventListener("message", (event) => {
        try { const reply = JSON.parse(String(event.data)); if (reply.id === id) resolve(reply.result); } catch (e) {}
      });
      SXPhone.postMessage(JSON.stringify({ id, method: "status", args: {} }));
      setTimeout(() => resolve(null), 5000);
    });
    SXHaptics.success();
    return JSON.stringify(out);
    """
}
