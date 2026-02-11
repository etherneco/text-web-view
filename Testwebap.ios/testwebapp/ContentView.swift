//
//  ContentView.swift
//  testwebapp
//
//  Created by Daniel Wojtak on 09/02/2026.
//

import SwiftUI
import UniformTypeIdentifiers
#if os(iOS)
import UIKit
#else
import AppKit
#endif

struct ContentView: View {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @StateObject private var store = WebViewStore()
    @AppStorage("webview_last_url") private var urlInput: String = "https://etherneco.co.uk"
    @AppStorage("webview_last_ua") private var userAgentInput: String = ContentView.desktopUA
    @State private var selectedTab: LogTab = .console
    @State private var consoleFilter: ConsoleFilter = .init()
    @State private var requestFilter: RequestFilter = .init()
    @State private var exportDocument: LogExportDocument?
    @State private var isExporting: Bool = false
    @State private var jsInput: String = "console.log('hello from injected JS')"
    @State private var isShowingSettings: Bool = false
    @State private var isShowingInject: Bool = false
    @State private var promptText: String = ""
    @AppStorage("webview_url_history") private var urlHistoryData: String = "[]"
    @AppStorage("webview_ua_history") private var uaHistoryData: String = "[]"
    @AppStorage("webview_js_history") private var jsHistoryData: String = "[]"

    private enum LogTab: String, CaseIterable, Identifiable {
        case console = "Console"
        case requests = "Requests"

        var id: String { rawValue }
    }

