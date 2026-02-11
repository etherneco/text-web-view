package com.example.testwebapp

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONObject

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppRoot() {
    val viewModel: AppViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    var showSettings by remember { mutableStateOf(false) }
    var showInjectJs by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val isTablet = configuration.smallestScreenWidthDp >= 600
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val webViewWeight = when {
        isTablet && isLandscape -> 1f
        isTablet -> 0.55f
        isLandscape -> 0.48f
        else -> 0.6f
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            writeExport(context, uri, viewModel.buildExportText())
        }
    }

    LaunchedEffect(state.url, state.ua, webViewRef) {
        webViewRef?.let { webView ->
            if (state.ua.isNotBlank()) {
                webView.settings.userAgentString = state.ua
            }
            if (state.url.isNotBlank() && state.url != webView.url) {
                webView.loadUrl(state.url)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TestWebApp") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    Button(onClick = { exportLauncher.launch("testwebapp_logs.txt") }) {
                        Text("Export")
                    }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { webViewRef?.goBack() },
                    enabled = state.canGoBack
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                IconButton(
                    onClick = { webViewRef?.goForward() },
                    enabled = state.canGoForward
                ) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "Forward")
                }
                IconButton(onClick = { webViewRef?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { showInjectJs = true }) {
                    Text("Inject JS")
                }
            }
        }
    ) { padding ->
        if (isTablet && isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                WebViewPane(
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    onWebViewCreated = { webViewRef = it }
                )
                LogsPane(
                    state = state,
                    viewModel = viewModel,
                    activeTab = activeTab,
                    onTabChange = { activeTab = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                WebViewPane(
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(webViewWeight)
                        .fillMaxWidth(),
                    onWebViewCreated = { webViewRef = it }
                )
                LogsPane(
                    state = state,
                    viewModel = viewModel,
                    activeTab = activeTab,
                    onTabChange = { activeTab = it },
                    modifier = Modifier
                        .weight(1f - webViewWeight)
                        .fillMaxWidth()
                )
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            state = state,
            onDismiss = { showSettings = false },
            onUrlCommit = { value ->
                viewModel.setUrl(value)
                webViewRef?.loadUrl(value)
            },
            onUaCommit = { value ->
                viewModel.setUserAgent(value)
                webViewRef?.settings?.userAgentString = value
                webViewRef?.reload()
            }
        )
    }

    if (showInjectJs) {
        InjectJsDialog(
            state = state,
            onDismiss = { showInjectJs = false },
            onRun = { script ->
                viewModel.addJsHistory(script)
                webViewRef?.evaluateJavascript(script) { result ->
                    viewModel.logConsole(ConsoleLevel.LOG, "JS Result: $result")
                }
            }
        )
    }
}

@Composable
private fun WebViewPane(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
    onWebViewCreated: (WebView) -> Unit,
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = {
            buildWebView(context, viewModel).also { onWebViewCreated(it) }
        }
    )
}

