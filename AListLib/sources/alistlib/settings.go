package alistlib

import (
	"context"
	"github.com/OpenListTeam/OpenList/v4/cmd"
	"github.com/OpenListTeam/OpenList/v4/cmd/flags"
	"github.com/OpenListTeam/OpenList/v4/internal/model"
	"github.com/OpenListTeam/OpenList/v4/internal/op"
	"github.com/OpenListTeam/OpenList/v4/pkg/utils"
)

func SetConfigData(path string) {
	flags.DataDir = path
}

func SetConfigLogStd(b bool) {
	flags.LogStd = b
}

func SetConfigDebug(b bool) {
	flags.Debug = b
}

func SetConfigNoPrefix(b bool) {
	flags.NoPrefix = b
}

func GetAllStorages() int {
	var drivers = op.GetAllStorages()
	return len(drivers)
}

func AddLocalStorage(localPath string, mountPath string) {
	//设置本地存储
	// 关键修复：添加更多权限选项以支持外置存储
	storage := model.Storage{
		Driver:     "Local",
		MountPath:  mountPath,
		Proxy:      model.Proxy{WebdavPolicy: "native_proxy"},
		EnableSign: false,
		// 关键配置说明：
		// - mkdir_perm: 777 给予最大权限（rwxrwxrwx）
		// - show_hidden: true 显示隐藏文件（包括.nomedia等）
		// - recycle_bin_path: "delete permanently" 永久删除而不是移到回收站
		Addition: "{\"root_folder_path\":\"" + localPath + "\",\"thumbnail\":false,\"thumb_cache_folder\":\"\",\"show_hidden\":true,\"mkdir_perm\":\"777\",\"recycle_bin_path\":\"delete permanently\"}",
	}
	//创建本地存储
	storageId, err := op.CreateStorage(context.Background(), storage)
	if err != nil {
		utils.Log.Errorf("failed to mount local storage: %+v", err)
		return
	}
	utils.Log.Infof("success: mount local storage [%s] with id:%+v, path:%s", mountPath, storageId, localPath)
}

func SetAdminPassword(pwd string) {
	admin, err := op.GetAdmin()
	if err != nil {
		utils.Log.Errorf("failed get admin user: %+v", err)
		return
	}
	//设置管理员密码
	admin.SetPassword(pwd)
	if err := op.UpdateUser(admin); err != nil {
		utils.Log.Errorf("failed update admin user: %+v", err)
		return
	}
	utils.Log.Infof("admin user has been updated:")
	utils.Log.Infof("username: %s", admin.Username)
	utils.Log.Infof("password: %s", pwd)
	cmd.DelAdminCacheOnline()
}

func GetAdminUser() string {
	//获取管理员账户
	admin, err := op.GetAdmin()
	if err != nil {
		utils.Log.Errorf("failed get admin user: %+v", err)
		return "admin"
	}
	return admin.Username
}
