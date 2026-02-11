import SwiftUI
import WebKit
import Combine

final class WebViewStore: NSObject, ObservableObject {
    @Published var consoleEntries: [ConsoleEntry] = []
    @Published var requestEntries: [RequestEntry] = []
    @Published var isLoading: Bool = false
    @Published var lastLoadedURL: String = ""
    @Published var activeAlert: JSAlert?
    @Published var activePrompt: JSPrompt?
    @Published var canGoBack: Bool = false
    @Published var canGoForward: Bool = false

    fileprivate let webView: WKWebView
    private let messageHandler = WeakScriptMessageHandler()
    private var canGoBackObservation: NSKeyValueObservation?
    private var canGoForwardObservation: NSKeyValueObservation?
    private var isLoadingObservation: NSKeyValueObservation?
    private var urlObservation: NSKeyValueObservation?

    override init() {
        let contentController = WKUserContentController()
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        config.userContentController = contentController

        webView = WKWebView(frame: .zero, configuration: config)
        #if os(iOS)
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.scrollView.contentInset = .zero
        webView.scrollView.scrollIndicatorInsets = .zero
        #endif

        super.init()

        messageHandler.owner = self
        contentController.add(messageHandler, name: "bridge")
        contentController.addUserScript(Self.consoleAndNetworkScript)

        webView.navigationDelegate = self
        webView.uiDelegate = self

        canGoBackObservation = webView.observe(\.canGoBack, options: [.initial, .new]) { [weak self] view, _ in
            self?.canGoBack = view.canGoBack
        }
        canGoForwardObservation = webView.observe(\.canGoForward, options: [.initial, .new]) { [weak self] view, _ in
            self?.canGoForward = view.canGoForward
        }
        isLoadingObservation = webView.observe(\.isLoading, options: [.initial, .new]) { [weak self] view, _ in
            self?.isLoading = view.isLoading
        }
        urlObservation = webView.observe(\.url, options: [.new]) { [weak self] view, _ in
            if let url = view.url?.absoluteString {
                self?.lastLoadedURL = url
            }
        }
    }

    func load(urlString: String, userAgent: String) {
        let trimmed = urlString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        let normalized = trimmed.hasPrefix("http://") || trimmed.hasPrefix("https://")
            ? trimmed
            : "https://\(trimmed)"

        guard let url = URL(string: normalized) else {
            appendConsole(level: "error", message: "Invalid URL: \(trimmed)")
            return
        }

        if userAgent.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            webView.customUserAgent = nil
        } else {
            webView.customUserAgent = userAgent
        }

        lastLoadedURL = normalized
        webView.load(URLRequest(url: url))
    }

    func injectJavaScript(_ script: String) {
        let trimmed = script.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        webView.evaluateJavaScript(trimmed) { [weak self] result, error in
            if let error = error {
                self?.appendConsole(level: "error", message: "JS inject error: \(error.localizedDescription)")
                return
            }
            if let value = result {
                self?.appendConsole(level: "log", message: "JS inject result: \(String(describing: value))")
            } else {
                self?.appendConsole(level: "log", message: "JS inject: ok")
            }
        }
    }

    func clearLogs() {
        consoleEntries.removeAll()
        requestEntries.removeAll()
    }

    func goBack() {
        webView.goBack()
    }

    func goForward() {
        webView.goForward()
    }

    func reload() {
        webView.reload()
    }

    fileprivate func appendConsole(level: String, message: String) {
        consoleEntries.append(ConsoleEntry(level: level, message: message))
        if consoleEntries.count > 500 {
            consoleEntries.removeFirst(consoleEntries.count - 500)
        }
    }

    fileprivate func appendRequest(kind: String, method: String, url: String) {
        requestEntries.append(RequestEntry(kind: kind, method: method, url: url))
        if requestEntries.count > 500 {
            requestEntries.removeFirst(requestEntries.count - 500)
        }
    }

    fileprivate func presentAlert(message: String, isConfirm: Bool, completion: @escaping (Bool) -> Void) {
        DispatchQueue.main.async {
            self.activeAlert = JSAlert(message: message, isConfirm: isConfirm) { value in
                completion(value)
                self.activeAlert = nil
            }
        }
    }

    fileprivate func presentPrompt(message: String, defaultText: String?, completion: @escaping (String?) -> Void) {
        DispatchQueue.main.async {
            self.activePrompt = JSPrompt(message: message, defaultText: defaultText) { value in
                completion(value)
                self.activePrompt = nil
            }
        }
    }
}

