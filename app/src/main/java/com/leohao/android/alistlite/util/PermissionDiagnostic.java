package com.leohao.android.alistlite.util;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 权限诊断工具
 * 用于深度分析存储权限问题
 * 
 * @author lambda567
 */
public class PermissionDiagnostic {
    private static final String TAG = "PermissionDiagnostic";

    /**
     * 全面诊断存储设备权限
     */
    public static String diagnoseStorage(Context context, String storagePath) {
        StringBuilder report = new StringBuilder();
        report.append("========== 存储权限诊断报告 ==========\n");
        report.append("路径: ").append(storagePath).append("\n\n");
        
        // 1. 检查Java层权限
        report.append("【Java层检查】\n");
        File storageDir = new File(storagePath);
        report.append("exists: ").append(storageDir.exists()).append("\n");
        report.append("canRead: ").append(storageDir.canRead()).append("\n");
        report.append("canWrite: ").append(storageDir.canWrite()).append("\n");
        report.append("canExecute: ").append(storageDir.canExecute()).append("\n\n");
        
        // 2. 检查文件系统挂载选项
        report.append("【文件系统挂载】\n");
        String mountInfo = checkMountOptions(storagePath);
        report.append(mountInfo).append("\n");
        
        // 3. 检查SELinux状态
        report.append("【SELinux状态】\n");
        String selinuxStatus = checkSELinux();
        report.append(selinuxStatus).append("\n");
        
        // 4. 检查目录权限（ls -ld）
        report.append("【目录权限】\n");
        String dirPermissions = checkDirectoryPermissions(storagePath);
        report.append(dirPermissions).append("\n");
        
        // 5. 实际写入测试
        report.append("【写入测试】\n");
        String writeTest = performWriteTest(storagePath);
        report.append(writeTest).append("\n");
        
        report.append("========================================\n");
        
        String fullReport = report.toString();
        Log.i(TAG, fullReport);
        return fullReport;
    }

    /**
     * 检查文件系统挂载选项
     */
    private static String checkMountOptions(String path) {
        try {
            Process process = Runtime.getRuntime().exec("mount");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder result = new StringBuilder();
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(path) || line.contains("storage") || line.contains("media_rw")) {
                    result.append(line).append("\n");
                    
                    // 检查是否为只读挂载
                    if (line.contains("(ro,") || line.contains(" ro ")) {
                        result.append("⚠️ 警告：此存储挂载为只读模式！\n");
                    } else if (line.contains("(rw,") || line.contains(" rw ")) {
                        result.append("✓ 存储挂载为读写模式\n");
                    }
                }
            }
            reader.close();
            
