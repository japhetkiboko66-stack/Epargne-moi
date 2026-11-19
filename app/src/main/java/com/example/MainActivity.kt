package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.ui.theme.MyApplicationTheme
import java.io.File

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )
    setContent {
      MyApplicationTheme(darkTheme = true, dynamicColor = false) {
        SavingsWebAppScreen(onFinishActivity = { finish() })
      }
    }
  }
}

private class SavingsAndroidBridge(
  private val onSavePdf: (String, ByteArray) -> Unit,
  private val onPrintPage: () -> Unit,
) {
  @JavascriptInterface
  fun savePdfBase64(filename: String, base64Data: String) {
    runCatching {
      val bytes = Base64.decode(base64Data, Base64.DEFAULT)
      onSavePdf(filename, bytes)
    }
  }

  @JavascriptInterface
  fun printPage() {
    onPrintPage()
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SavingsWebAppScreen(onFinishActivity: () -> Unit) {
  val context = LocalContext.current
  val density = LocalDensity.current
  val layoutDir = LocalLayoutDirection.current
  val safeInsets = WindowInsets.safeDrawing

  val topInsetDp = with(density) { safeInsets.getTop(density).toDp().value }
  val bottomInsetDp = with(density) { safeInsets.getBottom(density).toDp().value }

  var webViewRef by remember { mutableStateOf<WebView?>(null) }
  var pageLoaded by remember { mutableStateOf(false) }

  var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
  var pendingWebPermission by remember { mutableStateOf<PermissionRequest?>(null) }
  var pendingCameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
  var pendingPdfBytes by remember { mutableStateOf<ByteArray?>(null) }

  val galleryLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      val cb = pendingFileCallback
      pendingFileCallback = null
      cb?.onReceiveValue(if (uri != null) arrayOf(uri) else emptyArray())
    }

  val takePictureLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
      val cb = pendingFileCallback
      val uri = pendingCameraPhotoUri
      pendingFileCallback = null
      pendingCameraPhotoUri = null
      cb?.onReceiveValue(if (success && uri != null) arrayOf(uri) else emptyArray())
    }

  val cameraPermForWebRtc =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      val req = pendingWebPermission
      pendingWebPermission = null
      if (req != null) {
        if (granted) req.grant(req.resources) else req.deny()
      }
    }

  val cameraPermForCapture =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      val uri = pendingCameraPhotoUri
      if (granted && uri != null) {
        takePictureLauncher.launch(uri)
      } else {
        pendingCameraPhotoUri = null
        galleryLauncher.launch(
          PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
      }
    }

  val createPdfLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri
      ->
      val bytes = pendingPdfBytes
      pendingPdfBytes = null
      if (uri != null && bytes != null) {
        runCatching {
          context.contentResolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
        }
      }
    }

  LaunchedEffect(pageLoaded, topInsetDp, bottomInsetDp) {
    if (pageLoaded) {
      webViewRef?.evaluateJavascript(
        "if(window.setSafeAreaInsets){window.setSafeAreaInsets($topInsetDp, $bottomInsetDp);}",
        null,
      )
    }
  }

  BackHandler(enabled = true) {
    val wv = webViewRef
    if (wv == null) {
      onFinishActivity()
      return@BackHandler
    }
    wv.evaluateJavascript("window.handleAndroidBack ? window.handleAndroidBack() : false") { res ->
      if (res != "true") {
        onFinishActivity()
      }
    }
  }

  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF050B1F))) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { ctx ->
        WebView(ctx).apply {
          layoutParams =
            ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
          setBackgroundColor(0xFF050B1F.toInt())
          overScrollMode = WebView.OVER_SCROLL_NEVER

          settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
          }

          addJavascriptInterface(
            SavingsAndroidBridge(
              onSavePdf = { filename, bytes ->
                post {
                  pendingPdfBytes = bytes
                  createPdfLauncher.launch(filename)
                }
              },
              onPrintPage = {
                post {
                  val printManager =
                    ctx.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return@post
                  val adapter = createPrintDocumentAdapter("Vela_Epargne_Rapport")
                  printManager.print(
                    "Vela Epargne Rapport",
                    adapter,
                    PrintAttributes.Builder().build(),
                  )
                }
              },
            ),
            "AndroidBridge",
          )

          webViewClient =
            object : WebViewClient() {
              override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageLoaded = true
                view?.evaluateJavascript(
                  "if(window.setSafeAreaInsets){window.setSafeAreaInsets($topInsetDp, $bottomInsetDp);}",
                  null,
                )
              }
            }

          webChromeClient =
            object : WebChromeClient() {
              override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val needsCam =
                  request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                if (!needsCam) {
                  request.deny()
                  return
                }
                val hasCam =
                  ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                if (hasCam) {
                  request.grant(request.resources)
                } else {
                  pendingWebPermission = request
                  cameraPermForWebRtc.launch(Manifest.permission.CAMERA)
                }
              }

              override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
              ): Boolean {
                pendingFileCallback?.onReceiveValue(emptyArray())
                pendingFileCallback = filePathCallback

                val isCapture = fileChooserParams?.isCaptureEnabled == true
                if (isCapture) {
                  val photoFile = File(ctx.cacheDir, "cam_${System.currentTimeMillis()}.jpg")
                  val uri =
                    runCatching {
                        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", photoFile)
                      }
                      .getOrNull()
                  if (uri != null) {
                    pendingCameraPhotoUri = uri
                    val hasCam =
                      ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    if (hasCam) {
                      takePictureLauncher.launch(uri)
                    } else {
                      cameraPermForCapture.launch(Manifest.permission.CAMERA)
                    }
                    return true
                  }
                }

                galleryLauncher.launch(
                  PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
                return true
              }
            }

          loadUrl("file:///android_asset/epargne.html")
          webViewRef = this
        }
      },
    )
  }
}
