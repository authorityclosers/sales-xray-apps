import CryptoKit
import Foundation

/// Plain rules for the iPhone app's own sign-in (SignInHandoff), kept free of UIKit so
/// `ios/rules-check` can test them with `swiftc` alone.
///
/// Google, Facebook and Apple refuse or break sign-in inside a WKWebView, and the
/// shell used to hand the provider page to the phone's browser, which never came back
/// (18 Nov 2026, iPhone 12 Pro). The Android app's hand-off works for the iPhone as it
/// is: the Sales Xray sign-in start gets a PKCE-style `app_challenge`, runs in an
/// ASWebAuthenticationSession, and the server hands a one-time code back to
/// com.authorityclosers.salesxray:/auth/return. The app posts it, with its verifier,
/// to /v1/auth/google/app-exchange in its own web view and lands on /auth/complete.
enum SignInRules {

    /// Sales Xray's hosts (the same list as Android's PhoneBridge.HOSTS).
    static let hosts: Set<String> = [
        "salesxray.authorityclosers.com",
        "app.salesxray.in",
        "salesxray-staging.authorityclosers.com",
        "salesxray-dev.authorityclosers.com",
    ]

    static let returnScheme = "com.authorityclosers.salesxray"
    static let returnPath = "/auth/return"
    static let exchangePath = "/v1/auth/google/app-exchange"
    static let completePath = "/auth/complete"

    private static let startPaths: Set<String> = [
        "/v1/auth/google/start",
        "/v1/auth/facebook/start",
        "/v1/auth/apple/start",
    ]

    /// True for exactly a Sales Xray sign-in start the app has not taken over yet.
    static func isStart(_ url: URL) -> Bool {
        guard url.scheme?.lowercased() == "https",
              let host = url.host?.lowercased(), hosts.contains(host),
              startPaths.contains(url.path),
              let parts = URLComponents(url: url, resolvingAgainstBaseURL: false)
        else { return false }
        return !(parts.queryItems ?? []).contains { $0.name == "app_challenge" }
    }

    static func origin(of url: URL) -> String? {
        guard let host = url.host?.lowercased(), hosts.contains(host) else { return nil }
        return "https://" + host
    }

    /// The start with the challenge only this app can answer.
    static func withChallenge(_ url: URL, challenge: String) -> URL? {
        guard var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        parts.queryItems = (parts.queryItems ?? []) + [URLQueryItem(name: "app_challenge", value: challenge)]
        return parts.url
    }

    static func base64Url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// 32 random bytes as base64url: 43 characters, as on Android.
    static func newVerifier() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        var generator = SystemRandomNumberGenerator()
        for index in bytes.indices { bytes[index] = UInt8.random(in: .min ... .max, using: &generator) }
        return base64Url(Data(bytes))
    }

    /// base64url(SHA-256(verifier)), the server's `app_challenge` (43 characters).
    static func challenge(for verifier: String) -> String {
        base64Url(Data(SHA256.hash(data: Data(verifier.utf8))))
    }

    /// The page's flow id, from the start's return_path (/auth/complete?flow=<uuid>).
    static func flow(ofStart url: URL) -> String? {
        guard let returnPath = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "return_path" })?.value,
              let inner = URLComponents(string: returnPath),
              let flow = inner.queryItems?.first(where: { $0.name == "flow" })?.value
        else { return nil }
        return isFlow(flow) ? flow : nil
    }

    static func isFlow(_ value: String) -> Bool {
        value.count == 36 && value.allSatisfy { $0.isHexDigit || $0 == "-" }
    }

    static func isResult(_ value: String) -> Bool {
        (1 ... 32).contains(value.count) && value.allSatisfy { ("a" ... "z").contains($0) || $0 == "_" }
    }

    enum Outcome: Equatable {
        /// A one-time code to redeem in the web view.
        case code(String)
        /// No code: tell the page how it ended ("failed", "unavailable", ...), with the flow if the server sent one.
        case result(String, flow: String?)
    }

    /// What the server handed back to com.authorityclosers.salesxray:/auth/return. Nil: not ours.
    static func outcome(of callback: URL) -> Outcome? {
        guard callback.scheme?.lowercased() == returnScheme, callback.path == returnPath else { return nil }
        let fields = fragment(callback.fragment)
        if let code = fields["code"], !code.isEmpty, code.count <= 2048 { return .code(code) }
        let flow = fields["flow"].flatMap { isFlow($0) ? $0 : nil }
        let result = fields["result"].flatMap { isResult($0) ? $0 : nil } ?? "failed"
        return .result(result, flow: flow)
    }

    static func fragment(_ raw: String?) -> [String: String] {
        guard let raw, !raw.isEmpty, let parts = URLComponents(string: "?" + raw) else { return [:] }
        var fields: [String: String] = [:]
        for item in parts.queryItems ?? [] where fields[item.name] == nil {
            fields[item.name] = item.value ?? ""
        }
        return fields
    }

    /// The form body the exchange takes: code and verifier, nothing else.
    static func exchangeBody(code: String, verifier: String) -> Data {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let encode = { (value: String) in value.addingPercentEncoding(withAllowedCharacters: allowed) ?? "" }
        return Data("code=\(encode(code))&verifier=\(encode(verifier))".utf8)
    }

    /// The page that tells the waiting sign-in screen how it ended.
    static func completeURL(origin: String, flow: String?, result: String) -> URL? {
        guard var parts = URLComponents(string: origin + completePath) else { return nil }
        var items: [URLQueryItem] = []
        if let flow { items.append(URLQueryItem(name: "flow", value: flow)) }
        items.append(URLQueryItem(name: "auth_result", value: isResult(result) ? result : "failed"))
        parts.queryItems = items
        return parts.url
    }

    /// A load that was cancelled, not broken: the app handed the page elsewhere (a
    /// sign-in, a link the phone opens) or a newer load took over. It must not show
    /// "Sales X-Ray can't be reached".
    static func isCancelledLoad(domain: String, code: Int) -> Bool {
        (domain == NSURLErrorDomain && code == NSURLErrorCancelled) ||
            // WebKitErrorFrameLoadInterruptedByPolicyChange
            (domain == "WebKitErrorDomain" && code == 102)
    }
}
