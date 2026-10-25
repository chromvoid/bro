import SafariServices
import SwiftUI
import WebKit

/**
 The whole interface is the web client from the configured origin. The shell adds what a page
 cannot do on its own: the system microphone prompt, saving an export, a screen that stays on
 during a call, and a way back when the network is gone. Other pages open in Safari, never in
 this web view.
 */
struct ShellView: View {
    let connection: AssistantConnection
    let openConnection: () -> Void
    @State private var offline = false
    @State private var attempt = 0
    @State private var problem: String?
    /// The page's theme from theme.set, which it names once its launch screen is gone; nil until then.
    @State private var pageDark: Bool?
    /// The page's first view is ready: it said so with theme.set (or it took too long).
    @State private var ready = false
    @State private var splash = SplashPhase.thinking
    @State private var launchedAt = Date()
    @Environment(\.accessibilityReduceMotion) private var still

    var body: some View {
        ZStack {
            backdrop.ignoresSafeArea()
            // Edge to edge: the page pads itself with env(safe-area-inset-*).
            WebView(connection: connection, attempt: attempt, offline: $offline, problem: $problem,
                    pageDark: $pageDark, ready: $ready, openConnection: openConnection)
                .ignoresSafeArea(.container, edges: [.bottom, .horizontal])
            if splash != .gone && !offline {
                // Bro's launch screen until the first view is ready (shell/README.md «Сплеш»).
                SplashOverlay(phase: splash).transition(.opacity)
            }
            if offline {
                VStack(spacing: 16) {
                    Text("Нет связи с сервером. Проверьте интернет и попробуйте снова.")
                        .font(.title3)
                        .multilineTextAlignment(.center)
                    Button("Повторить") {
                        offline = false
                        attempt += 1
                    }
                    .buttonStyle(.borderedProminent)
                    .foregroundStyle(KaiColors.onAccent)
                }
                .padding(32)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(KaiColors.background)
            }
        }
        .task {
            try? await Task.sleep(for: ShellView.splashLimit)
            ready = true
        }
        .onChange(of: ready) { isReady in if isReady { Task { await smile() } } }
        .alert("Не удалось выполнить действие", isPresented: Binding(
            get: { problem != nil }, set: { if !$0 { problem = nil } })) {
                Button("Закрыть", role: .cancel) { problem = nil }
            } message: { Text(problem ?? "") }
    }

    /// A page slower than this shows its own launch screen instead of an endless one.
    static let splashLimit = Duration.seconds(15)

    /// The first view is ready: once the dots have been seen, they flow into ")", Bro smiles and the
    /// launch screen fades. With reduced motion the smile simply stands.
    @MainActor private func smile() async {
        guard splash == .thinking else { return }
        let thinking = still ? 0 : max(0, SplashOverlay.thinking - Date().timeIntervalSince(launchedAt))
        try? await Task.sleep(for: .seconds(thinking))
        if !still {
            withAnimation(.timingCurve(0.4, 0, 0.2, 1, duration: SplashGeometry.mergeSeconds)) { splash = .merging }
            try? await Task.sleep(for: .seconds(SplashGeometry.mergeSeconds))
        }
        splash = .smiled
        try? await Task.sleep(for: .seconds(SplashOverlay.smile))
        withAnimation(.easeOut(duration: SplashOverlay.fade)) { splash = .gone }
    }

    /// Above the web view, behind the status bar: the launch colour until the page names its theme.
    @ViewBuilder private var backdrop: some View {
        if offline {
            KaiColors.background
        } else if let pageDark {
            KaiColors.background.environment(\.colorScheme, pageDark ? .dark : .light)
        } else {
            KaiColors.splashBackground
        }
    }
}

/**
 The system text size for the page: Dynamic Type's body size against its usual 17 points, set as the
 CSS variable --system-text-scale before the page paints. WKWebView applies no text size of its own;
 the page follows this halfway, up to 125% (web/src/styles.css).
 */
