package com.leohao.android.alistlite;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.kyleduo.switchbutton.SwitchButton;
import com.leohao.android.alistlite.adaptor.PermissionListAdapter;
import com.leohao.android.alistlite.model.PermissionItem;
import com.leohao.android.alistlite.util.Constants;
import com.leohao.android.alistlite.util.RootUtil;
import com.leohao.android.alistlite.util.SharedDataHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限配置 Activity
 *
 * @author LeoHao
 */
public class PermissionActivity extends AppCompatActivity implements OnItemClickListener {
    private static final String TAG = "PermissionActivity";
    private PermissionListAdapter permissionListAdapter;
    private final List<PermissionItem> permissionList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission);
        //控件和数据初始化
        init();
    }

    /**
     * 初始化控件和用户信息
     */
    private void init() {
        ListView permissionListView = findViewById(R.id.permission_list);
        SwitchButton rootSwitch = findViewById(R.id.root_permission_switch);
        
        //初始化刷新权限列表
        refreshPermissionList();
        //初始化列表视图适配器
        permissionListAdapter = new PermissionListAdapter(this, permissionList);
        permissionListView.setAdapter(permissionListAdapter);
        permissionListView.setOnItemClickListener(this);
        
        //初始化ROOT权限开关
        initRootPermissionSwitch(rootSwitch);
    }
    
    /**
     * 初始化ROOT权限开关
     */
    private void initRootPermissionSwitch(SwitchButton rootSwitch) {
        // 读取保存的ROOT权限状态
        boolean isRootEnabled = SharedDataHelper.getBoolean(Constants.KEY_ROOT_PERMISSION_ENABLED, false);
        rootSwitch.setCheckedNoEvent(isRootEnabled);
        
        // 检查设备是否Root
        boolean isDeviceRooted = RootUtil.isDeviceRooted();
        
        // 如果设备未Root，禁用开关
        if (!isDeviceRooted) {
            rootSwitch.setEnabled(false);
            rootSwitch.setAlpha(0.5f);
        }
        
        // 设置开关监听
        rootSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // 显示警告对话框
                new AlertDialog.Builder(this)
                        .setTitle("⚠️ ROOT权限警告")
                        .setMessage("启用ROOT权限将允许AListLite使用超级用户权限修复外置存储访问问题。\n\n" +
                                "⚠️ 风险提示：\n" +
                                "1. ROOT权限可能带来安全风险\n" +
                                "2. 错误操作可能损坏系统文件\n" +
                                "3. 仅在必要时启用\n\n" +
                                "✅ 优点：\n" +
                                "- 解决外置存储（OTG/SD卡）写入问题\n" +
                                "- 绕过系统权限限制\n\n" +
                                "是否确认启用ROOT权限？")
                        .setPositiveButton("确认启用", (dialog, which) -> {
                            // 保存状态
                            SharedDataHelper.putBoolean(Constants.KEY_ROOT_PERMISSION_ENABLED, true);
                            showToast("ROOT权限已启用，重启服务后生效");
                            Log.i(TAG, "✅ 用户启用了ROOT权限");
                        })
                        .setNegativeButton("取消", (dialog, which) -> {
                            // 取消时关闭开关
                            rootSwitch.setCheckedNoEvent(false);
                        })
                        .setCancelable(false)
                        .show();
            } else {
                // 关闭ROOT权限
                SharedDataHelper.putBoolean(Constants.KEY_ROOT_PERMISSION_ENABLED, false);
                showToast("ROOT权限已关闭");
                Log.i(TAG, "❌ 用户关闭了ROOT权限");
            }
        });
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        PermissionItem item = (PermissionItem) parent.getAdapter().getItem(position);
        //跳过已授权的权限
        if (item.getIsGranted()) {
            return;
        }
        // Android 5.0-5.1 不支持运行时权限请求
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            showToast("Android 5.x 系统权限在安装时已自动授予");
            return;
        }
        //跳转到对应权限设置页面
        try {
            XXPermissions.with(this).permission(item.getPermissionName()).request(new OnPermissionCallback() {
                @Override
                public void onGranted(@NonNull List<String> permissions, boolean allGranted) {
                    //新授权的权限提示设置完成
                    if (!item.getIsGranted()) {
                        showToast("设置成功");
                    }
                    //重新获取权限列表
                    refreshPermissionList();
                    //通知适配器数据变化
                    permissionListAdapter.notifyDataSetChanged();
                }

                @Override
                public void onDenied(@NonNull List<String> permissions, boolean doNotAskAgain) {
                    if (doNotAskAgain) {
                        showToast("设置失败，请手动授予相关权限");
                    } else {
                        showToast("设置失败");
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "fail to request permission: " + item.getPermissionName());
        }
    }

    @Override
    public void onBackPressed() {
        this.finish();
    }

    /**
     * 获取权限列表
     */
    private void refreshPermissionList() {
        //清空当前列表
        permissionList.clear();
        //获取当前应用的包名
        String packageName = getPackageName();
        try {
            //获取PackageInfo对象
            PackageInfo packageInfo = getPackageManager().getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
            //获取软件所需的所有权限
            String[] requestedPermissions = packageInfo.requestedPermissions;
            //依次检测权限授予状态
            for (String permission : requestedPermissions) {
                //跳过未声明的权限（未声明的权限代表默认允许）
                if (!Constants.permissionDescriptionMap.containsKey(permission)) {
                    continue;
                }
                //若当前 API 版本大于等于 33（Android 13），则跳过 READ_EXTERNAL_STORAGE 和 WRITE_EXTERNAL_STORAGE 检查（被新权限替代）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (Permission.READ_EXTERNAL_STORAGE.equals(permission) || 
                        Permission.WRITE_EXTERNAL_STORAGE.equals(permission)) {
                        continue;
                    }
                }
                //若当前 API 版本大于等于 30（Android 11），则跳过 WRITE_EXTERNAL_STORAGE 检查（被 MANAGE_EXTERNAL_STORAGE 替代）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Permission.WRITE_EXTERNAL_STORAGE.equals(permission)) {
                    continue;
                }
                //获取权限状态
                boolean isGranted = XXPermissions.isGranted(this, permission);
                PermissionItem permissionItem = new PermissionItem(permission, permission.replaceAll(Constants.ANDROID_PERMISSION_PREFIX, ""), Constants.permissionDescriptionMap.get(permission), isGranted);
                permissionList.add(permissionItem);
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
