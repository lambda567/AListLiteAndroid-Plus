package com.leohao.android.alistlite;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import cn.hutool.extra.qrcode.QrCodeUtil;
import cn.hutool.http.Method;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.google.zxing.common.BitMatrix;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.kyleduo.switchbutton.SwitchButton;
import com.leohao.android.alistlite.model.Alist;
import com.leohao.android.alistlite.service.AlistService;
import com.leohao.android.alistlite.service.AlistTileService;
import com.leohao.android.alistlite.util.AppUtil;
import com.leohao.android.alistlite.util.ClipBoardHelper;
import com.leohao.android.alistlite.util.Constants;
import com.leohao.android.alistlite.util.MyHttpUtil;
import com.leohao.android.alistlite.window.PopupMenuWindow;
import com.yuyh.jsonviewer.library.JsonRecyclerView;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author LeoHao
 */
public class MainActivity extends AppCompatActivity {
    private static MainActivity instance;
    private static final String TAG = "MainActivity";
    /**
     * 广播定时发送定时器（用于实时更新服务磁贴状态）
     */
    private ScheduledExecutorService broadcastScheduler = null;
    private String currentAppVersion;
    private String currentAlistVersion;
    public ActionBar actionBar = null;
    public WebView webView = null;
    public TextView runningInfoTextView = null;
    public SwitchButton serviceSwitch = null;
    public String serverAddress = Constants.URL_ABOUT_BLANK;
    private Alist alistServer;
    public TextView appInfoTextView;
    private PopupMenuWindow popupMenuWindow;
    private final ClipBoardHelper clipBoardHelper = ClipBoardHelper.getInstance();
    /**
     * 文件上传回调变量
     */
    private ValueCallback<Uri[]> mFilePathCallback;
    private ValueCallback<Uri> mUploadMessage;
    private static final int FILE_CHOOSER_REQUEST_CODE = 100;
    private static final int REQUEST_CODE_SAF_EXTERNAL_STORAGE = 2001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        setContentView(R.layout.activity_main);
        //获取 AListServer 对象
        alistServer = Alist.getInstance();
        //初始化控件
        initWidgets();
        //焦点设置
        initFocusSettings();
        //权限检查
        checkPermissions();
        //检查外置存储SAF授权（针对非Root设备）
        checkAndRequestSAFPermission();
        //检查系统更新
        checkUpdates(null);
        //初始化广播发送定时器
        initBroadcastScheduler();
    }
    
    /**
     * 检查并请求SAF授权（针对非Root设备）
     */
    private void checkAndRequestSAFPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 检查设备是否Root
            boolean isRootEnabled = com.leohao.android.alistlite.util.SharedDataHelper.getBoolean(
                    Constants.KEY_ROOT_PERMISSION_ENABLED, false);
            
            // 如果已启用ROOT权限，不需要SAF
            if (isRootEnabled) {
                Log.i(TAG, "已启用ROOT权限，跳过SAF授权");
                return;
            }
            
            // 检查是否已有SAF授权
            List<android.content.UriPermission> permissions = getContentResolver().getPersistedUriPermissions();
            if (permissions.isEmpty()) {
                // 延迟3秒后提示SAF授权
                new android.os.Handler().postDelayed(() -> {
                    requestSAFPermission();
                }, 3000);
            } else {
                Log.i(TAG, "✅ 已有SAF授权: " + permissions.size() + "个目录");
            }
        }
    }
    
    /**
     * 请求SAF授权
     */
    private void requestSAFPermission() {
        new AlertDialog.Builder(this)
                .setTitle("外置存储访问授权")
                .setMessage("检测到外置存储设备（OTG/SD卡）。\n\n" +
                        "【方案1：SAF授权】（推荐非Root设备）\n" +
                        "在下一步中选择外置存储根目录并授权。\n" +
                        "注意：仅对授权的目录有效。\n\n" +
                        "【方案2：ROOT权限】（推荐Root设备）\n" +
                        "在【权限配置】中启用ROOT权限。\n\n" +
                        "是否开始SAF授权？")
                .setPositiveButton("开始授权", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    try {
                        startActivityForResult(intent, REQUEST_CODE_SAF_EXTERNAL_STORAGE);
                    } catch (Exception e) {
                        showToast("无法启动授权界面");
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }
    
    /**
     * 初始化广播发送定时器
     */
    private void initBroadcastScheduler() {
        //初始化广播定时发送定时器
        broadcastScheduler = Executors.newSingleThreadScheduledExecutor();
        //定时向 TileService 发送服务开启状态
        broadcastScheduler.scheduleAtFixedRate(() -> {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                //请求监听状态
                TileService.requestListeningState(this, new ComponentName(this, AlistTileService.class));
                //根据 AList 服务开启状态选择广播消息类型
                String actionName = (alistServer != null && alistServer.hasRunning()) ? AlistTileService.ACTION_TILE_ON : AlistTileService.ACTION_TILE_OFF;
                //更新磁贴开关状态
                Intent tileServiceIntent = new Intent(this, AlistTileService.class).setAction(actionName);
                LocalBroadcastManager.getInstance(this).sendBroadcast(tileServiceIntent);
            }
        }, 2, 1, TimeUnit.SECONDS);
    }

    /**
     * 初始化焦点设置
     */
    private void initFocusSettings() {
        //初始化焦点为主页按钮
        appInfoTextView.postDelayed(() -> {
            //初始时焦点设置为密码按钮
            appInfoTextView.requestFocus();
        }, 1000);
        //适配 TV 端操作，控件获取到焦点时显示边框
        List<View> views = AppUtil.getAllViews(this);
        views.addAll(AppUtil.getAllChildViews(popupMenuWindow.getContentView()));
        for (View view : views) {
            view.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    view.setBackgroundResource(R.drawable.background_border);
                } else {
                    view.setBackground(null);
                }
            });
        }
    }

    /**
     * 权限检查
     * Android 5.0-5.1（API 21-22）：权限在安装时自动授予，无需运行时请求
     * Android 6.0+（API 23+）：需要运行时请求权限
     */
    private void checkPermissions() {
        // Android 6.0 以下版本不需要运行时权限请求
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        
        // 首先检查是否已有存储权限（针对电视盒子精简系统）
        boolean hasStoragePermission = checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                == PackageManager.PERMISSION_GRANTED;
        
        if (!hasStoragePermission) {
            // 对于Android 6-10，尝试使用系统API直接请求（电视盒子备用方案）
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.w(TAG, "使用系统API请求存储权限（电视盒子兼容模式）");
                requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, 1001);
                // 继续使用XXPermissions作为备选
            }
        }
        
        // 根据Android版本申请不同的存储权限
        // Android 11+（API 30+）：MANAGE_EXTERNAL_STORAGE（所有文件管理权限）
        // Android 6-10（API 23-29）：READ_EXTERNAL_STORAGE + WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+
            XXPermissions.with(this)
                    .permission(Permission.POST_NOTIFICATIONS)
                    .permission(Permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .permission(Permission.MANAGE_EXTERNAL_STORAGE)
                    .request(new OnPermissionCallback() {
                        @Override
                        public void onGranted(@NonNull List<String> permissions, boolean allGranted) {
                            if (!allGranted) {
                                showToast("部分权限未授予，软件可能无法正常运行");
                            }
                        }

                        @Override
                        public void onDenied(@NonNull List<String> permissions, boolean doNotAskAgain) {
                            if (doNotAskAgain) {
                                showToast("请手动授予相关权限");
                            }
                        }
                    });
        } else {
            // Android 6-10
            XXPermissions.with(this)
                    .permission(Permission.POST_NOTIFICATIONS)
                    .permission(Permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .permission(Permission.Group.STORAGE)
                    .request(new OnPermissionCallback() {
                        @Override
                        public void onGranted(@NonNull List<String> permissions, boolean allGranted) {
                            if (!allGranted) {
                                showToast("部分权限未授予，软件可能无法正常运行");
                                // 针对电视盒子：即使XXPermissions失败，也检查系统权限
                                checkFallbackPermissions();
                            }
                        }

                        @Override
                        public void onDenied(@NonNull List<String> permissions, boolean doNotAskAgain) {
                            if (doNotAskAgain) {
                                showToast("请手动授予相关权限");
                            }
                            // 针对电视盒子：检查系统权限作为备选
                            checkFallbackPermissions();
                        }
                    });
        }
    }
    
    /**
     * 检查备用权限状态（针对电视盒子精简系统）
     */
    private void checkFallbackPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean hasWrite = checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                    == PackageManager.PERMISSION_GRANTED;
            boolean hasRead = checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) 
                    == PackageManager.PERMISSION_GRANTED;
            
            if (hasWrite && hasRead) {
                Log.i(TAG, "✅ 系统存储权限已授予（电视盒子兼容模式）");
                showToast("存储权限已授予");
            } else {
                Log.w(TAG, "⚠️ 系统存储权限未授予 - Write:" + hasWrite + " Read:" + hasRead);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Log.i(TAG, "✅ 系统API权限请求成功（电视盒子模式）");
                showToast("存储权限已授予");
            } else {
                Log.w(TAG, "⚠️ 系统API权限请求失败");
                showToast("存储权限被拒绝，请在设置中手动授予");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    private void readyToStartService() {
        //Service启动Intent
        Intent intent = new Intent(this, AlistService.class).setAction(AlistService.ACTION_STARTUP);
        //调用服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void readyToShutdownService() {
        //Service关闭Intent
        Intent intent = new Intent(this, AlistService.class).setAction(AlistService.ACTION_SHUTDOWN);
        //调用服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void initWidgets() {
        // 设置标题栏
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        actionBar = getSupportActionBar();
        Objects.requireNonNull(getSupportActionBar()).setDisplayShowTitleEnabled(false);
        serviceSwitch = findViewById(R.id.switchButton);
        appInfoTextView = findViewById(R.id.tv_app_info);
        runningInfoTextView = findViewById(R.id.tv_alist_status);
        webView = findViewById(R.id.webview_alist);
        //初始化菜单栏弹框
        popupMenuWindow = new PopupMenuWindow(this);
        popupMenuWindow.setOnDismissListener(() -> {
            backgroundAlpha(1.0f);
        });
        //初始化 webView 设定
        initWebview();
        //获取当前APP版本号
        currentAppVersion = getCurrentAppVersion();
        //获取基于的AList版本
        currentAlistVersion = getCurrentAlistVersion();
        //设置服务开关监听
        serviceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isChecked) {
                //准备停止AList服务
                readyToShutdownService();
                return;
            }
            try {
                //准备开启AList服务
                readyToStartService();
            } catch (Exception e) {
                Log.d(TAG, e.getLocalizedMessage());
            }
        });
        //默认开启服务
        serviceSwitch.setChecked(true);
    }

    private void initWebview() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.removeJavascriptInterface("searchBoxJavaBredge_");
        webView.setWebChromeClient(new WebChromeClient() {
            private View mCustomView;
            private CustomViewCallback mCustomViewCallback;
            final FrameLayout videoContainer = findViewById(R.id.video_container);

            /**
             * 处理Android 5.0及以上的文件选择
             */
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                // 确保之前的回调已处理
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                }
                mFilePathCallback = filePathCallback;
                // 创建文件选择意图
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    mFilePathCallback = null;
                    showToast("无法打开文件选择器");
                    return false;
                }
                return true;
            }

            /**
             * 处理Android 3.0到4.4的文件选择
             */
            public void openFileChooser(ValueCallback<Uri> uploadMsg) {
                mUploadMessage = uploadMsg;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                startActivityForResult(Intent.createChooser(intent, "选择文件"), FILE_CHOOSER_REQUEST_CODE);
            }

            /**
             * 处理早期Android版本的文件选择
             */
            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType) {
                mUploadMessage = uploadMsg;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType(acceptType);
                startActivityForResult(Intent.createChooser(intent, "选择文件"), FILE_CHOOSER_REQUEST_CODE);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                super.onShowCustomView(view, callback);
                if (mCustomView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                mCustomView = view;
                videoContainer.addView(mCustomView);
                mCustomViewCallback = callback;
                webView.setVisibility(View.GONE);
                //隐藏标题栏
                actionBar.hide();
                // 隐藏状态栏
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                //切换至横屏
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }

            @Override
            public void onHideCustomView() {
                webView.setVisibility(View.VISIBLE);
                if (mCustomView == null) {
                    return;
                }
                mCustomView.setVisibility(View.GONE);
                videoContainer.removeView(mCustomView);
                mCustomViewCallback.onCustomViewHidden();
                mCustomView = null;
                //显示标题栏
                actionBar.show();
                //显示状态栏
                getWindow().getDecorView().setSystemUiVisibility(0);
                //切换至竖屏
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                super.onHideCustomView();
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                //JS 注入，更新版本信息
                if (url.equals(Constants.URL_LOCAL_ABOUT_ALIST_LITE) || url.equals(Constants.URL_LOCAL_RELEASE_LOG)) {
                    String versionInfo = String.format(Constants.VERSION_INFO, currentAppVersion, currentAlistVersion);
                    String jsCode = "document.getElementById('text_version').innerHTML='" + versionInfo + "';";
                    webView.evaluateJavascript("javascript:(function(){" + jsCode + "})();", null);
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("blob:")) {
                    handleBlobUrl(view, url);
                    return true;
                }
                if (!url.startsWith("http") && !url.startsWith("file")) {
                    try {
                        openExternalUrl(url);
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                return super.shouldOverrideUrlLoading(view, url);
            }

            /**
             * 处理 Blob 类型 URL
             * @param view webView
             * @param blobUrl URL
             */
            private void handleBlobUrl(WebView view, String blobUrl) {
                // 使用JavaScript获取blob数据并转换为Base64
                String js = "javascript:(function() {" +
                        "fetch('" + blobUrl + "')" +
                        ".then(response => response.blob())" +
                        ".then(blob => {" +
                        "  const reader = new FileReader();" +
                        "  reader.onloadend = function() {" +
                        "    const base64data = reader.result;" +
                        "    window.android.onBlobDataReceived(base64data, blob.type);" +
                        "  };" +
                        "  reader.readAsDataURL(blob);" +
                        "})" +
                        ".catch(error => console.error('Error handling blob:', error));" +
                        "})()";
                // 在UI线程执行JavaScript
                view.post(() -> {
                    view.evaluateJavascript(js, null);
                });
            }

            @TargetApi(Build.VERSION_CODES.N)
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return shouldOverrideUrlLoading(view, request.getUrl().toString());
            }

            @Override
            public void onReceivedSslError(WebView webView, SslErrorHandler sslErrorHandler, SslError sslError) {
                sslErrorHandler.proceed();
            }
        });
        // 设置下载监听器以支持文件下载
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            //删除 blob 前缀，防止下载失败 TODO 备份文件下载失败 不是有效的 json 文件夹
            url = url.replaceFirst("blob:", "");
            // 使用系统下载管理器
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(mimetype);
            // 获取文件名
            String fileName = MyHttpUtil.guessFileName(contentDisposition);
            // 从文件名中提取扩展名（如果有）
            String extensionFromName = MimeTypeMap.getFileExtensionFromUrl(fileName);
            if (!TextUtils.isEmpty(extensionFromName)) {
                // 文件名已包含扩展名，直接使用
                fileName = fileName.replace("." + extensionFromName, "") + "." + extensionFromName;
            } else {
                // 否则使用MIME类型生成的扩展名
                fileName += MyHttpUtil.getFileExtension(mimetype);
            }
            request.setTitle(fileName);
            // 显示下载通知
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            // 设置下载路径
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            // 获取下载服务并开始下载
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(request);
            Toast.makeText(getApplicationContext(), "开始下载: " + fileName, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        // 处理SAF授权回调
        if (requestCode == REQUEST_CODE_SAF_EXTERNAL_STORAGE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri treeUri = data.getData();
                if (treeUri != null) {
                    getContentResolver().takePersistableUriPermission(treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    Log.i(TAG, "✅ SAF授权成功: " + treeUri);
                    showToast("外置存储已授权（限SAF支持的操作）", Toast.LENGTH_LONG);
                }
            }
            return;
        }
        
        // 处理文件选择回调
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            // 处理Android 5.0及以上的回调
            if (mFilePathCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    String dataString = data.getDataString();
                    if (dataString != null) {
                        results = new Uri[]{Uri.parse(dataString)};
                    }
                }
                mFilePathCallback.onReceiveValue(results);
                mFilePathCallback = null;
            }
            // 处理旧版本Android的回调
            if (mUploadMessage != null) {
                Uri result = (resultCode == Activity.RESULT_OK && data != null) ? data.getData() : null;
                mUploadMessage.onReceiveValue(result);
                mUploadMessage = null;
            }
        }
    }

    /**
     * 显示远程访问链接二维码
     */
    public void showQrCode(View view) {
        if (!alistServer.hasRunning()) {
            showToast("AList 服务未启动");
            return;
        }
        final ImageView imageView = new ImageView(MainActivity.this);
        //生成二维码
        imageView.setImageBitmap(bitMatrixToBitmap(QrCodeUtil.encode(serverAddress, 500, 500)));
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        //点击二维码图片使用浏览器打开
        imageView.setOnClickListener(v -> {
            openExternalUrl(serverAddress);
        });
        //创建布局
        FrameLayout layout = new FrameLayout(MainActivity.this);
        int padding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5, getResources().getDisplayMetrics());
        layout.setPadding(padding, padding, padding, padding);
        //添加二维码
        layout.addView(imageView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        AlertDialog.Builder dialog = new AlertDialog.Builder(MainActivity.this);
        AlertDialog alertDialog = dialog.create();
        alertDialog.setTitle("远程访问");
        alertDialog.setMessage(String.format("AList 服务地址：%s\r\n\r\n提示：请确保在同一网络环境内操作", serverAddress));
        alertDialog.setView(layout);
        alertDialog.show();
    }

    /**
     * 将BitMatrix对象转换为Bitmap对象
     */
    private Bitmap bitMatrixToBitmap(BitMatrix bitMatrix) {
        final int width = bitMatrix.getWidth();
        final int height = bitMatrix.getHeight();
        final int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    /**
     * 显示系统信息
     */
    public void showSystemInfo(View view) {
        webView.loadUrl(Constants.URL_LOCAL_ABOUT_ALIST_LITE);
    }

    /**
     * 设定管理员密码
     */
    public void setAdminPassword(View view) {
        final EditText editText = new EditText(MainActivity.this);
        //设置密码不可见
        editText.setTransformationMethod(PasswordTransformationMethod.getInstance());
        editText.setSingleLine();
        AlertDialog.Builder dialog = new AlertDialog.Builder(MainActivity.this);
        dialog.setTitle("设置管理员密码");
        dialog.setView(editText);
        dialog.setCancelable(true);
        dialog.setPositiveButton("确定", (dialog1, which) -> {
            try {
                //去除前后空格后的密码
                String pwd = editText.getText().toString().trim();
                if (!"".equals(pwd)) {
                    alistServer.setAdminPassword(pwd);
                    String adminUsername = alistServer.getAdminUser();
                    showToast(String.format("管理员密码已更新：%s | %s", adminUsername, pwd), Toast.LENGTH_LONG);
                } else {
                    showToast("管理员密码不能为空");
                }
            } catch (Exception e) {
                showToast("管理员密码设置失败");
                Log.e(TAG, "setAdminPassword: ", e);
            }
        });
        dialog.show();
    }

    /**
     * 跳转到AList主页面
     */
    public void jumpToHomepage(View view) {
        if (alistServer.hasRunning()) {
            webView.loadUrl(serverAddress);
        } else {
            showToast("AList 服务未启动");
        }
    }

    /**
     * 管理(查看/修改) AList 配置文件
     */
    public void manageConfigData(View view) {
        AlertDialog configDataDialog = new AlertDialog.Builder(this).create();
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.config_view, null);
        JsonRecyclerView jsonView = dialogView.findViewById(R.id.json_view_config);
        ImageButton editButton = dialogView.findViewById(R.id.btn_edit_config);
        EditText jsonEditText = dialogView.findViewById(R.id.edit_text_config);
        jsonView.setTextSize(14);
        //读取 AList 配置
        String dataPath = this.getExternalFilesDir("data").getAbsolutePath();
        String configPath = String.format("%s%s%s", dataPath, File.separator, Constants.ALIST_CONFIG_FILENAME);
        String configJsonData;
        File configFile = new File(configPath);
        try {
            //AList 配置数据
            configJsonData = FileUtils.readFileToString(configFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            configJsonData = Constants.ERROR_MSG_CONFIG_DATA_READ.replace("MSG", Objects.requireNonNull(e.getLocalizedMessage()));
            editButton.setVisibility(View.INVISIBLE);
        }
        //显示 AList 配置
        jsonView.bindJson(configJsonData);
        configDataDialog.setView(dialogView);
        configDataDialog.show();
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        //窗口大小设置必须在show()之后
        if (width < height) {
            configDataDialog.getWindow().setLayout(width - 50, height * 2 / 5);
        } else {
            configDataDialog.getWindow().setLayout(width * 5 / 6, height - 200);
        }
        //配置编辑按钮点击事件
        String finalConfigJsonData = configJsonData;
        AtomicBoolean isEditing = new AtomicBoolean(false);
        editButton.setOnClickListener(v -> {
            //若当前为编辑状态则保存配置，否则进入编辑模式
            if (isEditing.get()) {
                //json合法性验证
                boolean isJsonLegal = true;
                try {
                    JSONUtil.parseObj(jsonEditText.getText());
                } catch (Exception ignored) {
                    isJsonLegal = false;
                }
                if (!isJsonLegal) {
                    showToast("配置文件不是合法的JSON文件");
                    return;
                }
                try {
                    //持久化配置
                    FileUtils.write(configFile, jsonEditText.getText());
                    showToast("重启服务以应用新配置");
                } catch (IOException e) {
                    showToast(Constants.ERROR_MSG_CONFIG_DATA_WRITE);
                }
                isEditing.set(false);
                //显示jsonView
                jsonView.setVisibility(View.VISIBLE);
                jsonEditText.setVisibility(View.INVISIBLE);
                editButton.setImageResource(R.drawable.edit);
            } else {
                showToast("错误配置可能导致服务无法启动，请谨慎修改！");
                isEditing.set(true);
                jsonEditText.setText(finalConfigJsonData);
                //隐藏jsonView
                jsonView.setVisibility(View.INVISIBLE);
                jsonEditText.setVisibility(View.VISIBLE);
                editButton.setImageResource(R.drawable.save);
            }
        });
    }

    /**
     * 查看服务日志
     */
    public void showServiceLogs(View view) {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.service_logs_view, null);
        TextView textView = dialogView.findViewById(R.id.tv_service_logs);
        ScrollView scrollView = dialogView.findViewById(R.id.tv_logs_scroll_view);
        
        // 设置文本可选择和复制
        textView.setTextIsSelectable(true);
        
        //显示服务日志
        textView.setText(Alist.ALIST_LOGS);
        //滚动到底部最新日志
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        
        // 添加复制全部日志按钮
        dialogBuilder.setPositiveButton("复制全部", (dialog, which) -> {
            clipBoardHelper.copyText(Alist.ALIST_LOGS.toString());
            showToast("日志已复制到剪贴板");
        });
        
        // 添加清空日志按钮
        dialogBuilder.setNegativeButton("清空日志", (dialog, which) -> {
            Alist.ALIST_LOGS.setLength(0);
            Alist.ALIST_LOGS.append("------ 日志已清空 ------\r\n\r\n");
            showToast("日志已清空");
        });
        
        dialogBuilder.setNeutralButton("关闭", null);
        
        //日志实时刷新
        Thread refreshThread = new Thread(() -> {
            while (true) {
                runOnUiThread(() -> {
                    textView.setText(Alist.ALIST_LOGS);
                    //日志更新时，滚动到底部最新日志
                    if (!Alist.ALIST_LOGS.toString().equals(textView.getText().toString())) {
                        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                    }
                });
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Log.i(TAG, "fail to print logs: " + e.getLocalizedMessage());
                    break;
                }
            }
        });
        refreshThread.start();
        
        dialogBuilder.setView(dialogView);
        AlertDialog configDataDialog = dialogBuilder.create();
        configDataDialog.show();
        
        // 对话框关闭时停止刷新线程
        configDataDialog.setOnDismissListener(dialog -> {
            refreshThread.interrupt();
        });
        
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        //窗口大小设置必须在show()之后
        if (width < height) {
            configDataDialog.getWindow().setLayout(width - 50, height * 2 / 5);
        } else {
            configDataDialog.getWindow().setLayout(width * 5 / 6, height - 200);
        }
    }

    /**
     * 页面刷新
     *
     * @param view view
     */
    public void refreshWebPage(View view) {
        webView.reload();
    }

    /**
     * 检查版本更新
     *
     * @param view view
     */
    public void checkUpdates(View view) {
        new Thread(() -> {
            //获取最新release版本信息
            try {
                //捕捉HTTP请求异常
                String releaseInfo = null;
                try {
                    releaseInfo = MyHttpUtil.request(Constants.URL_RELEASE_LATEST, Method.GET);
                } catch (Throwable t) {
                    Looper.prepare();
                    showToast("无法获取更新: " + t.getLocalizedMessage());
                    Looper.loop();
                    Log.e(TAG, "checkUpdates: " + t.getLocalizedMessage());
                }
                JSONObject release = JSONUtil.parseObj(releaseInfo);
                if (!release.containsKey("tag_name")) {
                    Looper.prepare();
                    showToast("未发现新版本信息");
                    Looper.loop();
                    return;
                }
                //设备 CPU 支持的 ABI 名称
                String abiName = AppUtil.getAbiName();
                //若 ABI 名称不在支持的分包架构列表中，则下载完整的安装包
                if (!Constants.SUPPORTED_DOWNLOAD_ABI_NAMES.contains(abiName)) {
                    abiName = Constants.UNIVERSAL_ABI_NAME;
                }
                //最新版本号
                String latestVersion = release.getStr("tag_name").substring(1);
                //最新版本基于的 OpenList 版本
                String latestOnOpenListVersion = release.getStr("name").substring(15);
                //版本更新日志
                String updateJournal = String.format("\uD83D\uDD25 新版本基于 OpenList %s 构建\r\n\r\n%s", latestOnOpenListVersion, release.getStr("body"));
                //新版本APK下载地址（Github）
                String downloadLinkGitHub = (String) release.getByPath("assets[0].browser_download_url");
                //镜像加速地址
                String downloadLinkFast = String.format("%s/v%s/AListLite-v%s-%s-release.apk", Constants.QUICK_DOWNLOAD_ADDRESS, latestVersion, latestVersion, abiName);
                //发现新版本
                if (latestVersion.compareTo(currentAppVersion) > 0) {
                    Looper.prepare();
                    String dialogTitle = String.format("\uD83C\uDF89 AListLite %s 已发布", latestVersion);
                    //弹出更新下载确认
                    AlertDialog.Builder dialog = new AlertDialog.Builder(MainActivity.this);
                    dialog.setTitle(dialogTitle);
                    dialog.setMessage(updateJournal);
                    dialog.setCancelable(true);
                    dialog.setPositiveButton("镜像加速下载", (dialog1, which) -> {
                        //跳转到浏览器下载
                        openExternalUrl(downloadLinkFast);
                    });
                    dialog.setNeutralButton("GitHub官网下载", (dialog2, which) -> {
                        //跳转到浏览器下载
                        openExternalUrl(downloadLinkGitHub);
                    });
                    dialog.setNegativeButton("取消", (dialog3, which) -> {
                    });
                    dialog.show();
                    Looper.loop();
                } else {
                    if (view != null) {
                        Looper.prepare();
                        showToast(String.format("当前已是最新版本（v%s）", currentAppVersion));
                        Looper.loop();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "checkUpdates: " + e.getLocalizedMessage());
            }
        }).start();
    }

    /**
     * 获取当前APP版本
     */
    private String getCurrentAppVersion() {
        String versionName = "unknown";
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "getCurrentVersion: ", e);
        }
        return versionName;
    }

    /**
     * 获取当前AList版本
     */
    private String getCurrentAlistVersion() {
        return Constants.OPENLIST_VERSION;
    }

    public static MainActivity getInstance() {
        return instance;
    }

    void showToast(String msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    private void showToast(String msg, int duration) {
        Toast.makeText(getApplicationContext(), msg, duration).show();
    }

    @Override
    public void finish() {
        //关闭服务
        readyToShutdownService();
        super.finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        //自定义返回键功能，实现webView的后退以及退出时保持后台运行而不是关闭app
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.canGoBack() && alistServer != null && alistServer.hasRunning()) {
                webView.goBack();
            } else {
                moveTaskToBack(true);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 处理webView前进后退按钮点击事件
     */
    public void webViewGoBackOrForward(View view) {
        if (view.getId() == R.id.btn_webViewGoBack) {
            if (webView.canGoBack()) {
                webView.goBack();
            }
        }
        if (view.getId() == R.id.btn_webViewGoForward) {
            if (webView.canGoForward()) {
                webView.goForward();
            }
        }
    }

    /**
     * 复制 AList 服务地址到剪切板
     */
    public void copyAddressToClipboard(View view) {
        if (alistServer != null && alistServer.hasRunning()) {
            clipBoardHelper.copyText(this.serverAddress);
            showToast("AList 服务地址已复制");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 关闭定时任务调度器
        if (broadcastScheduler != null) {
            broadcastScheduler.shutdownNow();
        }
    }

    /**
     * 打开权限检查配置页面
     */
    public void startPermissionCheckActivity(View view) {
        Intent intent = new Intent(MainActivity.this, PermissionActivity.class);
        startActivity(intent);
    }

    /**
     * 显示菜单弹窗
     */
    public void showPopupMenu(View view) {
        if (isActivityRunning()) {
            popupMenuWindow.showAsDropDown(view, 0, 50);
            backgroundAlpha(0.6f);
        }
    }

    /**
     * 修改背景透明度，实现变暗效果
     */
    private void backgroundAlpha(float bgAlpha) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.alpha = bgAlpha;
        getWindow().setAttributes(lp);
        // 此方法用来设置浮动层，防止部分手机变暗无效
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    }

    /**
     * 打开外部链接
     *
     * @param url URL 链接
     */
    private void openExternalUrl(String url) {
        try {
            //跳转到浏览器下载
            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            startActivity(intent);
        } catch (Exception e) {
            showToast("无法打开此外部链接");
        }
    }

    /**
     * 检查 Activity 是否正在运行
     */
    private boolean isActivityRunning() {
        return !isFinishing() && !isDestroyed();
    }
}