enum PageText {
    static func scale(bodyPointSize: CGFloat) -> Double { Double(bodyPointSize) / 17 }

    static var current: Double { scale(bodyPointSize: UIFont.preferredFont(forTextStyle: .body).pointSize) }

    /// Only a formatted number reaches the page's script.
    static func source(_ scale: Double) -> String {
        String(format: "document.documentElement.style.setProperty('--system-text-scale', '%.3f')", scale)
    }

    static func script(_ scale: Double) -> WKUserScript {
        WKUserScript(source: source(scale), injectionTime: .atDocumentStart, forMainFrameOnly: true, in: .page)
    }
}

private struct WebView: UIViewRepresentable {
    let connection: AssistantConnection
    var origin: ShellOrigin { connection.origin }
    let attempt: Int
    @Binding var offline: Bool
    @Binding var problem: String?
    @Binding var pageDark: Bool?
    @Binding var ready: Bool
    let openConnection: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(connection: connection, offline: $offline, problem: $problem, pageDark: $pageDark,
                    ready: $ready, openConnection: openConnection)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = .all
        // Page world only; the handler checks the frame, origin and page of every message.
        configuration.userContentController.addScriptMessageHandler(
            context.coordinator, contentWorld: .page, name: BridgeProtocol.name)
        // The system text size before the page paints; the coordinator follows its changes.
        configuration.userContentController.addUserScript(PageText.script(PageText.current))
        let web = WKWebView(frame: .zero, configuration: configuration)
        web.backgroundColor = KaiColors.backgroundUI
        web.scrollView.backgroundColor = KaiColors.backgroundUI
        web.navigationDelegate = context.coordinator
        web.uiDelegate = context.coordinator
        web.allowsBackForwardNavigationGestures = true
        web.scrollView.contentInsetAdjustmentBehavior = .never
        context.coordinator.web = web
        NativeNotifications.shared.openConnection = openConnection
        NativeNotifications.shared.eventSink = { [weak web] event, payload in
            guard NativeNotifications.shared.accepts(connectionID: connection.id),
                  let web, origin.ownsPage(web.url), let data = try? JSONSerialization.data(withJSONObject: [
                "event": event, "payload": payload]), let json = String(data: data, encoding: .utf8) else { return }
            // Arguments cross WebKit's serialized value boundary, never JavaScript interpolation.
            web.callAsyncJavaScript("window.__assistantShellEvent?.(eventJSON)",
                                    arguments: ["eventJSON": json], in: nil, in: .page, completionHandler: { _ in })
        }
        AmbientAudio.shared.eventSink = NativeNotifications.shared.eventSink
        context.coordinator.observeMicrophone(web)
        context.coordinator.observeTextSize()
        web.load(URLRequest(url: origin.url.appendingPathComponent("assistant")))
        context.coordinator.attempt = attempt
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {
        guard context.coordinator.attempt != attempt else { return }
        context.coordinator.attempt = attempt
        if web.url == nil { web.load(URLRequest(url: origin.url.appendingPathComponent("assistant"))) } else { web.reload() }
    }