extension WebViewStore: WKNavigationDelegate, WKUIDelegate {
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        isLoading = true
        if let url = webView.url?.absoluteString {
            appendRequest(kind: "navigate", method: "GET", url: url)
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        isLoading = false
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        isLoading = false
        appendConsole(level: "error", message: error.localizedDescription)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        isLoading = false
        appendConsole(level: "error", message: error.localizedDescription)
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let url = navigationAction.request.url?.absoluteString {
            appendRequest(kind: "navigationAction", method: navigationAction.request.httpMethod ?? "GET", url: url)
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        if let response = navigationResponse.response as? HTTPURLResponse,
           let url = response.url?.absoluteString {
            appendRequest(kind: "navigationResponse", method: "GET", url: "\(response.statusCode) \(url)")
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        presentAlert(message: message, isConfirm: false) { _ in
            completionHandler()
        }
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        presentAlert(message: message, isConfirm: true) { value in
            completionHandler(value)
        }
    }

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) {
        presentPrompt(message: prompt, defaultText: defaultText) { value in
            completionHandler(value)
        }
    }
}

struct JSAlert: Identifiable {
    let id = UUID()
    let message: String
    let isConfirm: Bool
    let completion: (Bool) -> Void
}

struct JSPrompt: Identifiable {
    let id = UUID()
    let message: String
    let defaultText: String?
    let completion: (String?) -> Void
}

private extension WebViewStore {
    static let consoleAndNetworkScript: WKUserScript = {
        let source = """
        (function() {
          function send(type, payload) {
            try {
              window.webkit.messageHandlers.bridge.postMessage({ type: type, payload: payload });
            } catch (e) {}
          }

          var originalLog = console.log;
          var originalWarn = console.warn;
          var originalError = console.error;

          console.log = function() {
            send("console", { level: "log", args: Array.from(arguments).map(String) });
            if (originalLog) { originalLog.apply(console, arguments); }
          };

          console.warn = function() {
            send("console", { level: "warn", args: Array.from(arguments).map(String) });
            if (originalWarn) { originalWarn.apply(console, arguments); }
          };

          console.error = function() {
            send("console", { level: "error", args: Array.from(arguments).map(String) });
            if (originalError) { originalError.apply(console, arguments); }
          };

          window.addEventListener("error", function(event) {
            var msg = event.message || "Unknown JS error";
            var loc = "";
            if (event.filename) { loc += event.filename; }
            if (event.lineno) { loc += ":" + event.lineno; }
            if (event.colno) { loc += ":" + event.colno; }
            send("js_error", { message: msg, location: loc });
          });

          window.addEventListener("unhandledrejection", function(event) {
            var reason = event.reason ? String(event.reason) : "Unhandled promise rejection";
            send("js_error", { message: reason, location: "promise" });
          });

          var originalFetch = window.fetch;
          if (originalFetch) {
            window.fetch = function() {
              var url = arguments[0];
              var opts = arguments[1] || {};
              var method = (opts && opts.method) ? opts.method : "GET";
              send("request", { kind: "fetch", method: String(method), url: String(url) });
              return originalFetch.apply(this, arguments);
            };
          }

          var originalOpen = XMLHttpRequest.prototype.open;
          var originalSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(method, url) {
            this.__method = method || "GET";
            this.__url = url || "";
            return originalOpen.apply(this, arguments);
          };
          XMLHttpRequest.prototype.send = function() {
            send("request", { kind: "xhr", method: this.__method || "GET", url: String(this.__url || "") });
            return originalSend.apply(this, arguments);
          };

          function sendResourceEntry(entry) {
            if (!entry || !entry.name) { return; }
            var initiator = entry.initiatorType || "resource";
            send("request", { kind: "resource", method: String(initiator), url: String(entry.name) });
          }

          if (window.PerformanceObserver) {
            try {
              var observer = new PerformanceObserver(function(list) {
                list.getEntries().forEach(sendResourceEntry);
              });
              observer.observe({ entryTypes: ["resource"] });
            } catch (e) {}
          }

          if (window.performance && performance.getEntriesByType) {
            try {
              performance.getEntriesByType("resource").forEach(sendResourceEntry);
            } catch (e) {}
          }
        })();
        """
        return WKUserScript(source: source, injectionTime: .atDocumentStart, forMainFrameOnly: false)
    }()
}

private final class WeakScriptMessageHandler: NSObject, WKScriptMessageHandler {
    weak var owner: WebViewStore?

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any],
              let type = body["type"] as? String else { return }

        if type == "console",
           let payload = body["payload"] as? [String: Any],
           let level = payload["level"] as? String,
           let args = payload["args"] as? [String] {
            owner?.appendConsole(level: level, message: args.joined(separator: " "))
            return
        }

        if type == "js_error",
           let payload = body["payload"] as? [String: Any],
           let message = payload["message"] as? String {
            let location = payload["location"] as? String ?? ""
            let combined = location.isEmpty ? message : "\(message) (\(location))"
            owner?.appendConsole(level: "error", message: combined)
            return
        }

        if type == "request",
           let payload = body["payload"] as? [String: Any],
           let kind = payload["kind"] as? String,
           let method = payload["method"] as? String,
           let url = payload["url"] as? String {
            owner?.appendRequest(kind: kind, method: method, url: url)
        }
    }
}

struct ConsoleEntry: Identifiable {
    let id = UUID()
    let time = Date()
    let level: String
    let message: String
}

struct RequestEntry: Identifiable {
    let id = UUID()
    let time = Date()
    let kind: String
    let method: String
    let url: String
}

#if os(macOS)
struct WebViewContainer: NSViewRepresentable {
    @ObservedObject var store: WebViewStore

    func makeNSView(context: Context) -> WKWebView {
        store.webView
    }

    func updateNSView(_ nsView: WKWebView, context: Context) {
        // No-op.
    }
}
#else
struct WebViewContainer: UIViewRepresentable {
    @ObservedObject var store: WebViewStore

    func makeUIView(context: Context) -> WKWebView {
        store.webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        // No-op.
    }
}
#endif
