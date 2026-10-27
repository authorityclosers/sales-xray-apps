import UIKit
import WebKit
import Capacitor
import UserNotifications

/// Sales X-Ray on the iPhone: the same web app as every other platform, bundled in the app.
///
/// iphone.yml puts the web app's static export (the platform repo's `AC_SALES_XRAY_STATIC_PREVIEW=1`
/// build, the one Android already carries) in the app, and Capacitor serves it at capacitor://localhost.
/// The page then needs three things a browser would have given it, all added here:
///  - Its pages: one folder per screen (`leads/index.html`), which SXRouter finds; Capacitor's own
///    router would answer every screen with the home page.
///  - Its API: the web app calls `/v1/...` on its own address. Here those calls go to the server
///    through Capacitor's native HTTP, so cookies live in the app's own jar and no CORS is involved.
///  - The phone app's channel: `window.SXPhone` and `window.SXHaptics`, the same contract the Android
///    app gives (apps/sales-xray-web/app/leads/native-phone.ts, app/ui/haptics.ts). `status` says
///    what an iPhone can do, so the web app keeps tel: links where Android places calls itself.
final class SXBridgeViewController: CAPBridgeViewController {
    static let server = "https://salesxray.authorityclosers.com"

    private(set) var hapticsPlayed = 0
    private var phone: SXPhoneChannel?

    override func router() -> Router {
        SXRouter()
    }