@Composable
private fun LogsPane(
    state: UiState,
    viewModel: AppViewModel,
    activeTab: Int,
    onTabChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        TabRow(selectedTabIndex = activeTab) {
            Tab(
                text = { Text("Console") },
                selected = activeTab == 0,
                onClick = { onTabChange(0) }
            )
            Tab(
                text = { Text("Requests") },
                selected = activeTab == 1,
                onClick = { onTabChange(1) }
            )
        }
        when (activeTab) {
            0 -> ConsoleTab(state = state, viewModel = viewModel)
            else -> RequestsTab(state = state, viewModel = viewModel)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ConsoleTab(state: UiState, viewModel: AppViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = state.consoleFilters.contains(ConsoleLevel.LOG),
                onClick = { viewModel.toggleConsoleFilter(ConsoleLevel.LOG) },
                label = { Text("Log") }
            )
            FilterChip(
                selected = state.consoleFilters.contains(ConsoleLevel.WARN),
                onClick = { viewModel.toggleConsoleFilter(ConsoleLevel.WARN) },
                label = { Text("Warn") }
            )
            FilterChip(
                selected = state.consoleFilters.contains(ConsoleLevel.ERROR),
                onClick = { viewModel.toggleConsoleFilter(ConsoleLevel.ERROR) },
                label = { Text("Error") }
            )
        }
        OutlinedTextField(
            value = state.consoleSearch,
            onValueChange = { viewModel.updateConsoleSearch(it) },
            label = { Text("Search console") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        val filtered = state.consoleEntries.filter { entry ->
            state.consoleFilters.contains(entry.level) &&
                entry.message.contains(state.consoleSearch, ignoreCase = true)
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered) { entry ->
                LogRow(
                    label = entry.level.name,
                    message = entry.message,
                    time = formatTime(entry.timeMs)
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RequestsTab(state: UiState, viewModel: AppViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = state.requestFilters.contains(RequestKind.FETCH),
                onClick = { viewModel.toggleRequestFilter(RequestKind.FETCH) },
                label = { Text("Fetch") }
            )
            FilterChip(
                selected = state.requestFilters.contains(RequestKind.XHR),
                onClick = { viewModel.toggleRequestFilter(RequestKind.XHR) },
                label = { Text("XHR") }
            )
            FilterChip(
                selected = state.requestFilters.contains(RequestKind.RESOURCE),
                onClick = { viewModel.toggleRequestFilter(RequestKind.RESOURCE) },
                label = { Text("Resource") }
            )
            FilterChip(
                selected = state.requestFilters.contains(RequestKind.NAVIGATION),
                onClick = { viewModel.toggleRequestFilter(RequestKind.NAVIGATION) },
                label = { Text("Nav") }
            )
        }
        OutlinedTextField(
            value = state.requestSearch,
            onValueChange = { viewModel.updateRequestSearch(it) },
            label = { Text("Search requests") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = state.requestsOnlyErrors,
                onClick = { viewModel.toggleOnlyErrors() },
                label = { Text("Only errors") }
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        val filtered = state.requestEntries.filter { entry ->
            state.requestFilters.contains(entry.kind) &&
                entry.url.contains(state.requestSearch, ignoreCase = true) &&
                (!state.requestsOnlyErrors || (entry.status != null && entry.status >= 400))
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered) { entry ->
                val statusText = entry.status?.toString() ?: "-"
                LogRow(
                    label = "${entry.kind.name} ${entry.method} $statusText",
                    message = entry.url,
                    time = formatTime(entry.timeMs)
                )
            }
        }
    }
}

@Composable
private fun LogRow(label: String, message: String, time: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(end = 6.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
            )
        }
        Text(text = message, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsDialog(
    state: UiState,
    onDismiss: () -> Unit,
    onUrlCommit: (String) -> Unit,
    onUaCommit: (String) -> Unit,
) {
    var urlText by remember { mutableStateOf(state.url) }
    var uaText by remember { mutableStateOf(state.ua) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        confirmButton = {
            Button(onClick = {
                onUrlCommit(urlText)
                onUaCommit(uaText)
                onDismiss()
            }) {
                Text("Apply")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = normalizeQuotes(it) },
                    label = { Text("URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                HistoryRow(
                    items = state.urlHistory,
                    onSelect = { urlText = it }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uaText,
                    onValueChange = { uaText = normalizeQuotes(it) },
                    label = { Text("User-Agent") },
                    modifier = Modifier.fillMaxWidth()
                )
                HistoryRow(
                    items = state.uaHistory,
                    onSelect = { uaText = it }
                )
            }
        }
    )
}

@Composable
private fun InjectJsDialog(
    state: UiState,
    onDismiss: () -> Unit,
    onRun: (String) -> Unit,
) {
    val context = LocalContext.current
    var scriptText by remember { mutableStateOf(state.jsHistory.firstOrNull().orEmpty()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inject JavaScript") },
        confirmButton = {
            Button(onClick = {
                onRun(scriptText)
                onDismiss()
            }) {
                Text("Run")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = scriptText,
                    onValueChange = { scriptText = normalizeQuotes(it) },
                    label = { Text("Script") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        val clip = getClipboardText(context)
                        if (clip.isNotBlank()) {
                            scriptText = normalizeQuotes(clip)
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                    }
                    Text("History", style = MaterialTheme.typography.labelMedium)
                }
                HistoryRow(
                    items = state.jsHistory,
                    onSelect = { scriptText = it }
                )
            }
        }
    )
}

@Composable
private fun HistoryRow(items: List<String>, onSelect: (String) -> Unit) {
    if (items.isEmpty()) {
        Text("No history yet", style = MaterialTheme.typography.bodySmall)
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(6.dp)
    ) {
        items(items) { entry ->
            TextButton(onClick = { onSelect(entry) }, modifier = Modifier.fillMaxWidth()) {
                Text(entry, maxLines = 1)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun buildWebView(context: Context, viewModel: AppViewModel): WebView {
    val webView = WebView(context)
    webView.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    webView.settings.javaScriptEnabled = true
    webView.settings.domStorageEnabled = true
    webView.settings.databaseEnabled = true
    webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
    webView.settings.loadsImagesAutomatically = true
    webView.settings.javaScriptCanOpenWindowsAutomatically = true
    webView.settings.setSupportMultipleWindows(false)

    webView.addJavascriptInterface(JsBridge(viewModel), "AndroidBridge")

    webView.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            val level = when (consoleMessage.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> ConsoleLevel.ERROR
                ConsoleMessage.MessageLevel.WARNING -> ConsoleLevel.WARN
                else -> ConsoleLevel.LOG
            }
            viewModel.logConsole(level, consoleMessage.message())
            return true
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
            showAlert(context, message.orEmpty(), result)
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
            showConfirm(context, message.orEmpty(), result)
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: android.webkit.JsPromptResult?,
        ): Boolean {
            showPrompt(context, message.orEmpty(), defaultValue.orEmpty(), result)
            return true
        }
    }

    webView.webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            if (!url.isNullOrBlank()) {
                viewModel.logRequest(RequestKind.NAVIGATION, "GET", url, null)
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            view?.let {
                viewModel.setNavigationState(it.canGoBack(), it.canGoForward())
                injectBridge(it)
            }
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val url = request?.url?.toString().orEmpty()
            val method = request?.method ?: "GET"
            if (url.isNotBlank()) {
                viewModel.logRequest(RequestKind.RESOURCE, method, url, null)
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?,
        ) {
            val url = request?.url?.toString().orEmpty()
            val method = request?.method ?: "GET"
            val status = errorResponse?.statusCode
            if (url.isNotBlank()) {
                viewModel.logRequest(RequestKind.RESOURCE, method, url, status)
            }
        }
    }

    return webView
}

private fun injectBridge(webView: WebView) {
    webView.evaluateJavascript(JS_BRIDGE, null)
}

private class JsBridge(
    private val viewModel: AppViewModel,
) {
    @JavascriptInterface
    fun postMessage(payload: String) {
        try {
            val obj = JSONObject(payload)
            when (obj.optString("type")) {
                "console" -> {
                    val level = when (obj.optString("level")) {
                        "warn" -> ConsoleLevel.WARN
                        "error" -> ConsoleLevel.ERROR
                        else -> ConsoleLevel.LOG
                    }
                    viewModel.logConsole(level, obj.optString("message"))
                }
                "request" -> {
                    val kind = when (obj.optString("kind")) {
                        "fetch" -> RequestKind.FETCH
                        "xhr" -> RequestKind.XHR
                        "resource" -> RequestKind.RESOURCE
                        "navigation" -> RequestKind.NAVIGATION
                        else -> RequestKind.RESOURCE
                    }
                    val method = obj.optString("method", "GET")
                    val url = obj.optString("url")
                    val status = obj.optInt("status", 0).takeIf { it > 0 }
                    if (url.isNotBlank()) {
                        viewModel.logRequest(kind, method, url, status)
                    }
                }
            }
        } catch (_: Exception) {
            viewModel.logConsole(ConsoleLevel.ERROR, "Bridge parse error: $payload")
        }
    }
}

private const val JS_BRIDGE = """
(function() {
  if (window.__twappInstalled) return;
  window.__twappInstalled = true;
  function send(payload) {
    try { AndroidBridge.postMessage(JSON.stringify(payload)); } catch (e) {}
  }
  var originalLog = console.log;
  var originalWarn = console.warn;
  var originalError = console.error;
  console.log = function() {
    send({ type: 'console', level: 'log', message: Array.from(arguments).join(' ') });
    originalLog.apply(console, arguments);
  };
  console.warn = function() {
    send({ type: 'console', level: 'warn', message: Array.from(arguments).join(' ') });
    originalWarn.apply(console, arguments);
  };
  console.error = function() {
    send({ type: 'console', level: 'error', message: Array.from(arguments).join(' ') });
    originalError.apply(console, arguments);
  };
  window.addEventListener('error', function(event) {
    send({ type: 'console', level: 'error', message: event.message || 'JS error' });
  });
  window.addEventListener('unhandledrejection', function(event) {
    var msg = event && event.reason ? (event.reason.message || event.reason.toString()) : 'Unhandled rejection';
    send({ type: 'console', level: 'error', message: msg });
  });
  if (window.fetch) {
    var originalFetch = window.fetch;
    window.fetch = function() {
      var args = arguments;
      var method = (args[1] && args[1].method) || 'GET';
      var url = args[0] && args[0].toString ? args[0].toString() : '';
      return originalFetch.apply(this, args).then(function(resp) {
        send({ type: 'request', kind: 'fetch', method: method, url: resp.url || url, status: resp.status });
        return resp;
      }).catch(function(err) {
        send({ type: 'request', kind: 'fetch', method: method, url: url, status: 0 });
        throw err;
      });
    };
  }
  (function() {
    var XHR = window.XMLHttpRequest;
    if (!XHR) return;
    var originalOpen = XHR.prototype.open;
    var originalSend = XHR.prototype.send;
    XHR.prototype.open = function(method, url) {
      this.__tw_method = method;
      this.__tw_url = url;
      return originalOpen.apply(this, arguments);
    };
    XHR.prototype.send = function() {
      var xhr = this;
      var onDone = function() {
        send({
          type: 'request',
          kind: 'xhr',
          method: xhr.__tw_method || 'GET',
          url: xhr.responseURL || xhr.__tw_url || '',
          status: xhr.status
        });
      };
      this.addEventListener('loadend', onDone);
      return originalSend.apply(this, arguments);
    };
  })();
  if (window.PerformanceObserver) {
    try {
      var observer = new PerformanceObserver(function(list) {
        list.getEntries().forEach(function(entry) {
          send({ type: 'request', kind: 'resource', method: 'GET', url: entry.name, status: 0 });
        });
      });
      observer.observe({ type: 'resource', buffered: true });
    } catch (e) {}
  }
})();
"""

private fun showAlert(context: Context, message: String, result: android.webkit.JsResult?) {
    val activity = context as? Activity ?: return
    activity.runOnUiThread {
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton("OK") { _: DialogInterface, _: Int ->
                result?.confirm()
            }
            .setOnCancelListener { result?.cancel() }
            .show()
    }
}

private fun showConfirm(context: Context, message: String, result: android.webkit.JsResult?) {
    val activity = context as? Activity ?: return
    activity.runOnUiThread {
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton("OK") { _: DialogInterface, _: Int ->
                result?.confirm()
            }
            .setNegativeButton("Cancel") { _: DialogInterface, _: Int ->
                result?.cancel()
            }
            .setOnCancelListener { result?.cancel() }
            .show()
    }
}

private fun showPrompt(
    context: Context,
    message: String,
    defaultValue: String,
    result: android.webkit.JsPromptResult?,
) {
    val activity = context as? Activity ?: return
    activity.runOnUiThread {
        val input = android.widget.EditText(activity)
        input.setText(defaultValue)
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setView(input)
            .setPositiveButton("OK") { _: DialogInterface, _: Int ->
                result?.confirm(input.text.toString())
            }
            .setNegativeButton("Cancel") { _: DialogInterface, _: Int ->
                result?.cancel()
            }
            .setOnCancelListener { result?.cancel() }
            .show()
    }
}

private fun writeExport(context: Context, uri: Uri, content: String) {
    context.contentResolver.openOutputStream(uri)?.use { stream ->
        stream.write(content.toByteArray())
        stream.flush()
    }
}

private fun getClipboardText(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip: ClipData = clipboard?.primaryClip ?: return ""
    val item = clip.getItemAt(0) ?: return ""
    return item.text?.toString() ?: ""
}