    static func dismantleUIView(_ web: WKWebView, coordinator: Coordinator) {
        coordinator.alive = false
        web.stopLoading()
        web.navigationDelegate = nil
        web.uiDelegate = nil
        if NativeNotifications.shared.accepts(connectionID: coordinator.connection.id) {
            NativeNotifications.shared.eventSink = nil
            NativeNotifications.shared.openConnection = nil
        }
        coordinator.microphoneObservation = nil
        if let observer = coordinator.textSizeObserver { NotificationCenter.default.removeObserver(observer) }
        if AmbientAudio.shared.accepts(connectionID: coordinator.connection.id) {
            AmbientAudio.shared.eventSink = nil
            AmbientAudio.shared.setWebMicrophoneInUse(false)
        }
        UIApplication.shared.isIdleTimerDisabled = false
        web.configuration.userContentController.removeScriptMessageHandler(forName: BridgeProtocol.name, contentWorld: .page)
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandlerWithReply,
        WKDownloadDelegate
    {
        let origin: ShellOrigin
        let connection: AssistantConnection
        @Binding var offline: Bool
        @Binding var problem: String?
        @Binding var pageDark: Bool?
        @Binding var ready: Bool
        var attempt = 0
        var alive = true
        weak var web: WKWebView?
        let openConnection: () -> Void
        private var downloads: [ObjectIdentifier: URL] = [:]
        var microphoneObservation: NSKeyValueObservation?
        var textSizeObserver: NSObjectProtocol?

        /// A new Dynamic Type size reaches the open page and every page loaded after it.
        func observeTextSize() {
            textSizeObserver = NotificationCenter.default.addObserver(
                forName: UIContentSizeCategory.didChangeNotification, object: nil, queue: .main
            ) { [weak self] _ in
                Task { @MainActor [weak self] in
                    guard let self, alive, let web else { return }
                    let scale = PageText.current
                    // The text size is the web view's only user script.
                    web.configuration.userContentController.removeAllUserScripts()
                    web.configuration.userContentController.addUserScript(PageText.script(scale))
                    web.evaluateJavaScript(PageText.source(scale), completionHandler: nil)
                }
            }
        }

        func observeMicrophone(_ web: WKWebView) {
            microphoneObservation = web.observe(\.microphoneCaptureState, options: [.initial, .new]) { [weak self] web, _ in
                let busy = web.microphoneCaptureState != .none
                Task { @MainActor [weak self] in
                    guard let self, alive, AmbientAudio.shared.accepts(connectionID: connection.id) else { return }
                    AmbientAudio.shared.setWebMicrophoneInUse(busy)
                }
            }
        }

        init(connection: AssistantConnection, offline: Binding<Bool>, problem: Binding<String?>,
             pageDark: Binding<Bool?>, ready: Binding<Bool>, openConnection: @escaping () -> Void) {
            self.connection = connection
            self.origin = connection.origin
            _offline = offline
            _problem = problem
            _pageDark = pageDark
            _ready = ready
            self.openConnection = openConnection
        }

        // MARK: Bridge

        func userContentController(
            _ controller: WKUserContentController, didReceive message: WKScriptMessage,
            replyHandler: @escaping (Any?, String?) -> Void
        ) {
            let frame = message.frameInfo
            guard frame.isMainFrame,
                  origin.owns(scheme: frame.securityOrigin.protocol, host: frame.securityOrigin.host,
                              port: frame.securityOrigin.port),
                  origin.ownsPage(frame.request.url),
                  let request = BridgeProtocol.parse(message.body)
            else { return replyHandler(nil, "denied") }
            guard alive, NativeNotifications.shared.accepts(connectionID: connection.id) else { return replyHandler(nil, "denied") }
            if request.type == "shell.capabilities" {
                replyHandler(BridgeProtocol.reply(request.id, result: BridgeProtocol.capabilities(
                    glasses: false, pushAvailable: NativeNotifications.shared.pushAvailable)), nil)
                NativeNotifications.shared.ready()
                return
            }
            if request.type.hasPrefix("ambient.") {
                Task { @MainActor [weak self] in
                    guard let self, alive, AmbientAudio.shared.accepts(connectionID: connection.id) else { return replyHandler(nil, "denied") }
                    do {
                        let result = try await AmbientAudio.shared.handle(request, connectionID: connection.id)
                        guard alive, AmbientAudio.shared.accepts(connectionID: connection.id),
                              origin.ownsPage(web?.url) else { return replyHandler(nil, "denied") }
                        replyHandler(BridgeProtocol.reply(request.id, result: result), nil)
                    } catch {
                        guard alive, AmbientAudio.shared.accepts(connectionID: connection.id) else { return replyHandler(nil, "denied") }
                        replyHandler(BridgeProtocol.failure(request.id, (error as? NativeError)?.code ?? "unavailable"), nil)
                    }
                }
                return
            }
            if request.type.hasPrefix("location.") {
                Task { @MainActor [weak self] in
                    guard let self, alive else { return replyHandler(nil, "denied") }
                    do {
                        let result: [String: Any]
                        if request.type.hasPrefix("location.background.") {
                            result = try await LocationCollector.shared.handle(request, origin: origin)
                        } else {
                            result = try await LocationBridge.shared.handle(request)
                        }
                        guard alive, origin.ownsPage(web?.url) else { return replyHandler(nil, "denied") }
                        replyHandler(BridgeProtocol.reply(request.id, result: result), nil)
                    } catch {
                        guard alive else { return replyHandler(nil, "denied") }
                        replyHandler(BridgeProtocol.failure(request.id, (error as? NativeError)?.code ?? "unavailable"), nil)
                    }
                }
                return
            }
            if request.type.hasPrefix("notifications.") || request.type == "connection.open" {
                Task { @MainActor [weak self] in
                    guard let self, alive, NativeNotifications.shared.accepts(connectionID: connection.id) else { return replyHandler(nil, "denied") }
                    do {
                        let result = try await NativeNotifications.shared.handle(request, connectionID: connection.id)
                        guard alive, NativeNotifications.shared.accepts(connectionID: connection.id),
                              origin.ownsPage(web?.url) else { return replyHandler(nil, "denied") }
                        replyHandler(BridgeProtocol.reply(request.id, result: result), nil)
                    } catch {
                        guard alive, NativeNotifications.shared.accepts(connectionID: connection.id) else { return replyHandler(nil, "denied") }
                        replyHandler(BridgeProtocol.failure(request.id, (error as? NativeError)?.code ?? "unavailable"), nil)
                    }
                }
                return
            }
            // Auto-lock would suspend the app during a call, and the call with it.
            if let on = BridgeProtocol.awake(request) { UIApplication.shared.isIdleTimerDisabled = on }
            if let dark = BridgeProtocol.darkTheme(request) {
                pageDark = dark
                // The page names its theme once its first view is ready: Bro smiles (ShellView.smile).
                ready = true
            }
            replyHandler(BridgeProtocol.handle(request), nil)
        }

        // MARK: Navigation

        func webView(
            _ web: WKWebView, decidePolicyFor action: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = action.request.url else { return decisionHandler(.cancel) }
            if origin.owns(url) {
                if action.shouldPerformDownload { return decisionHandler(.download) }
                // WebKit may discover the export download only from Content-Disposition.
                if action.request.httpMethod == "GET" && url.path == "/v1/nutrition/export" {
                    return decisionHandler(.allow)
                }
            }
            if origin.ownsPage(url) { return decisionHandler(.allow) }
            decisionHandler(.cancel)
            openOutside(url, from: web)
        }

        /// A new page starts with the idle timer back on: a reload never leaves the screen on.
        func webView(_ web: WKWebView, didCommit navigation: WKNavigation!) {
            UIApplication.shared.isIdleTimerDisabled = false
            offline = false
            // A loaded page starts with its launch screen again, until it names its theme.
            pageDark = nil
        }

        func webView(_ web: WKWebView, didFinish navigation: WKNavigation!) { NativeNotifications.shared.ready() }

        func webView(_ web: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            if (error as NSError).code != NSURLErrorCancelled { offline = true }
        }

        func webView(_ web: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            if (error as NSError).code != NSURLErrorCancelled { offline = true }
        }

        func webViewWebContentProcessDidTerminate(_ web: WKWebView) {
            UIApplication.shared.isIdleTimerDisabled = false
            offline = true
        }

        func webView(_ web: WKWebView, decidePolicyFor response: WKNavigationResponse,
                     decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
            guard let url = response.response.url, origin.owns(url) else { return decisionHandler(.cancel) }
            let attachment = (response.response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Disposition")?
                .lowercased().hasPrefix("attachment") == true
            if attachment || !response.canShowMIMEType { return decisionHandler(.download) }
            if let http = response.response as? HTTPURLResponse, http.statusCode >= 500, response.isForMainFrame {
                offline = true
            }
            decisionHandler(.allow)
        }

        /// target=_blank: shown outside the assistant, never as a second web view.
        func webView(
            _ web: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
            for action: WKNavigationAction, windowFeatures: WKWindowFeatures
        ) -> WKWebView? {
            if let url = action.request.url {
                if origin.ownsPage(url) { web.load(action.request) } else { openOutside(url, from: web) }
            }
            return nil
        }

        private func openOutside(_ url: URL, from web: WKWebView) {
            guard let scheme = url.scheme?.lowercased() else { return }
            if scheme == "https" || scheme == "http" {
                presenter(of: web)?.present(SFSafariViewController(url: url), animated: true)
            } else if scheme == "mailto" || scheme == "tel" {
                UIApplication.shared.open(url)
            }
        }

        // MARK: Microphone

        /// Only the microphone, and only for the assistant's own pages in the main frame.
        func webView(
            _ web: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin,
            initiatedByFrame frame: WKFrameInfo, type: WKMediaCaptureType,
            decisionHandler: @escaping (WKPermissionDecision) -> Void
        ) {
            let own = self.origin.owns(scheme: origin.protocol, host: origin.host, port: origin.port)
            let ownFrame = self.origin.owns(scheme: frame.securityOrigin.protocol,
                                            host: frame.securityOrigin.host, port: frame.securityOrigin.port)
            let allowed = alive && NativeNotifications.shared.accepts(connectionID: connection.id)
                            && own && ownFrame && frame.isMainFrame && self.origin.ownsPage(frame.request.url)
                            && type == .microphone
            // Release native audio before WebKit starts a call or dictation. Merely observing
            // the active capture afterwards would deactivate a session WebKit already owns.
            if allowed { AmbientAudio.shared.stop(reason: "microphone_busy") }
            decisionHandler(allowed ? .grant : .deny)
        }

        // MARK: Downloads (the food diary export)

        func webView(_ web: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
            download.delegate = self
        }

        func webView(_ web: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
            download.delegate = self
        }

        func download(
            _ download: WKDownload, decideDestinationUsing response: URLResponse, suggestedFilename: String,
            completionHandler: @escaping (URL?) -> Void
        ) {
            let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            do { try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true) }
            catch {
                problem = "Не удалось сохранить экспорт. Попробуйте снова."
                return completionHandler(nil)
            }
            let target = folder.appendingPathComponent((suggestedFilename as NSString).lastPathComponent)
            downloads[ObjectIdentifier(download)] = target
            completionHandler(target)
        }

        func downloadDidFinish(_ download: WKDownload) {
            guard let file = downloads.removeValue(forKey: ObjectIdentifier(download)),
                  let web = download.webView, let presenter = presenter(of: web)
            else { return }
            let share = UIActivityViewController(activityItems: [file], applicationActivities: nil)
            share.popoverPresentationController?.sourceView = web
            share.completionWithItemsHandler = { _, _, _, _ in
                try? FileManager.default.removeItem(at: file.deletingLastPathComponent())
            }
            presenter.present(share, animated: true)
        }

        func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
            if let file = downloads.removeValue(forKey: ObjectIdentifier(download)) {
                try? FileManager.default.removeItem(at: file.deletingLastPathComponent())
            }
            problem = "Не удалось скачать экспорт. Проверьте связь и попробуйте снова."
        }

        private func presenter(of web: WKWebView) -> UIViewController? {
            var top = web.window?.rootViewController
            while let presented = top?.presentedViewController { top = presented }
            return top
        }
    }
}
