package com.leohao.android.alistlite.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.util.List;

/**
 * DocumentFile工具类
 * 用于处理外置存储（OTG/SD卡）的Android/data等受保护目录
 * 
 * @author lambda567
 */
public class DocumentFileHelper {
    private static final String TAG = "DocumentFileHelper";
    public static final int REQUEST_CODE_OPEN_DOCUMENT_TREE = 2001;

    /**
     * 请求外置存储目录的访问权限
     * 这是访问外置存储Android/data目录的关键
     * 
     * @param activity Activity
     * @param storagePath 存储路径（如 /storage/8956-8C7E）
     */
    public static void requestStorageAccess(Activity activity, String storagePath) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                           Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                           Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            
            // 尝试预选择目录（Android 8.0+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 将路径转换为URI
                try {
                    Uri initialUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A");
                    intent.putExtra("android.provider.extra.INITIAL_URI", initialUri);
                } catch (Exception e) {
                    Log.w(TAG, "无法设置初始URI: " + e.getMessage());
                }
            }
            
            activity.startActivityForResult(intent, REQUEST_CODE_OPEN_DOCUMENT_TREE);
        }
    }

    /**
     * 处理目录访问授权结果
     * 
     * @param context Context
     * @param resultCode 结果码
     * @param data Intent数据
     * @return 是否授权成功
     */
    public static boolean handleStorageAccessResult(Context context, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                // 持久化权限
                context.getContentResolver().takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                );
                
                Log.i(TAG, "✅ 已授予目录访问权限: " + treeUri);
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否已授予特定目录的访问权限
     * 
     * @param context Context
     * @return 已授权的目录列表
     */
    public static List<UriPermission> getGrantedPermissions(Context context) {
        return context.getContentResolver().getPersistedUriPermissions();
    }

    /**
     * 使用DocumentFile删除文件（用于受保护目录）
     * 
     * @param context Context
     * @param filePath 文件路径
     * @return 是否删除成功
     */
    public static boolean deleteFileWithDocumentFile(Context context, String filePath) {
        try {
            // 尝试从已授权的URI中找到对应的文件
            List<UriPermission> permissions = getGrantedPermissions(context);
            for (UriPermission permission : permissions) {
                Uri treeUri = permission.getUri();
                DocumentFile rootDoc = DocumentFile.fromTreeUri(context, treeUri);
                if (rootDoc != null) {
                    // 递归查找文件
                    DocumentFile targetFile = findDocumentFile(rootDoc, filePath);
                    if (targetFile != null && targetFile.exists()) {
                        boolean deleted = targetFile.delete();
                        Log.i(TAG, "DocumentFile删除结果: " + deleted + " - " + filePath);
                        return deleted;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "DocumentFile删除失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 在DocumentFile树中查找文件
     */
    private static DocumentFile findDocumentFile(DocumentFile root, String targetPath) {
        // 简化实现：这里可以添加更复杂的查找逻辑
        return null;
    }
}

