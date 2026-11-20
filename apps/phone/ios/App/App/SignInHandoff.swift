import AuthenticationServices
import Capacitor
import UIKit
import WebKit

/// The app's view controller: Capacitor's, plus the sign-in hand-off and quiet cancels.
final class MainViewController: CAPBridgeViewController {

    private var quieter: QuietCancelledLoads?

    override func capacitorDidLoad() {
        super.capacitorDidLoad()
        bridge?.registerPluginInstance(SignInHandoffPlugin())
        if let web = webView, let capacitor = web.navigationDelegate {
            let wrapper = QuietCancelledLoads(capacitor)
            quieter = wrapper
            web.navigationDelegate = wrapper
        }
    }
}

/// Opens Sales Xray sign-in starts (Google, Facebook, Apple) in an
/// ASWebAuthenticationSession and redeems the code in the app (SignInRules).
@objc(SignInHandoffPlugin)
final class SignInHandoffPlugin: CAPPlugin, CAPBridgedPlugin, ASWebAuthenticationPresentationContextProviding {

    let identifier = "SignInHandoffPlugin"
    let jsName = "SignInHandoff"
    let pluginMethods: [CAPPluginMethod] = []

    private var session: ASWebAuthenticationSession?

    override func shouldOverrideLoad(_ navigationAction: WKNavigationAction) -> NSNumber? {
        guard navigationAction.targetFrame?.isMainFrame ?? true,
              let url = navigationAction.request.url,
              SignInRules.isStart(url)
        else { return nil }
        DispatchQueue.main.async { self.begin(url) }
        return true
    }

    private func begin(_ start: URL) {
        guard session == nil, let origin = SignInRules.origin(of: start) else { return }
        let flow = SignInRules.flow(ofStart: start)
        let verifier = SignInRules.newVerifier()
        guard let url = SignInRules.withChallenge(start, challenge: SignInRules.challenge(for: verifier)) else {
            report(origin: origin, flow: flow, result: "failed")
            return
        }
        let auth = ASWebAuthenticationSession(url: url, callbackURLScheme: SignInRules.returnScheme) { [weak self] callback, error in
            DispatchQueue.main.async {
                self?.session = nil
                self?.finish(callback: callback, error: error, origin: origin, flow: flow, verifier: verifier)
            }
        }
        auth.presentationContextProvider = self
        session = auth
        if !auth.start() {
            session = nil
            report(origin: origin, flow: flow, result: "unavailable")
        }
    }

    private func finish(callback: URL?, error: Error?, origin: String, flow: String?, verifier: String) {
        // Closed by the person, or nothing came back: the page shows its message (as on Android).
        guard error == nil, let callback, let outcome = SignInRules.outcome(of: callback) else {
            report(origin: origin, flow: flow, result: "failed")
            return
        }
        switch outcome {
        case .code(let code):
            guard let web = bridge?.webView, let target = URL(string: origin + SignInRules.exchangePath) else { return }
            var request = URLRequest(url: target)
            request.httpMethod = "POST"
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            request.httpBody = SignInRules.exchangeBody(code: code, verifier: verifier)
            web.load(request)
        case .result(let result, let returnedFlow):
            report(origin: origin, flow: returnedFlow ?? flow, result: result)
        }
    }

    private func report(origin: String, flow: String?, result: String) {
        guard let web = bridge?.webView, let url = SignInRules.completeURL(origin: origin, flow: flow, result: result) else { return }
        web.load(URLRequest(url: url))
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        bridge?.webView?.window ?? ASPresentationAnchor()
    }
}

/// Capacitor shows its error page for every failed load, including loads that were only
/// cancelled (a link the phone opens elsewhere, a sign-in the app took over). This sits
/// in front of Capacitor's navigation delegate and drops just those.
final class QuietCancelledLoads: NSObject, WKNavigationDelegate {

    private let inner: WKNavigationDelegate

    init(_ inner: WKNavigationDelegate) {
        self.inner = inner
        super.init()
    }

    override func responds(to selector: Selector!) -> Bool {
        super.responds(to: selector) || (inner as AnyObject).responds(to: selector)
    }

    override func forwardingTarget(for selector: Selector!) -> Any? {
        (inner as AnyObject).responds(to: selector) ? inner : super.forwardingTarget(for: selector)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        let failure = error as NSError
        if SignInRules.isCancelledLoad(domain: failure.domain, code: failure.code) { return }
        inner.webView?(webView, didFailProvisionalNavigation: navigation, withError: error)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        let failure = error as NSError
        if SignInRules.isCancelledLoad(domain: failure.domain, code: failure.code) { return }
        inner.webView?(webView, didFail: navigation, withError: error)
    }
}
