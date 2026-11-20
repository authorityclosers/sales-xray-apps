// Checks the iPhone app's plain sign-in rules with swiftc alone (no simulator):
//   swiftc -o "$TMPDIR/rules-check" ios/App/App/SignInRules.swift ios/rules-check/main.swift && "$TMPDIR/rules-check"
import Foundation

var failures = 0
func check(_ condition: Bool, _ name: String) {
    if condition { print("ok   \(name)") } else { failures += 1; print("FAIL \(name)") }
}

let flow = "0b8e7c1e-6f7a-4c1f-9d42-1a2b3c4d5e6f"
let start = URL(string: "https://salesxray.authorityclosers.com/v1/auth/google/start?action=authenticate&surface=sales_xray&return_path=%2Fauth%2Fcomplete%3Fflow%3D\(flow)")!

// Which loads the app takes over
check(SignInRules.isStart(start), "google start is taken over")
check(SignInRules.isStart(URL(string: "https://app.salesxray.in/v1/auth/facebook/start?a=1")!), "facebook start on app.salesxray.in")
check(SignInRules.isStart(URL(string: "https://salesxray-dev.authorityclosers.com/v1/auth/apple/start")!), "apple start on dev")
check(!SignInRules.isStart(URL(string: "https://salesxray.authorityclosers.com/v1/auth/pulse/start")!), "pulse stays in the web view")
check(!SignInRules.isStart(URL(string: "https://evil.example/v1/auth/google/start")!), "other hosts are left alone")
check(!SignInRules.isStart(URL(string: "http://salesxray.authorityclosers.com/v1/auth/google/start")!), "plain http is left alone")
check(!SignInRules.isStart(URL(string: "https://salesxray.authorityclosers.com/v1/auth/google/start/x")!), "only the exact path")
check(!SignInRules.isStart(URL(string: "https://salesxray.authorityclosers.com/leads")!), "app pages are left alone")
check(!SignInRules.isStart(SignInRules.withChallenge(start, challenge: "x")!), "a start with a challenge is not taken twice")

// Verifier and challenge: 43 base64url characters, and the RFC 7636 test vector
let verifier = SignInRules.newVerifier()
check(verifier.count == 43 && !verifier.contains("=") && !verifier.contains("+") && !verifier.contains("/"), "verifier is 43 base64url characters")
check(SignInRules.newVerifier() != verifier, "verifiers are random")
// Expected value from: printf %s <verifier> | openssl dgst -sha256 -binary | base64 | tr "+/" "-_" | tr -d =
check(SignInRules.challenge(for: "dBjftJeZ4CVP-mJ0d4fpE5Jdbr0MDIzgQ4WI6ZZ32Sk") == "K40AlSWT96HLgezBKtpyAgzqaPXDqUNFXHNaPlQXlKU", "challenge is base64url(SHA-256(verifier)), as the server checks")
check(SignInRules.challenge(for: verifier).count == 43, "challenge is 43 characters")

// The page's flow and origin
check(SignInRules.flow(ofStart: start) == flow, "flow is read from return_path")
check(SignInRules.flow(ofStart: URL(string: "https://salesxray.authorityclosers.com/v1/auth/google/start?return_path=%2Fhome")!) == nil, "no flow, no guess")
check(SignInRules.origin(of: start) == "https://salesxray.authorityclosers.com", "origin")
let challenged = SignInRules.withChallenge(start, challenge: "abc")!
check(URLComponents(url: challenged, resolvingAgainstBaseURL: false)!.queryItems!.contains { $0.name == "app_challenge" && $0.value == "abc" }, "challenge is appended")
check(SignInRules.flow(ofStart: challenged) == flow, "return_path survives")

// What comes back
check(SignInRules.outcome(of: URL(string: "com.authorityclosers.salesxray:/auth/return#code=AbC-123_x")!) == .code("AbC-123_x"), "a code is redeemed")
check(SignInRules.outcome(of: URL(string: "com.authorityclosers.salesxray:/auth/return#result=unavailable&flow=\(flow)")!) == .result("unavailable", flow: flow), "a result with its flow")
check(SignInRules.outcome(of: URL(string: "com.authorityclosers.salesxray:/auth/return#result=<script>")!) == .result("failed", flow: nil), "an odd result reads as failed")
check(SignInRules.outcome(of: URL(string: "com.authorityclosers.salesxray:/auth/return")!) == .result("failed", flow: nil), "nothing back reads as failed")
check(SignInRules.outcome(of: URL(string: "com.other:/auth/return#code=x")!) == nil, "another scheme is not ours")
check(SignInRules.outcome(of: URL(string: "com.authorityclosers.salesxray:/elsewhere#code=x")!) == nil, "another path is not ours")

// What the app sends
check(String(data: SignInRules.exchangeBody(code: "a+b/c=", verifier: "v_1-2"), encoding: .ascii) == "code=a%2Bb%2Fc%3D&verifier=v_1-2", "exchange body is form-encoded")
check(SignInRules.completeURL(origin: "https://app.salesxray.in", flow: flow, result: "failed")?.absoluteString == "https://app.salesxray.in/auth/complete?flow=\(flow)&auth_result=failed", "complete URL")
check(SignInRules.completeURL(origin: "https://app.salesxray.in", flow: nil, result: "Bad!")?.absoluteString == "https://app.salesxray.in/auth/complete?auth_result=failed", "complete URL never carries an odd result")

// Cancelled loads are quiet; real failures are not
check(SignInRules.isCancelledLoad(domain: NSURLErrorDomain, code: NSURLErrorCancelled), "cancelled load is quiet")
check(SignInRules.isCancelledLoad(domain: "WebKitErrorDomain", code: 102), "policy-interrupted load is quiet")
check(!SignInRules.isCancelledLoad(domain: NSURLErrorDomain, code: NSURLErrorNotConnectedToInternet), "offline still shows the error page")
check(!SignInRules.isCancelledLoad(domain: NSURLErrorDomain, code: NSURLErrorTimedOut), "a timeout still shows the error page")

print(failures == 0 ? "all sign-in rules hold" : "\(failures) failed")
exit(failures == 0 ? 0 : 1)