    override func capacitorDidLoad() {
        super.capacitorDidLoad()
        guard let controller = webView?.configuration.userContentController else { return }
        // Added after Capacitor's own scripts, so the API calls pass through its native HTTP.
        controller.addUserScript(WKUserScript(source: Self.pageScript(), injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let phone = SXPhoneChannel(controller: self)
        controller.add(WeakMessageHandler(phone), name: SXPhoneChannel.name)
        self.phone = phone
        if ProcessInfo.processInfo.arguments.contains("-SXSelfTest") {
            SXSelfTest.schedule(on: self)
        }
    }

    func playHaptic(_ kind: String) {
        hapticsPlayed += 1
        switch kind {
        case "success", "done":
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        case "warning":
            UINotificationFeedbackGenerator().notificationOccurred(.warning)
        case "error":
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        case "urgent":
            let heavy = UIImpactFeedbackGenerator(style: .heavy)
            for beat in 0..<3 {
                DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(beat * 180)) { heavy.impactOccurred() }
            }
        case "press":
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        default:
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
    }

    func share(text: String, url: String?) {
        var items: [Any] = [text]
        if let url, let link = URL(string: url) { items.append(link) }
        let sheet = UIActivityViewController(activityItems: items, applicationActivities: nil)
        sheet.popoverPresentationController?.sourceView = view
        present(sheet, animated: true)
    }

    /// The page's own setup, before any of its scripts: the phone channel, haptics, and (bundled only)
    /// the API address.
    private static func pageScript() -> String {
        """
        (function () {
          if (window.SXPhone) return;
          var post = function (message) { window.webkit.messageHandlers.\(SXPhoneChannel.name).postMessage(message); };
          var listeners = [];
          window.SXPhone = {
            postMessage: function (data) { post(String(data)); },
            addEventListener: function (type, listener) { if (type === "message") listeners.push(listener); }
          };
          window.__sxPhoneDeliver = function (data) {
            listeners.slice().forEach(function (listener) { try { listener({ data: data }); } catch (e) {} });
          };
          var haptics = { play: function (kind) { post(JSON.stringify({ method: "haptic", args: { kind: String(kind) } })); } };
          ["tap", "success", "warning", "error", "urgent"].forEach(function (kind) { haptics[kind] = function () { haptics.play(kind); }; });
          window.SXHaptics = haptics;
          if (location.protocol !== "capacitor:") return;
          var server = \(jsString(server));
          var api = function (path) { return /^\\/v1(\\/|$)/.test(path); };
          var toServer = function (url) {
            if (typeof url !== "string") return url;
            if (url.charAt(0) === "/" && url.charAt(1) !== "/" && api(url)) return server + url;
            return url;
          };
          var fetchOnPage = window.fetch;
          window.fetch = function (resource, options) {
            if (typeof resource === "string") resource = toServer(resource);
            else if (resource instanceof URL && resource.origin === location.origin && api(resource.pathname)) resource = server + resource.pathname + resource.search;
            else if (resource instanceof Request) {
              var at = new URL(resource.url);
              if (at.origin === location.origin && api(at.pathname)) resource = new Request(server + at.pathname + at.search, resource);
            }
            return fetchOnPage.call(this, resource, options);
          };
          var open = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function (method, url) {
            arguments[1] = toServer(String(url));
            return open.apply(this, arguments);
          };
        })();
        """
    }

    static func jsString(_ value: String) -> String {
        let data = try? JSONSerialization.data(withJSONObject: [value])
        let array = data.flatMap { String(data: $0, encoding: .utf8) } ?? "[\"\"]"
        return String(array.dropFirst().dropLast())
    }
}

/// The web app's static export has one folder per screen (`trailingSlash`), so `/leads/` is
/// `leads/index.html`. A file with an extension is itself. A screen the export doesn't have gets
/// its 404 page.
struct SXRouter: Router {
    var basePath: String = ""

    func route(for path: String) -> String {
        let clean = path.removingPercentEncoding ?? path
        if clean.contains("..") { return basePath + "/404.html" }
        if !URL(fileURLWithPath: clean).pathExtension.isEmpty { return basePath + clean }
        let folder = clean.hasSuffix("/") ? clean : clean + "/"
        for candidate in [folder + "index.html", (clean.hasSuffix("/") ? String(clean.dropLast()) : clean) + ".html"] {
            if FileManager.default.fileExists(atPath: basePath + candidate) { return basePath + candidate }
        }
        return basePath + (FileManager.default.fileExists(atPath: basePath + "/404.html") ? "/404.html" : "/index.html")
    }
}

/// `window.SXPhone` on the iPhone: requests `{id, method, args}`, replies `{id, ok, result | error}`.
/// Methods an iPhone can't do answer `unsupported`; `status` tells the page before it asks.
final class SXPhoneChannel: NSObject, WKScriptMessageHandler {
    static let name = "sxphone"
    private weak var controller: SXBridgeViewController?

    init(controller: SXBridgeViewController) {
        self.controller = controller
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let text = message.body as? String,
              let data = text.data(using: .utf8),
              let request = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let method = request["method"] as? String else { return }
        let id = request["id"] as? String
        let args = request["args"] as? [String: Any] ?? [:]
        handle(method, args) { [weak self] result in
            guard let id else { return }  // haptics are fire-and-forget
            switch result {
            case .success(let value): self?.reply(["id": id, "ok": true, "result": value])
            case .failure(let error): self?.reply(["id": id, "ok": false, "error": error.code])
            }
        }
    }

    struct Failure: Error { let code: String }

    private func handle(_ method: String, _ args: [String: Any], done: @escaping (Result<Any, Failure>) -> Void) {
        guard let controller else { return done(.failure(Failure(code: "unavailable"))) }
        switch method {
        case "status":
            done(.success([
                "platform": "ios",
                "version": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "",
                "calling": false,
                "callAccess": false,
                "audio": false,
                "connect": false,
                "signIn": false,
                "nativeSignIn": [String](),
                "theme": "system"
            ] as [String: Any]))
        case "haptic":
            controller.playHaptic(args["kind"] as? String ?? "tap")
            done(.success(["played": true]))
        case "theme":
            let mode = args["mode"] as? String ?? "system"
            controller.view.window?.overrideUserInterfaceStyle = mode == "dark" ? .dark : mode == "light" ? .light : .unspecified
            done(.success(["mode": mode]))
        case "share":
            controller.share(text: args["text"] as? String ?? "Sales X-Ray", url: args["url"] as? String)
            done(.success(["shown": true]))
        case "requestNotifications":
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, _ in
                DispatchQueue.main.async { done(.success(["granted": granted])) }
            }
        default:
            done(.failure(Failure(code: "unsupported")))
        }
    }

    private func reply(_ message: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: message),
              let text = String(data: data, encoding: .utf8) else { return }
        controller?.webView?.callAsyncJavaScript("window.__sxPhoneDeliver(data)", arguments: ["data": text], in: nil, in: .page, completionHandler: nil)
    }
}

/// WKUserContentController keeps its handlers strongly; this keeps the channel from holding itself.
final class WeakMessageHandler: NSObject, WKScriptMessageHandler {
    private weak var target: WKScriptMessageHandler?

    init(_ target: WKScriptMessageHandler) {
        self.target = target
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(userContentController, didReceive: message)
    }
}
