@file:OptIn(ExperimentalMaterial3Api::class)

package app.hoyotray.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import app.hoyotray.data.HoyoApi
import app.hoyotray.data.HoyoException
import app.hoyotray.data.Repo
import app.hoyotray.model.Game
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shows hoyolab.com in a WebView. Once the session cookies exist and actually work, saves them and closes.
 * The WebView keeps its own cookies, so logging in again is often automatic.
 */
class LoginActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HoyoTheme {
                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text("Log in to HoYoLAB") },
                        navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.Filled.Close, "Close") } },
                    )
                }) { pad ->
                    Column(Modifier.padding(pad).fillMaxSize()) {
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = object : WebViewClient() {
                                    // Ignore "open the HoYoLAB app" deep links (hoyolab://, intent://).
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                                        request.url.scheme !in setOf("http", "https")
                                }
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                // HoYoLAB's desktop site has a plain "Log in" button and sets the ltoken_v2 cookies we need
                                // (the mobile site opens on an app-download screen; account pages only log in to hoyoverse.com).
                                settings.userAgentString = HoyoApi.DESKTOP_UA
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.setSupportZoom(true)
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                loadUrl("https://www.hoyolab.com/home")
                            }
                        })
                    }
                }
            }
        }

        lifecycleScope.launch {
            while (true) {
                delay(1500)
                val cookie = listOf("https://www.hoyolab.com", "https://account.hoyolab.com")
                    .mapNotNull { CookieManager.getInstance().getCookie(it) }
                    .firstOrNull { "ltoken_v2=" in it && "ltuid_v2=" in it } ?: continue
                // Validate: an old expired token may still sit in the WebView's cookies.
                val works = try {
                    HoyoApi.getAccounts(cookie, Game.GENSHIN.biz); true
                } catch (e: HoyoException) {
                    !e.authExpired
                } catch (e: Exception) {
                    false
                }
                if (works) {
                    CookieManager.getInstance().flush()
                    Repo.login(cookie)
                    setResult(RESULT_OK)
                    finish()
                    break
                }
            }
        }
    }
}
