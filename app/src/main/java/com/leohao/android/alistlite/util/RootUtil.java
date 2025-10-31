package com.leohao.android.alistlite.util;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

/**
 * Root权限工具
 * 
 * @author lambda567
 */
public class RootUtil {
    private static final String TAG = "RootUtil";
    private static Boolean isRooted = null;

    /**
     * 检查设备是否已Root
     */
    public static boolean isDeviceRooted() {
        if (isRooted != null) {
            return isRooted;
        }
        
        isRooted = checkRootMethod1() || checkRootMethod2() || checkRootMethod3();
        return isRooted;
    }

    private static boolean checkRootMethod1() {
        String[] paths = {
                "/system/app/Superuser.apk",
                "/sbin/su",
                "/system/bin/su",
                "/system/xbin/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/su",
                "/su/bin/su"
        };
        for (String path : paths) {
            if (new java.io.File(path).exists()) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkRootMethod2() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"/system/xbin/which", "su"});
            BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));
            return in.readLine() != null;
        } catch (Throwable t) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static boolean checkRootMethod3() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("exit\n");
            os.flush();
            int exitValue = process.waitFor();
            return exitValue == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    /**
     * 执行Root命令
     */
    public static String executeRootCommand(String command) {
        StringBuilder output = new StringBuilder();
        Process process = null;
        DataOutputStream os = null;
        BufferedReader successReader = null;
        BufferedReader errorReader = null;

        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();

            successReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            String line;
            while ((line = successReader.readLine()) != null) {
                output.append(line).append("\n");
            }
            while ((line = errorReader.readLine()) != null) {
                output.append("ERROR: ").append(line).append("\n");
            }

            process.waitFor();

        } catch (Exception e) {
            Log.e(TAG, "Root命令执行失败: " + e.getMessage());
            output.append("执行失败: ").append(e.getMessage());
        } finally {
            try {
                if (os != null) os.close();
                if (successReader != null) successReader.close();
                if (errorReader != null) errorReader.close();
                if (process != null) process.destroy();
            } catch (Exception ignored) {}
        }

        return output.toString();
    }

    /**
     * 修复外置存储权限（使用Root）
     */
    public static String fixExternalStorageWithRoot(String storagePath) {
        if (!isDeviceRooted()) {
            return "设备未Root，无法执行修复";
        }

        StringBuilder result = new StringBuilder();
        result.append("使用Root权限修复外置存储...\n");

        // 1. 设置777权限
        String cmd1 = "chmod -R 777 " + storagePath;
        result.append("执行: ").append(cmd1).append("\n");
        result.append(executeRootCommand(cmd1)).append("\n");

        // 2. 修改所有者为当前APP
        // 获取APP的UID
        String cmd2 = "chown -R $(stat -c %u /data/data/com.leohao.android.alistlite):sdcard_rw " + storagePath;
        result.append("执行: ").append(cmd2).append("\n");
        result.append(executeRootCommand(cmd2)).append("\n");
        
        // 3. 设置SELinux上下文
        String cmd3 = "chcon -R u:object_r:media_rw_data_file:s0 " + storagePath;
        result.append("执行: ").append(cmd3).append("\n");
        result.append(executeRootCommand(cmd3)).append("\n");

        return result.toString();
    }
    
    /**
     * 检查SELinux状态
     */
    public static String checkSELinuxStatus() {
        try {
            Process process = Runtime.getRuntime().exec("getenforce");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String status = reader.readLine();
            process.waitFor();
            return status != null ? status : "未知";
        } catch (Exception e) {
            return "检查失败: " + e.getMessage();
        }
    }
}