    var body: some View {
        NavigationStack {
            GeometryReader { geo in
                let isPortrait = geo.size.height >= geo.size.width
                if horizontalSizeClass == .compact {
                    VStack(spacing: 12) {
                        webViewPanel
                            .frame(maxWidth: .infinity)
                            .frame(height: geo.size.height * (isPortrait ? 0.62 : 0.48))
                            .ignoresSafeArea(.container, edges: .horizontal)
                        logsPanel
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(.vertical, 12)
                    .padding(.horizontal, 0)
                    .padding(.bottom, 56)
                } else if isPortrait {
                    VStack(spacing: 12) {
                        webViewPanel
                            .frame(height: geo.size.height * 0.62)
                            .ignoresSafeArea(.container, edges: .horizontal)
                        logsPanel
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(.vertical, 12)
                    .padding(.horizontal, 0)
                } else {
                    HStack(spacing: 12) {
                        webViewPanel
                            .frame(width: geo.size.width * 0.68)
                        logsPanel
                            .frame(width: max(320, geo.size.width * 0.32))
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(12)
                }
            }
            .navigationTitle("WebView Lab")
            .toolbar {
                ToolbarItemGroup(placement: .topBarLeading) {
                    if horizontalSizeClass != .compact {
                        Button("Settings") {
                            isShowingSettings = true
                        }
                    }
                }
                ToolbarItem(placement: .principal) {
                    if horizontalSizeClass != .compact {
                        VStack(spacing: 2) {
                            Text(shortened(urlInput))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            if !userAgentInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                Text(shortened(userAgentInput))
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if horizontalSizeClass == .compact {
                        Button {
                            store.goBack()
                        } label: {
                            Image(systemName: "chevron.backward")
                        }
                        .disabled(!store.canGoBack)
                        Button {
                            store.goForward()
                        } label: {
                            Image(systemName: "chevron.forward")
                        }
                        .disabled(!store.canGoForward)
                        Button {
                            store.reload()
                        } label: {
                            Image(systemName: "arrow.clockwise")
                        }
                    } else {
                        Button("Back") {
                            store.goBack()
                        }
                        .disabled(!store.canGoBack)
                        Button("Next") {
                            store.goForward()
                        }
                        .disabled(!store.canGoForward)
                        Button("Refresh") {
                            store.reload()
                        }
                    }
                    if store.isLoading {
                        ProgressView()
                            .controlSize(.small)
                    }
                    Button("Clear Logs") {
                        store.clearLogs()
                    }
                    Button("Export Logs") {
                        exportDocument = LogExportDocument(console: store.consoleEntries, requests: store.requestEntries)
                        isExporting = true
                    }
                }
            }
            header
        }
        .frame(minWidth: 900, minHeight: 600)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea(.keyboard, edges: .bottom)
        .onAppear {
            applyDefaultUserAgentForSizeClass()
            store.load(urlString: urlInput, userAgent: userAgentInput)
        }
        .onChange(of: horizontalSizeClass) { _, _ in
            applyDefaultUserAgentForSizeClass()
        }
        .safeAreaInset(edge: .bottom) {
            HStack {
                if horizontalSizeClass == .compact {
                    Button("Settings") {
                        isShowingSettings = true
                    }
                }
                Button("Inject JS") {
                    isShowingInject = true
                }
                Spacer()
            }
            .padding(12)
            .background(.ultraThinMaterial)
        }
        .sheet(isPresented: $isShowingSettings) {
            settingsSheet
        }
        .sheet(isPresented: $isShowingInject) {
            injectSheet
        }
        .alert(item: $store.activeAlert) { alert in
            if alert.isConfirm {
                return Alert(
                    title: Text("Confirm"),
                    message: Text(alert.message),
                    primaryButton: .default(Text("OK")) {
                        alert.completion(true)
                    },
                    secondaryButton: .cancel {
                        alert.completion(false)
                    }
                )
            }
            return Alert(
                title: Text("Alert"),
                message: Text(alert.message),
                dismissButton: .default(Text("OK")) {
                    alert.completion(true)
                }
            )
        }
        .sheet(item: $store.activePrompt, onDismiss: {
            if store.activePrompt != nil {
                store.activePrompt?.completion(nil)
                store.activePrompt = nil
            }
        }) { prompt in
            promptSheet(prompt)
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                if !store.lastLoadedURL.isEmpty {
                    Text("Loaded: \(store.lastLoadedURL)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .fileExporter(isPresented: $isExporting, document: exportDocument, contentType: .plainText, defaultFilename: "webview-logs") { _ in }
    }

    private var webViewPanel: some View {
        WebViewContainer(store: store)
            .overlay {
                if store.isLoading {
                    Color.black.opacity(0.05)
                }
            }
            .cornerRadius(8)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var logsPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            Picker("Logs", selection: $selectedTab) {
                ForEach(LogTab.allCases) { tab in
                    Text(tab.rawValue).tag(tab)
                }
            }
            .pickerStyle(.segmented)

            if selectedTab == .console {
                if horizontalSizeClass == .compact {
                    consoleFiltersCompact
                } else {
                    consoleFilters
                }
                consoleList
            } else {
                if horizontalSizeClass == .compact {
                    requestFiltersCompact
                } else {
                    requestFilters
                }
                requestList
            }

        }
        .frame(maxHeight: .infinity)
    }

    private var consoleFilters: some View {
        HStack(spacing: 8) {
            Toggle("Log", isOn: $consoleFilter.showLog)
            Toggle("Warn", isOn: $consoleFilter.showWarn)
            Toggle("Error", isOn: $consoleFilter.showError)
            Spacer()
            TextField("Search", text: $consoleFilter.searchText)
                .textFieldStyle(.roundedBorder)
                .frame(maxWidth: 180)
        }
        .toggleStyle(.switch)
        .font(.footnote)
    }

    private var consoleFiltersCompact: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                Toggle("Log", isOn: $consoleFilter.showLog)
                Toggle("Warn", isOn: $consoleFilter.showWarn)
                Toggle("Error", isOn: $consoleFilter.showError)
                Spacer()
            }
            TextField("Search", text: $consoleFilter.searchText)
                .textFieldStyle(.roundedBorder)
        }
        .toggleStyle(.switch)
        .font(.caption)
    }

    private var consoleList: some View {
        List(filteredConsoleEntries) { entry in
            VStack(alignment: .leading, spacing: 4) {
                Text("\(entry.level.uppercased())  \(entry.time.formatted(date: .omitted, time: .standard))")
                    .font(.caption)
                    .foregroundStyle(entry.level == "error" ? .red : .secondary)
                Text(entry.message)
                    .font(.callout)
                    .textSelection(.enabled)
            }
        }
    }

    private var requestFilters: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Toggle("Fetch", isOn: $requestFilter.showFetch)
                Toggle("XHR", isOn: $requestFilter.showXHR)
                Toggle("Resource", isOn: $requestFilter.showResource)
                Toggle("Navigation", isOn: $requestFilter.showNavigation)
                Spacer()
            }
            HStack(spacing: 8) {
                TextField("Search", text: $requestFilter.searchText)
                    .textFieldStyle(.roundedBorder)
                Toggle("Only Errors (4xx/5xx)", isOn: $requestFilter.onlyErrors)
            }
        }
        .toggleStyle(.switch)
        .font(.footnote)
    }

    private var requestFiltersCompact: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Toggle("Fetch", isOn: $requestFilter.showFetch)
                Toggle("XHR", isOn: $requestFilter.showXHR)
                Toggle("Res", isOn: $requestFilter.showResource)
                Toggle("Nav", isOn: $requestFilter.showNavigation)
                Spacer()
            }
            TextField("Search", text: $requestFilter.searchText)
                .textFieldStyle(.roundedBorder)
            Toggle("Only Errors (4xx/5xx)", isOn: $requestFilter.onlyErrors)
        }
        .toggleStyle(.switch)
        .font(.caption)
    }

    private var requestList: some View {
        List(filteredRequestEntries) { entry in
            VStack(alignment: .leading, spacing: 4) {
                Text("\(entry.kind.uppercased())  \(entry.method)  \(entry.time.formatted(date: .omitted, time: .standard))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(entry.url)
                    .font(.callout)
                    .textSelection(.enabled)
            }
        }
    }

    private var filteredConsoleEntries: [ConsoleEntry] {
        store.consoleEntries.filter { entry in
            switch entry.level {
            case "log":
                guard consoleFilter.showLog else { return false }
            case "warn":
                guard consoleFilter.showWarn else { return false }
            case "error":
                guard consoleFilter.showError else { return false }
            default:
                break
            }
            if consoleFilter.searchText.isEmpty { return true }
            return entry.message.localizedCaseInsensitiveContains(consoleFilter.searchText)
        }
    }

    private var filteredRequestEntries: [RequestEntry] {
        store.requestEntries.filter { entry in
            switch entry.kind {
            case "fetch":
                guard requestFilter.showFetch else { return false }
            case "xhr":
                guard requestFilter.showXHR else { return false }
            case "resource":
                guard requestFilter.showResource else { return false }
            case "navigate", "navigationAction", "navigationResponse":
                guard requestFilter.showNavigation else { return false }
            default:
                break
            }

            if requestFilter.onlyErrors {
                let urlString = entry.url
                if !urlString.contains(" 4") && !urlString.contains(" 5") {
                    return false
                }
            }

            if requestFilter.searchText.isEmpty { return true }
            return entry.url.localizedCaseInsensitiveContains(requestFilter.searchText)
                || entry.method.localizedCaseInsensitiveContains(requestFilter.searchText)
        }
    }

    private var settingsSheet: some View {
        NavigationStack {
            Form {
                Section("URL") {
                    TextField("https://etherneco.co.uk", text: $urlInput)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.asciiCapable)
                        .textContentType(.URL)
                        .onChange(of: urlInput) { _, newValue in
                            urlInput = normalizeQuotes(newValue)
                        }
                    HStack {
                        Button("Paste") {
                            let text = readClipboardText()
                            if !text.isEmpty {
                                urlInput = normalizeQuotes(text)
                            }
                        }
                        Spacer()
                    }
                }
                if !urlHistory.isEmpty {
                    Section("Recent URLs") {
                        ForEach(urlHistory, id: \.self) { item in
                            Button(item) {
                                urlInput = item
                            }
                        }
                    }
                }
                Section("User Agent") {
                    TextField("User Agent (optional)", text: $userAgentInput, axis: .vertical)
                        .lineLimit(1...3)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.asciiCapable)
                        .onChange(of: userAgentInput) { _, newValue in
                            userAgentInput = normalizeQuotes(newValue)
                        }
                    if horizontalSizeClass == .compact {
                        Button("Use iPhone UA") {
                            userAgentInput = ContentView.iPhoneUA
                        }
                    } else {
                        Button("Use Desktop UA") {
                            userAgentInput = ContentView.desktopUA
                        }
                    }
                    HStack {
                        Button("Paste") {
                            let text = readClipboardText()
                            if !text.isEmpty {
                                userAgentInput = normalizeQuotes(text)
                            }
                        }
                        Spacer()
                    }
                }
                if !uaHistory.isEmpty {
                    Section("Recent User Agents") {
                        ForEach(uaHistory, id: \.self) { item in
                            Button(item) {
                                userAgentInput = item
                            }
                        }
                    }
                }
            }
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Close") { isShowingSettings = false }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Load") {
                        if userAgentInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            applyDefaultUserAgentForSizeClass()
                        }
                        store.load(urlString: urlInput, userAgent: userAgentInput)
                        addURLHistory(urlInput)
                        addUAHistory(userAgentInput)
                        isShowingSettings = false
                    }
                }
            }
        }
    }

    private var injectSheet: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text("JavaScript")
                    .font(.headline)
                TextEditor(text: $jsInput)
                    .font(.system(.body, design: .monospaced))
                    .frame(minHeight: 220)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onChange(of: jsInput) { _, newValue in
                        jsInput = normalizeQuotes(newValue)
                    }
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color.secondary.opacity(0.2))
                    )
                HStack(spacing: 8) {
                    Button("Paste") {
                        jsInput = normalizeQuotes(readClipboardText())
                    }
                    Spacer()
                    Button("Inject") {
                        store.injectJavaScript(jsInput)
                        addJSHistory(jsInput)
                        isShowingInject = false
                    }
                }
                if !jsHistory.isEmpty {
                    Divider()
                    Text("Recent Scripts")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    ForEach(jsHistory, id: \.self) { item in
                        Button(shortened(item)) {
                            jsInput = item
                        }
                    }
                }
            }
            .padding(16)
            .navigationTitle("Inject JS")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Close") { isShowingInject = false }
                }
            }
        }
    }

    private func promptSheet(_ prompt: JSPrompt) -> some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text(prompt.message)
                    .font(.headline)
                TextField("Value", text: $promptText)
                    .textFieldStyle(.roundedBorder)
                HStack {
                    Button("Cancel") {
                        prompt.completion(nil)
                        store.activePrompt = nil
                    }
                    Spacer()
                    Button("OK") {
                        prompt.completion(promptText)
                        store.activePrompt = nil
                    }
                }
            }
            .padding(16)
            .navigationTitle("Prompt")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Close") {
                        prompt.completion(nil)
                        store.activePrompt = nil
                    }
                }
            }
            .onAppear {
                promptText = prompt.defaultText ?? ""
            }
        }
    }

    private func readClipboardText() -> String {
#if os(iOS)
        return UIPasteboard.general.string ?? ""
#else
        return NSPasteboard.general.string(forType: .string) ?? ""
#endif
    }

    private var urlHistory: [String] {
        decodeHistory(from: urlHistoryData)
    }

    private var uaHistory: [String] {
        decodeHistory(from: uaHistoryData)
    }

    private var jsHistory: [String] {
        decodeHistory(from: jsHistoryData)
    }

    private func addURLHistory(_ value: String) {
        urlHistoryData = encodeHistory(value, existing: urlHistoryData, max: 8)
    }

    private func addUAHistory(_ value: String) {
        uaHistoryData = encodeHistory(value, existing: uaHistoryData, max: 8)
    }

    private func addJSHistory(_ value: String) {
        jsHistoryData = encodeHistory(value, existing: jsHistoryData, max: 12)
    }

    private func encodeHistory(_ value: String, existing: String, max: Int) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return existing }
        var items = decodeHistory(from: existing)
        items.removeAll { $0 == trimmed }
        items.insert(trimmed, at: 0)
        if items.count > max {
            items = Array(items.prefix(max))
        }
        if let data = try? JSONEncoder().encode(items),
           let json = String(data: data, encoding: .utf8) {
            return json
        }
        return existing
    }

    private func decodeHistory(from json: String) -> [String] {
        guard let data = json.data(using: .utf8),
              let items = try? JSONDecoder().decode([String].self, from: data) else {
            return []
        }
        return items
    }

    private func shortened(_ text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.count <= 42 { return trimmed }
        let prefix = trimmed.prefix(36)
        return "\(prefix)..."
    }

    private func normalizeQuotes(_ text: String) -> String {
        text
            .replacingOccurrences(of: "“", with: "\"")
            .replacingOccurrences(of: "”", with: "\"")
            .replacingOccurrences(of: "„", with: "\"")
            .replacingOccurrences(of: "’", with: "'")
            .replacingOccurrences(of: "‘", with: "'")
            .replacingOccurrences(of: "‚", with: "'")
    }

    private func applyDefaultUserAgentForSizeClass() {
        let trimmed = userAgentInput.trimmingCharacters(in: .whitespacesAndNewlines)
        if horizontalSizeClass == .compact {
            if trimmed.isEmpty || trimmed == ContentView.desktopUA || !trimmed.localizedCaseInsensitiveContains("mobile") {
                userAgentInput = ContentView.iPhoneUA
            }
        } else {
            if trimmed.isEmpty || trimmed == ContentView.iPhoneUA {
                userAgentInput = ContentView.desktopUA
            }
        }
    }

    private static let desktopUA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    private static let iPhoneUA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
}

#Preview {
    ContentView()
}

private struct ConsoleFilter {
    var showLog: Bool = true
    var showWarn: Bool = true
    var showError: Bool = true
    var searchText: String = ""
}

private struct RequestFilter {
    var showFetch: Bool = true
    var showXHR: Bool = true
    var showResource: Bool = true
    var showNavigation: Bool = true
    var onlyErrors: Bool = false
    var searchText: String = ""
}

struct LogExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.plainText] }

    var text: String

    init(console: [ConsoleEntry], requests: [RequestEntry]) {
        var lines: [String] = []
        lines.append("== Console ==")
        for entry in console {
            let time = entry.time.formatted(date: .omitted, time: .standard)
            lines.append("[\(time)] \(entry.level.uppercased()): \(entry.message)")
        }
        lines.append("")
        lines.append("== Requests ==")
        for entry in requests {
            let time = entry.time.formatted(date: .omitted, time: .standard)
            lines.append("[\(time)] \(entry.kind) \(entry.method) \(entry.url)")
        }
        text = lines.joined(separator: "\n")
    }

    init(configuration: ReadConfiguration) throws {
        text = ""
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        let data = Data(text.utf8)
        return FileWrapper(regularFileWithContents: data)
    }
}
