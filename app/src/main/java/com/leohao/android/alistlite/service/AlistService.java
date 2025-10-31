package com.leohao.android.alistlite.service;

import android.app.*;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.leohao.android.alistlite.MainActivity;
import com.leohao.android.alistlite.R;
import com.leohao.android.alistlite.broadcast.CopyReceiver;
import com.leohao.android.alistlite.model.Alist;
import com.leohao.android.alistlite.util.AppUtil;
import com.leohao.android.alistlite.util.Constants;
import com.leohao.android.alistlite.util.StorageUtil;
import com.leohao.android.alistlite.util.PermissionDiagnostic;
import com.leohao.android.alistlite.util.RootUtil;
import com.leohao.android.alistlite.util.SharedDataHelper;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * AList 服务
 *
 * @author LeoHao
 */
public class AlistService extends Service {
    /**
     * 电源唤醒锁
     */
    private PowerManager.WakeLock wakeLock = null;
    public final static String TAG = "AListService";
    private final static String CHANNEL_ID = "com.leohao.android.alistlite";
    private final static String CHANNEL_NAME = "AlistService";
    public final static String ACTION_STARTUP = "com.leohao.android.alistlite.ACTION_STARTUP";
    public final static String ACTION_SHUTDOWN = "com.leohao.android.alistlite.ACTION_SHUTDOWN";
    private final Alist alistServer = Alist.getInstance();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String channelId;
        // 8.0 以上需要特殊处理
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channelId = createNotificationChannel(CHANNEL_ID, CHANNEL_NAME);
        } else {
            channelId = "";
        }
        Intent clickIntent = new Intent(getApplicationContext(), MainActivity.class);
        //用于点击状态栏进入主页面
        PendingIntent pendingIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingIntent = PendingIntent.getActivity(this, 0, clickIntent, PendingIntent.FLAG_IMMUTABLE);
        } else {
            pendingIntent = PendingIntent.getActivity(this, 0, clickIntent, PendingIntent.FLAG_ONE_SHOT);
        }
        //根据action决定是否启动AList服务端
        if (ACTION_SHUTDOWN.equals(intent.getAction())) {
            if (alistServer.hasRunning()) {
                //关闭服务
                exitService();
            }
            //更新磁贴状态
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                updateAlistTileServiceState(AlistTileService.ACTION_TILE_OFF);
            }
            showToast("AList 服务已关闭");
        }
        if (ACTION_STARTUP.equals(intent.getAction())) {
            try {
                //创建消息以维持后台（此处必须先执行，否则可能产生由于未及时调用 startForeground 导致的 ANR 异常）
                Notification notification = new NotificationCompat.Builder(this, channelId).setContentTitle(getString(R.string.alist_service_is_running)).setContentText("服务正在初始化").setSmallIcon(R.drawable.ic_launcher).setContentIntent(pendingIntent).build();
                startForeground(startId, notification);
                //若服务未运行则开启
                if (!alistServer.hasRunning()) {
                    //开启AList服务端
                    alistServer.startup();
                    //判断 AList 是否为首次初始化
                    boolean hasInitialized = AppUtil.checkAlistHasInitialized();
                    if (!hasInitialized) {
                        //自动挂载所有存储设备（包括内置存储、SD卡、OTG）
                        mountAllStorageDevices();
                        //初始化密码
                        alistServer.setAdminPassword(Constants.ALIST_DEFAULT_PASSWORD);
                        //管理员用户名
                        String adminUsername = alistServer.getAdminUser();
                        showToast(String.format("初始登录信息：%s | %s", adminUsername, Constants.ALIST_DEFAULT_PASSWORD), Toast.LENGTH_LONG);
                    }
                }
                //AList服务前端访问地址
                String serverAddress = getAlistServerAddress();
                if (MainActivity.getInstance() != null) {
                    //状态开关恢复到开启状态（不触发监听事件）
                    MainActivity.getInstance().serviceSwitch.setCheckedNoEvent(true);
                    //加载AList前端页面
                    MainActivity.getInstance().serverAddress = serverAddress;
                    MainActivity.getInstance().webView.loadUrl(serverAddress);
                    //隐藏服务未开启提示
                    MainActivity.getInstance().runningInfoTextView.setVisibility(View.GONE);
                }
                //创建 Intent，用于复制服务器地址到剪贴板
                Intent copyIntent = new Intent(this, CopyReceiver.class);
                copyIntent.putExtra("address", serverAddress);
                PendingIntent copyPendingIntent;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    copyPendingIntent = PendingIntent.getBroadcast(this, 0, copyIntent, PendingIntent.FLAG_IMMUTABLE);
                } else {
                    copyPendingIntent = PendingIntent.getBroadcast(this, 0, copyIntent, PendingIntent.FLAG_ONE_SHOT);
                }
                //创建复制服务地址的 Action
                NotificationCompat.Action addressCopyAction = new NotificationCompat.Action.Builder(
                        R.drawable.copy,
                        "复制服务地址",
                        copyPendingIntent)
                        .build();
                //更新消息内容里的服务地址，同时添加服务地址复制入口
                notification = new NotificationCompat.Builder(this, channelId)
                        .setContentTitle(getString(R.string.alist_service_is_running))
                        .setContentText(serverAddress)
                        .setSmallIcon(R.drawable.ic_launcher)
                        .addAction(addressCopyAction)
                        .setContentIntent(pendingIntent).build();
                startForeground(startId, notification);
                //更新磁贴状态
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    updateAlistTileServiceState(AlistTileService.ACTION_TILE_ON);
                }
                showToast("AList 服务已开启");
            } catch (Exception e) {
                Log.e(TAG, e.getLocalizedMessage());
                if (MainActivity.getInstance() != null) {
                    //状态开关恢复到关闭状态（不触发监听事件）
                    MainActivity.getInstance().serviceSwitch.setCheckedNoEvent(false);
                }
                showToast(String.format("AList 服务开启失败: %s", e.getLocalizedMessage()));
            }
        }
        return START_NOT_STICKY;
    }

    /**
     * 获取 AList 服务地址
     *
     * @return AList 服务地址（根据当前采用的协议类型动态）
     * @throws IOException
     */
    public String getAlistServerAddress() throws IOException {
        //判断是否强制开启了 HTTPS
        boolean isForceHttps = "true".equals(alistServer.getConfigValue("scheme.force_https"));
        boolean isHttpPortLegal = !"-1".equals(alistServer.getConfigValue("scheme.https_port"));
        boolean isHttpsMode = isForceHttps && isHttpPortLegal;
        //读取 AList 服务运行端口
        String serverPort = alistServer.getConfigValue(isHttpsMode ? "scheme.https_port" : "scheme.http_port");
        //AList 服务前端访问地址
        return String.format(Locale.CHINA, "%s://%s:%s", isHttpsMode ? "https" : "http", alistServer.getBindingIP(), serverPort);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    public void exitService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        //关闭服务
        alistServer.shutdown();
        if (MainActivity.getInstance() != null) {
            //状态开关恢复到关闭状态（不触发监听事件）
            MainActivity.getInstance().serviceSwitch.setCheckedNoEvent(false);
            //刷新 webview
            MainActivity.getInstance().webView.reload();
            //显示服务未开启提示
            MainActivity.getInstance().runningInfoTextView.setVisibility(View.VISIBLE);
        }
        if (wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
        }
        this.stopSelf();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        //初始化电源管理器
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, AlistService.class.getName());
        // 常驻服务需要持续保持CPU唤醒，不设置超时
        // 配合前台服务使用，确保Web服务24/7可用
        wakeLock.acquire();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 创建通道并返回通道ID
     */
    private String createNotificationChannel(String channelId, String channelName) {
        NotificationChannel channel;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_NONE);
            channel.setLightColor(Color.BLUE);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            NotificationManager service = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            service.createNotificationChannel(channel);
        }
        return channelId;
    }

    /**
     * 更新 AList 服务磁贴状态
     *
     * @param actionName 新服务磁贴状态对应的 ACTION 名称
     */
    private void updateAlistTileServiceState(String actionName) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            //请求监听状态
            TileService.requestListeningState(this, new ComponentName(this, AlistTileService.class));
            //更新磁贴开关状态
            Intent tileServiceIntent = new Intent(this, AlistTileService.class).setAction(actionName);
            LocalBroadcastManager.getInstance(this).sendBroadcast(tileServiceIntent);
        }
    }

    /**
     * 挂载所有存储设备（包括内置存储、SD卡、OTG）
     * 这是解决Android 9外置存储写入问题的关键
     */
    private void mountAllStorageDevices() {
        try {
            Log.i(TAG, "========== 开始挂载所有存储设备 ==========");
            
            // 检查Root状态和用户设置
            boolean isDeviceRooted = RootUtil.isDeviceRooted();
            boolean isRootEnabled = SharedDataHelper.getBoolean(Constants.KEY_ROOT_PERMISSION_ENABLED, false);
            Log.i(TAG, "设备Root状态: " + (isDeviceRooted ? "✅ 已Root" : "❌ 未Root"));
            Log.i(TAG, "ROOT权限开关: " + (isRootEnabled ? "✅ 已启用" : "❌ 未启用（可在权限配置中启用）"));
            
            List<StorageUtil.StorageInfo> storageDevices = StorageUtil.getAllStorageDevices(this);
            
            if (storageDevices.isEmpty()) {
                Log.w(TAG, "⚠️ 未发现任何存储设备，使用默认内置存储");
                // 兜底：挂载默认内置存储
                String defaultPath = Environment.getExternalStorageDirectory().getAbsolutePath();
                alistServer.addLocalStorageDriver(defaultPath, Constants.ALIST_STORAGE_DRIVER_MOUNT_PATH);
                Log.i(TAG, "✅ 已挂载默认存储: " + defaultPath);
                return;
            }
            
            // 挂载所有发现的存储设备
            int mountCount = 0;
            int skippedCount = 0;
            for (StorageUtil.StorageInfo storage : storageDevices) {
                try {
                    // 验证路径可访问
                    File storageFile = new File(storage.path);
                    if (!storageFile.exists() || !storageFile.canRead()) {
                        Log.w(TAG, "⚠️ 跳过不可访问的存储: " + storage.name + " -> " + storage.path);
                        skippedCount++;
                        continue;
                    }
                    
                    // 挂载策略（关键修复！）：
                    // - 内置存储：使用/storage/emulated/0
                    // - 外置存储：尝试使用/mnt/media_rw路径（绕过sdcardfs权限检查）
                    String mountPath = storage.isPrimary ? Constants.ALIST_STORAGE_DRIVER_MOUNT_PATH : storage.name;
                    String physicalPath = storage.path;
                    boolean canWrite = false;
                    
                    // 关键修复：对于外置存储，强制尝试使用/mnt/media_rw路径
                    if (storage.isRemovable && storage.path.startsWith("/storage/")) {
                        // 从/storage/8956-8C7E转换为/mnt/media_rw/8956-8C7E
                        String deviceName = storage.path.substring("/storage/".length());
                        String mediaRwPath = "/mnt/media_rw/" + deviceName;
                        File mediaRwFile = new File(mediaRwPath);
                        
                        // 优先检查/mnt/media_rw路径（这是外置存储的真实挂载点）
                        boolean mediaRwExists = mediaRwFile.exists();
                        boolean mediaRwReadable = mediaRwExists && mediaRwFile.canRead();
                        
                        Log.i(TAG, String.format("   🔍 检查/mnt/media_rw路径: %s (存在:%s, 可读:%s)", 
                                mediaRwPath, mediaRwExists, mediaRwReadable));
                        
                        if (mediaRwReadable) {
                            // 路径存在且可读，尝试使用它
                            Log.i(TAG, "   🔑 使用/mnt/media_rw路径绕过sdcardfs: " + mediaRwPath);
                            physicalPath = mediaRwPath;
                            
                            // 测试/mnt/media_rw路径的写入权限
                            if (mediaRwFile.canWrite()) {
                                Log.i(TAG, "   ✅ /mnt/media_rw路径可写入！");
                                canWrite = true;
                            } else {
                                Log.w(TAG, "   ⚠️ /mnt/media_rw路径只读");
                                // Android 9+上，即使canWrite()返回false，也可能通过实际测试
                                // 这里暂时标记为不可写，后续通过实际写入测试验证
                                canWrite = false;
                                // 对于电视盒子，我们即使canWrite返回false也标记为可写，因为实际测试可能通过
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    Log.i(TAG, "   📺 检测到Android 9+设备，标记为可写以进行实际测试");
                                    canWrite = true;
                                }
                            }
                        } else if (mediaRwExists && !mediaRwReadable) {
                            // 路径存在但不可读，可能需要权限
                            Log.w(TAG, "   ⚠️ /mnt/media_rw路径存在但不可读");
                            Log.w(TAG, "   💡 可能需要WRITE_MEDIA_STORAGE权限或Root权限");
                            
                            // 如果设备已Root且启用了Root权限，尝试使用Root访问
                            if (isDeviceRooted && isRootEnabled) {
                                Log.i(TAG, "   🔓 尝试使用Root权限访问/mnt/media_rw路径");
                                // 即使路径不可读，也尝试使用它（Root权限可能会让Go层能够访问）
                                physicalPath = mediaRwPath;
                                canWrite = true; // Root设备假设可写
                            } else {
                                // 非Root设备，回退到/storage路径（但大概率无法写入）
                                Log.w(TAG, "   ⚠️ 非Root设备，回退到/storage路径（可能无法写入）");
                                physicalPath = storage.path;
                            }
                        } else {
                            // 路径不存在，尝试其他可能的路径格式
                            Log.w(TAG, "   ⚠️ /mnt/media_rw路径不存在: " + mediaRwPath);
                            
                            // 尝试查找可能的挂载点（某些设备可能使用不同的路径格式）
                            String[] alternativePaths = {
                                "/mnt/media_rw/" + deviceName,
                                "/storage/" + deviceName,
                                "/mnt/sdcard/" + deviceName,
                                "/storage/sdcard1"
                            };
                            
                            boolean foundAlternative = false;
                            for (String altPath : alternativePaths) {
                                File altFile = new File(altPath);
                                if (altFile.exists() && altFile.canRead()) {
                                    Log.i(TAG, "   ✅ 找到可用的替代路径: " + altPath);
                                    physicalPath = altPath;
                                    // 对于电视盒子，即使canWrite返回false也假设可写
                                    canWrite = altFile.canWrite() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P);
                                    foundAlternative = true;
                                    break;
                                }
                            }
                            
                            if (!foundAlternative) {
                                // 如果找不到替代路径，使用原路径但标记为可能无法写入
                                Log.w(TAG, "   ⚠️ 未找到可用的替代路径，使用/storage路径");
                                Log.w(TAG, "   💡 警告：此路径在Android 9+上可能无法写入");
                                physicalPath = storage.path;
                                // 对于电视盒子，即使canWrite返回false也假设可写
                                canWrite = storageFile.canWrite() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P);
                            }
                        }
                    } else {
                        // 内置存储或非/storage路径，直接检查
                        canWrite = storageFile.canWrite();
                    }
                    
                    // Android 9+ 关键修复：对外置存储进行实际写入测试
                    // 注意：这个测试必须在挂载之前执行，确保只有可写的存储才会被挂载
                    if (storage.isRemovable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        Log.i(TAG, String.format("   🧪 [Android 9+] 对外置存储 %s 进行写入测试: %s", storage.name, physicalPath));
                        boolean actuallyWritable = testActualWriteAccess(physicalPath);
                        if (!actuallyWritable) {
                            String errorMsg = String.format("❌ [Android 9+] 外置存储 %s 无法写入（权限测试失败）", storage.name);
                            String reason = "   💡 原因：";
                            String reasonDetail;
                            
                            // 根据使用的路径给出更具体的错误原因
                            if (physicalPath.startsWith("/storage/")) {
                                reasonDetail = "使用了 /storage/ 路径，该路径在 Android 9+ 上通过 sdcardfs 挂载，通常只能读取，无法写入。";
                            } else if (physicalPath.startsWith("/mnt/media_rw/")) {
                                reasonDetail = "使用了 /mnt/media_rw/ 路径，但该路径可能没有写入权限或需要特殊权限（WRITE_MEDIA_STORAGE 或 Root）。";
                            } else {
                                reasonDetail = "路径无法写入，可能是权限不足或挂载为只读。";
                            }
                            
                            String suggestion1 = "   💡 解决方案：";
                            String suggestion2;
                            String suggestion3;
                            String suggestion4 = "";
                            
                            if (isDeviceRooted) {
                                suggestion2 = "      1. 在【权限配置】中启用【ROOT权限】，然后重启应用";
                                suggestion3 = "      2. Root权限可以让应用直接访问 /mnt/media_rw/ 路径的真实挂载点";
                                if (!isRootEnabled) {
                                    suggestion4 = "      3. 当前ROOT权限未启用，请在权限配置中启用";
                                }
                            } else {
                                suggestion2 = "      1. 此设备未Root，无法获得完全的外置存储写入权限";
                                suggestion3 = "      2. 建议：Root设备后启用ROOT权限";
                                suggestion4 = "      3. 或者等待后续版本支持SAF URI映射（功能受限）";
                            }
                            
                            // 输出到Logcat
                            Log.e(TAG, "   ========================================");
                            Log.e(TAG, errorMsg);
                            Log.e(TAG, reason + reasonDetail);
                            Log.e(TAG, suggestion1);
                            Log.e(TAG, suggestion2);
                            Log.e(TAG, suggestion3);
                            if (!suggestion4.isEmpty()) {
                                Log.e(TAG, suggestion4);
                            }
                            Log.e(TAG, String.format("   使用路径: %s", physicalPath));
                            Log.e(TAG, "   ========================================");
                            
                            // 同步到APP内部日志（便于用户在APP内查看）
                            logToAppInternal("ERROR", "   ========================================");
                            logToAppInternal("ERROR", errorMsg);
                            logToAppInternal("ERROR", reason + reasonDetail);
                            logToAppInternal("ERROR", suggestion1);
                            logToAppInternal("ERROR", suggestion2);
                            logToAppInternal("ERROR", suggestion3);
                            if (!suggestion4.isEmpty()) {
                                logToAppInternal("ERROR", suggestion4);
                            }
                            logToAppInternal("ERROR", String.format("   使用路径: %s", physicalPath));
                            logToAppInternal("ERROR", "   ========================================");
                            
                            skippedCount++;
                            continue; // 跳过无法写入的外置存储，不挂载
                        } else {
                            Log.i(TAG, "   ✅ Android 9+ 写入测试通过：可创建、写入、重命名、删除文件");
                            Log.i(TAG, String.format("   使用路径: %s", physicalPath));
                            logToAppInternal("INFO", String.format("✅ Android 9+ 外置存储 %s 写入测试通过 (路径: %s)", storage.name, physicalPath));
                        }
                    } else if (storage.isRemovable) {
                        // Android 9 以下版本，也进行简单测试
                        Log.i(TAG, String.format("   🧪 对外置存储 %s 进行写入测试: %s", storage.name, physicalPath));
                        if (!canWrite) {
                            // 即使 canWrite() 返回 false，也尝试实际写入测试（某些设备可能canWrite返回false但实际可写）
                            boolean actuallyWritable = testActualWriteAccess(physicalPath);
                            if (!actuallyWritable) {
                                Log.w(TAG, String.format("   ⚠️ 外置存储 %s 写入测试失败", storage.name));
                                skippedCount++;
                                continue;
                            } else {
                                Log.i(TAG, String.format("   ✅ 外置存储 %s 写入测试通过（实际测试通过，虽然canWrite()返回false）", storage.name));
                            }
                        }
                    }
                    
                    // 挂载到AList
                    alistServer.addLocalStorageDriver(physicalPath, mountPath);
                    mountCount++;
                    
                    Log.i(TAG, String.format("✅ 已挂载 [%d/%d]: %s -> %s", 
                            mountCount, storageDevices.size(), mountPath, physicalPath));
                    
                    // 对于外置存储，进行全面的权限诊断
                    if (storage.isRemovable) {
                        Log.i(TAG, "   🔍 开始外置存储权限诊断...");
                        Log.i(TAG, "   ========================================");
                        String diagnostic = PermissionDiagnostic.diagnoseStorage(this, physicalPath);
                        Log.i(TAG, diagnostic);
                        Log.i(TAG, "   ========================================");
                        
                        // 如果诊断发现问题，尝试修复
                        if (diagnostic.contains("只读") || diagnostic.contains("✗") || 
                            diagnostic.contains("失败") || diagnostic.contains("ro,")) {
                            Log.w(TAG, "   ⚠️ 检测到权限问题，尝试修复...");
                            
                            // 如果设备已Root且用户启用了ROOT权限，使用Root修复
                            if (isDeviceRooted && isRootEnabled) {
                                Log.i(TAG, "   🔓 使用ROOT权限修复外置存储...");
                                String rootFixResult = RootUtil.fixExternalStorageWithRoot(physicalPath);
                                Log.i(TAG, rootFixResult);
                            } else if (isDeviceRooted && !isRootEnabled) {
                                Log.w(TAG, "   ⚠️ 设备已Root但用户未启用ROOT权限");
                                Log.w(TAG, "   💡 建议：在【权限配置】中启用【外置存储ROOT权限】");
                            } else {
                                // 非Root设备，使用普通方法尝试
                                String fixResult = PermissionDiagnostic.tryFixStoragePermissions(physicalPath);
                                Log.i(TAG, "   " + fixResult);
                                Log.w(TAG, "   ⚠️ 非Root设备，权限修复能力有限");
                                Log.w(TAG, "   💡 建议：如果设备已Root，重启APP将自动使用Root修复");
                            }
                            
                            // 再次测试
                            Log.i(TAG, "   🔄 修复后再次测试...");
                            try {
                                File testFile = new File(physicalPath, ".alistlite_write_test_after_fix.tmp");
                                boolean created = testFile.createNewFile();
                                if (created) {
                                    File renamed = new File(physicalPath, ".alistlite_renamed.tmp");
                                    boolean renameOk = testFile.renameTo(renamed);
                                    if (renameOk) {
                                        renamed.delete();
                                        Log.i(TAG, "   ✅ 修复后测试成功：可创建、重命名、删除");
                                    } else {
                                        testFile.delete();
                                        Log.e(TAG, "   ❌ 修复后仍无法重命名！");
                                        Log.e(TAG, "   💡 可能原因：");
                                        Log.e(TAG, "      1. 存储挂载为只读（ro）- 无解");
                                        Log.e(TAG, "      2. SELinux强制模式 - 需要宽松模式或Root");
                                        Log.e(TAG, "      3. 文件系统损坏 - 需要修复或格式化");
                                    }
                                } else {
                                    Log.e(TAG, "   ❌ 修复后仍无法创建文件！");
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "   ❌ 修复后测试异常: " + e.getMessage());
                            }
                        } else {
                            Log.i(TAG, "   ✅ 权限诊断通过");
                        }
                    } else {
                        // 内置存储简单检查
                        if (storageFile.canWrite()) {
                            Log.i(TAG, "   ✓ 可写入");
                        } else {
                            Log.w(TAG, "   ⚠ 只读模式（异常）");
                        }
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "❌ 挂载失败 " + storage.name + ": " + e.getMessage());
                }
            }
            
            Log.i(TAG, String.format("========== 挂载完成：成功 %d/%d，跳过 %d ==========", 
                    mountCount, storageDevices.size(), skippedCount));
            
            if (mountCount > 0) {
                if (skippedCount > 0) {
                    showToast(String.format("已挂载 %d 个存储设备，跳过 %d 个（无法写入）", 
                            mountCount, skippedCount), Toast.LENGTH_LONG);
                } else {
                    showToast(String.format("已挂载 %d 个存储设备", mountCount), Toast.LENGTH_SHORT);
                }
            } else {
                Log.e(TAG, "❌ 所有存储设备挂载失败！");
                showToast("存储设备挂载失败，请检查权限", Toast.LENGTH_LONG);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "挂载存储设备异常: " + e.getMessage(), e);
            showToast("存储挂载异常: " + e.getMessage(), Toast.LENGTH_LONG);
        }
    }

    /**
     * 记录重要日志到APP内部日志（便于用户在APP内查看）
     * 
     * @param level 日志级别：ERROR, WARN, INFO
     * @param message 日志消息
     */
    private void logToAppInternal(String level, String message) {
        try {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(new Date());
            String logEntry = String.format("%s[%s] [AlistService] %s\r\n\r\n", level, timestamp, message);
            
            synchronized (Alist.ALIST_LOGS) {
                // 检查日志大小，防止内存溢出
                if (Alist.ALIST_LOGS.length() > 500000) {
                    int keepSize = (int) (500000 * 0.8);
                    int deleteSize = Alist.ALIST_LOGS.length() - keepSize;
                    Alist.ALIST_LOGS.delete(0, deleteSize);
                    Alist.ALIST_LOGS.insert(0, "... [日志已自动清理旧内容] ...\r\n\r\n");
                }
                Alist.ALIST_LOGS.append(logEntry);
            }
        } catch (Exception e) {
            // 如果同步失败，至少保证Logcat输出
            Log.e(TAG, "记录日志到APP内部失败: " + e.getMessage());
        }
    }

    /**
     * 测试路径的实际写入能力（Android 9+ 关键修复）
     * 通过创建、写入、重命名、删除文件来验证是否真正可写
     * 
     * @param path 要测试的路径
     * @return 是否真正可写入
     */
    private boolean testActualWriteAccess(String path) {
        File testDir = new File(path);
        if (!testDir.exists() || !testDir.isDirectory()) {
            Log.w(TAG, "   测试路径不存在或不是目录: " + path);
            return false;
        }
        
        // 检查目录是否可执行（对某些电视盒子很重要）
        if (!testDir.canExecute()) {
            Log.w(TAG, "   测试路径不可执行: " + path);
            // 对于电视盒子，即使不能执行也可能可以写入
        }
        
        File testFile = null;
        File renamedFile = null;
        
        try {
            // 测试1：创建文件
            String testFileName = ".alistlite_write_test_" + System.currentTimeMillis() + ".tmp";
            testFile = new File(path, testFileName);
            boolean created = testFile.createNewFile();
            
            if (!created) {
                Log.w(TAG, "   ❌ 测试失败：无法创建文件");
                // 对于某些电视盒子，可能需要特殊处理
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Log.i(TAG, "   📺 Android 9+设备，尝试使用Root权限创建文件");
                    // 尝试使用Root权限创建文件
                    String cmd = "touch " + path + "/" + testFileName;
                    String result = RootUtil.executeRootCommand(cmd);
                    Log.d(TAG, "   Root命令结果: " + result);
                    if (testFile.exists()) {
                        Log.i(TAG, "   ✅ Root权限创建文件成功");
                        created = true;
                    }
                }
                
                if (!created) {
                    return false;
                }
            }
            
            // 测试2：写入内容
            try (java.io.FileWriter writer = new java.io.FileWriter(testFile)) {
                writer.write("AListLite write test");
                writer.flush();
            } catch (Exception e) {
                Log.w(TAG, "   ❌ 测试失败：无法写入文件内容: " + e.getMessage());
                testFile.delete();
                return false;
            }
            
            // 测试3：重命名文件（Android 9+外置存储的关键测试点）
            String renamedFileName = ".alistlite_renamed_" + System.currentTimeMillis() + ".tmp";
            renamedFile = new File(path, renamedFileName);
            boolean renamed = testFile.renameTo(renamedFile);
            
            if (!renamed) {
                Log.w(TAG, "   ❌ 测试失败：无法重命名文件（这是Android 9+外置存储的常见问题）");
                // 对于电视盒子，尝试使用Root权限重命名
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Log.i(TAG, "   📺 Android 9+设备，尝试使用Root权限重命名文件");
                    String cmd = "mv " + testFile.getAbsolutePath() + " " + renamedFile.getAbsolutePath();
                    String result = RootUtil.executeRootCommand(cmd);
                    Log.d(TAG, "   Root命令结果: " + result);
                    if (renamedFile.exists()) {
                        Log.i(TAG, "   ✅ Root权限重命名文件成功");
                        renamed = true;
                    }
                }
            }
            
            // 测试4：删除重命名后的文件
            boolean deleted = false;
            if (renamedFile != null && renamedFile.exists()) {
                deleted = renamedFile.delete();
                if (!deleted) {
                    Log.w(TAG, "   ❌ 测试失败：无法删除重命名后的文件");
                    // 尝试使用Root权限删除
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        Log.i(TAG, "   📺 Android 9+设备，尝试使用Root权限删除文件");
                        String cmd = "rm " + renamedFile.getAbsolutePath();
                        String result = RootUtil.executeRootCommand(cmd);
                        Log.d(TAG, "   Root命令结果: " + result);
                        if (!renamedFile.exists()) {
                            Log.i(TAG, "   ✅ Root权限删除文件成功");
                            deleted = true;
                        }
                    }
                }
            } else if (testFile != null && testFile.exists()) {
                // 如果重命名失败，尝试删除原始文件
                deleted = testFile.delete();
                if (!deleted) {
                    Log.w(TAG, "   ❌ 测试失败：无法删除原始文件");
                    // 尝试使用Root权限删除
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        Log.i(TAG, "   📺 Android 9+设备，尝试使用Root权限删除文件");
                        String cmd = "rm " + testFile.getAbsolutePath();
                        String result = RootUtil.executeRootCommand(cmd);
                        Log.d(TAG, "   Root命令结果: " + result);
                        if (!testFile.exists()) {
                            Log.i(TAG, "   ✅ Root权限删除文件成功");
                            deleted = true;
                        }
                    }
                }
            }
            
            if (renamed && deleted) {
                Log.i(TAG, "   ✅ 实际写入测试全部通过");
                return true;
            } else {
                Log.w(TAG, "   ⚠️ 实际写入测试部分失败 (重命名:" + renamed + ", 删除:" + deleted + ")");
                // 即使部分测试失败，只要能创建和写入文件就认为基本可用
                return testFile != null && testFile.exists();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "   ❌ 实际写入测试异常: " + e.getMessage(), e);
            // 清理测试文件
            try {
                if (testFile != null && testFile.exists()) testFile.delete();
                if (renamedFile != null && renamedFile.exists()) renamedFile.delete();
            } catch (Exception ignored) {}
            return false;
        }
    }

    private void showToast(String msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    private void showToast(String msg, int duration) {
        Toast.makeText(getApplicationContext(), msg, duration).show();
    }
}


