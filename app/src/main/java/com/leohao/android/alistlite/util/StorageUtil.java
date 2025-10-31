package com.leohao.android.alistlite.util;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 存储设备工具类
 * 用于获取所有存储设备路径（包括内置存储、SD卡、OTG）
 * 
 * @author lambda567
 */
public class StorageUtil {
    private static final String TAG = "StorageUtil";

    /**
     * 获取所有存储设备信息
     * 
     * @param context Context
     * @return 存储设备信息列表
     */
    public static List<StorageInfo> getAllStorageDevices(Context context) {
        List<StorageInfo> storageList = new ArrayList<>();
        
        try {
            // 方法1：使用StorageManager（Android 7.0+推荐）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                storageList = getStorageDevicesModern(context);
            } else {
                // 方法2：使用反射（Android 5-6兼容）
                storageList = getStorageDevicesReflection(context);
            }
            
            // 如果上述方法都失败，使用兜底方案
            if (storageList.isEmpty()) {
                storageList = getStorageDevicesFallback(context);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "获取存储设备失败: " + e.getMessage(), e);
            // 使用兜底方案
            storageList = getStorageDevicesFallback(context);
        }
        
        return storageList;
    }

    /**
     * 方法1：Android 7.0+ 使用官方API
     */
    private static List<StorageInfo> getStorageDevicesModern(Context context) {
        List<StorageInfo> storageList = new ArrayList<>();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            StorageManager storageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            List<StorageVolume> volumes = storageManager.getStorageVolumes();
            
            for (int i = 0; i < volumes.size(); i++) {
                StorageVolume volume = volumes.get(i);
                String path = getVolumePathReflection(volume);
                
                if (path != null && !path.isEmpty()) {
                    String description = getVolumeDescription(volume, context);
                    boolean isPrimary = volume.isPrimary();
                    boolean isRemovable = volume.isRemovable();
                    boolean isEmulated = volume.isEmulated();
                    
                    String name = isPrimary ? "内置存储" : 
                                 (isRemovable ? "外置存储" + (i > 1 ? i : "") : "其他存储");
                    
                    storageList.add(new StorageInfo(name, path, isPrimary, isRemovable, description));
                    Log.i(TAG, String.format("发现存储: %s -> %s (Primary:%s, Removable:%s, Emulated:%s)", 
                            name, path, isPrimary, isRemovable, isEmulated));
                }
            }
        }
        
        return storageList;
    }

    /**
     * 方法2：Android 5-6 使用反射
     */
    private static List<StorageInfo> getStorageDevicesReflection(Context context) {
        List<StorageInfo> storageList = new ArrayList<>();
        
        try {
            StorageManager storageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            Method getVolumeList = StorageManager.class.getMethod("getVolumeList");
            Object[] storageVolumes = (Object[]) getVolumeList.invoke(storageManager);
            
            if (storageVolumes != null) {
                for (int i = 0; i < storageVolumes.length; i++) {
                    Object volume = storageVolumes[i];
                    
                    // 获取路径
                    Method getPath = volume.getClass().getMethod("getPath");
                    String path = (String) getPath.invoke(volume);
                    
                    // 获取状态
                    Method getState = volume.getClass().getMethod("getState");
                    String state = (String) getState.invoke(volume);
                    
                    // 只添加已挂载的存储
                    if ("mounted".equals(state) && path != null && !path.isEmpty()) {
                        Method isRemovable = volume.getClass().getMethod("isRemovable");
                        boolean removable = (Boolean) isRemovable.invoke(volume);
                        
                        String name = removable ? "外置存储" + (i > 0 ? i : "") : "内置存储";
                        storageList.add(new StorageInfo(name, path, !removable, removable, ""));
                        
                        Log.i(TAG, String.format("发现存储(反射): %s -> %s (Removable:%s)", 
                                name, path, removable));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "反射获取存储设备失败: " + e.getMessage());
        }
        
        return storageList;
    }

    /**
     * 方法3：兜底方案 - 扫描标准路径
     */
    private static List<StorageInfo> getStorageDevicesFallback(Context context) {
        List<StorageInfo> storageList = new ArrayList<>();
        
        // 内置存储
        File primaryStorage = Environment.getExternalStorageDirectory();
        if (primaryStorage != null && primaryStorage.exists()) {
            storageList.add(new StorageInfo("内置存储", 
                    primaryStorage.getAbsolutePath(), true, false, "主存储"));
            Log.i(TAG, "兜底方案: 添加内置存储 -> " + primaryStorage.getAbsolutePath());
        }
        
        // 扫描常见的外置存储路径
        // 关键修复：优先扫描/mnt/media_rw（绕过sdcardfs权限限制）
        String[] possiblePaths = {
                "/mnt/media_rw",   // 优先！直接访问真实挂载点
                "/storage",
                "/mnt/sdcard",
                "/mnt/extSdCard",
                "/storage/sdcard1",
                "/storage/extSdCard"
        };
        
        for (String basePath : possiblePaths) {
            File dir = new File(basePath);
            if (dir.exists() && dir.isDirectory()) {
                // 对于/mnt/media_rw路径，需要特殊处理
                if (basePath.equals("/mnt/media_rw")) {
                    File[] subDirs = dir.listFiles();
                    if (subDirs != null) {
                        for (File subDir : subDirs) {
                            String path = subDir.getAbsolutePath();
                            // 检查是否可读
                            if (subDir.canRead()) {
                                // 对于media_rw路径，即使canWrite返回false也要尝试实际写入测试
                                String name = "外置存储(" + subDir.getName() + ")[直接访问]";
                                storageList.add(new StorageInfo(name, path, false, true, "直接访问模式"));
                                Log.i(TAG, String.format("兜底方案: 发现 %s -> %s (直接访问media_rw)", name, path));
                            }
                        }
                    }
                } else {
                    // 处理其他路径
                    File[] subDirs = dir.listFiles();
                    if (subDirs != null) {
                        for (File subDir : subDirs) {
                            String path = subDir.getAbsolutePath();
                            // 跳过已添加的内置存储
                            if (path.contains("emulated") || path.equals(primaryStorage.getAbsolutePath())) {
                                continue;
                            }
                            // 检查是否可读写
                            if (subDir.canRead()) {
                                boolean canWrite = subDir.canWrite();
                                String name = "外置存储(" + subDir.getName() + ")";
                                storageList.add(new StorageInfo(name, path, false, true, 
                                        canWrite ? "可读写" : "只读"));
                                Log.i(TAG, String.format("兜底方案: 发现 %s -> %s (可写:%s)", 
                                        name, path, canWrite));
                            }
                        }
                    }
                }
            }
        }
        
        return storageList;
    }

    /**
     * 获取StorageVolume路径（反射）
     */
    private static String getVolumePathReflection(StorageVolume volume) {
        try {
            // Android 7.0-10 使用反射
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Method getPath = volume.getClass().getMethod("getPath");
                Object path = getPath.invoke(volume);
                return path != null ? (String) path : null;
            } else {
                // Android 11+ 使用getDirectory()
                File directory = volume.getDirectory();
                return directory != null ? directory.getAbsolutePath() : null;
            }
        } catch (Exception e) {
            Log.e(TAG, "反射获取路径失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 获取StorageVolume描述
     */
    private static String getVolumeDescription(StorageVolume volume, Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return volume.getDescription(context);
        }
        return "";
    }

    /**
     * 检查路径是否包含受保护的Android目录
     * 这些目录在某些Android版本/设备上有特殊权限限制
     */
    public static boolean isProtectedAndroidPath(String path) {
        if (path == null) return false;
        
        String lowerPath = path.toLowerCase();
        // Android/data 和 Android/obb 是受保护目录
        // 但我们只在挂载外置存储时需要注意，内置存储可以访问
        return false; // 先返回false，让系统尝试访问
    }

    /**
     * 存储设备信息类
     */
    public static class StorageInfo {
        public String name;        // 显示名称
        public String path;        // 实际路径
        public boolean isPrimary;  // 是否为主存储
        public boolean isRemovable;// 是否为可移除存储
        public String description; // 描述

        public StorageInfo(String name, String path, boolean isPrimary, 
                          boolean isRemovable, String description) {
            this.name = name;
            this.path = path;
            this.isPrimary = isPrimary;
            this.isRemovable = isRemovable;
            this.description = description;
        }

        @Override
        public String toString() {
            return String.format("%s: %s (Primary:%s, Removable:%s)", 
                    name, path, isPrimary, isRemovable);
        }
    }