            if (result.length() == 0) {
                return "未找到对应的挂载信息\n";
            }
            return result.toString();
        } catch (Exception e) {
            return "检查失败: " + e.getMessage() + "\n";
        }
    }

    /**
     * 检查SELinux状态
     */
    private static String checkSELinux() {
        try {
            Process process = Runtime.getRuntime().exec("getenforce");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String status = reader.readLine();
            reader.close();
            
            if ("Enforcing".equalsIgnoreCase(status)) {
                return "SELinux: Enforcing（强制模式）\n⚠️ 可能阻止文件操作\n";
            } else if ("Permissive".equalsIgnoreCase(status)) {
                return "SELinux: Permissive（宽松模式）\n✓ 不影响文件操作\n";
            } else {
                return "SELinux: " + status + "\n";
            }
        } catch (Exception e) {
            return "SELinux检查失败: " + e.getMessage() + "\n";
        }
    }

    /**
     * 检查目录权限
     */
    private static String checkDirectoryPermissions(String path) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"ls", "-ld", path});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String permissions = reader.readLine();
            reader.close();
            
            if (permissions != null) {
                return permissions + "\n";
            }
            return "无法获取权限信息\n";
        } catch (Exception e) {
            return "检查失败: " + e.getMessage() + "\n";
        }
    }

    /**
     * 执行实际写入测试
     */
    private static String performWriteTest(String path) {
        StringBuilder result = new StringBuilder();
        File testFile = new File(path, ".alistlite_permission_test.txt");
        
        try {
            // 测试1：创建文件
            boolean created = testFile.createNewFile();
            result.append("创建文件: ").append(created ? "✓ 成功" : "✗ 失败").append("\n");
            
            if (created) {
                // 测试2：写入内容
                try {
                    java.io.FileWriter writer = new java.io.FileWriter(testFile);
                    writer.write("AListLite Permission Test");
                    writer.close();
                    result.append("写入内容: ✓ 成功\n");
                    
                    // 测试2.1：读取验证
                    java.io.FileReader reader = new java.io.FileReader(testFile);
                    char[] buffer = new char[50];
                    int len = reader.read(buffer);
                    reader.close();
                    result.append("读取验证: ✓ 成功 (").append(len).append(" 字节)\n");
                    
                } catch (Exception e) {
                    result.append("写入内容: ✗ 失败 - ").append(e.getMessage()).append("\n");
                }
                
                // 测试3：重命名文件（关键测试！）
                File renamedFile = new File(path, ".alistlite_test_RENAMED.txt");
                boolean renamed = testFile.renameTo(renamedFile);
                result.append("重命名文件: ").append(renamed ? "✓ 成功" : "✗ 失败 ⚠️关键失败").append("\n");
                
                if (!renamed) {
                    // 如果重命名失败，这就是问题所在！
                    result.append("⚠️ JAVA层重命名失败说明问题在文件系统层面！\n");
                    result.append("   可能原因:\n");
                    result.append("   1. 存储挂载为只读\n");
                    result.append("   2. 文件系统错误\n");
                    result.append("   3. 需要Root权限\n");
                }
                
                // 测试4：删除文件
                File fileToDelete = renamed ? renamedFile : testFile;
                boolean deleted = fileToDelete.delete();
                result.append("删除文件: ").append(deleted ? "✓ 成功" : "✗ 失败").append("\n");
                
                if (!deleted) {
                    result.append("⚠️ JAVA层删除失败！\n");
                }
            } else {
                result.append("⚠️ JAVA层无法创建文件！\n");
                result.append("   问题在Java层权限，检查:\n");
                result.append("   1. WRITE_EXTERNAL_STORAGE是否真正授予\n");
                result.append("   2. 存储是否为只读挂载\n");
            }
            
        } catch (Exception e) {
            result.append("测试异常: ").append(e.getMessage()).append("\n");
            result.append("异常栈: ").append(e.toString()).append("\n");
        } finally {
            // 清理测试文件
            try {
                if (testFile.exists()) testFile.delete();
                File renamedFile = new File(path, ".alistlite_test_RENAMED.txt");
                if (renamedFile.exists()) renamedFile.delete();
            } catch (Exception ignored) {}
        }
        
        return result.toString();
    }

    /**
     * 尝试修复外置存储权限（需要Root）
     */
    public static String tryFixStoragePermissions(String storagePath) {
        StringBuilder result = new StringBuilder();
        result.append("尝试修复存储权限...\n");
        
        try {
            // 尝试1：chmod 777
            Process p1 = Runtime.getRuntime().exec(new String[]{"chmod", "777", storagePath});
            int r1 = p1.waitFor();
            result.append("chmod 777: ").append(r1 == 0 ? "✓" : "✗").append("\n");
            
            // 尝试2：chown（需要root）
            Process p2 = Runtime.getRuntime().exec(new String[]{"su", "-c", "chmod", "777", storagePath});
            int r2 = p2.waitFor();
            result.append("su chmod 777: ").append(r2 == 0 ? "✓ (需要Root)" : "✗").append("\n");
            
        } catch (Exception e) {
            result.append("修复失败: ").append(e.getMessage()).append("\n");
        }
        
        return result.toString();
    }
}

