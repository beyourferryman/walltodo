# 桌面待办 WallTodo

把待办事项直接写在手机壁纸上——亮屏即见，忘不掉。

一个极简的 Android 壁纸待办工具：输入要做的事，App 把它渲染成一张"待办壁纸"，同时设置为桌面和锁屏壁纸；事情做完后，一键还原原来的壁纸。

## 它解决什么问题

- 待办 App 收藏了一堆，还是想不起来打开 → 待办写进壁纸，亮屏就是提醒，成本为零
- 手动改壁纸：截图→P字→设置，太麻烦 → 这里一步完成，且**自动备份原壁纸**，完成后一键还原
- 超级壁纸/动态壁纸读不到原图 → 可从相册选一张图作为原壁纸，还原一直用它，不受系统限制影响
- 锁屏提醒最有效：抬手亮屏的一瞬间正好看到，不用解锁

## 功能

- 输入待办文本，一键设为桌面 + 锁屏壁纸
- 原壁纸持久保存：自动获取当前壁纸或手动选图，反复"设置-还原"始终用同一张，仅手动更换时才变化
- 手动选图内置裁剪页：裁剪框锁定屏幕物理比例，拖动/双指缩放，EXIF 方向自动转正
- 保存当前壁纸到相册（Pictures/桌面待办）
- 纯本地运行：无网络权限、无广告、不联网、不收集任何数据

## 下载

到 [Releases](../../releases) 页面下载 APK 直接安装。

> 应用未上架应用商店，安装时系统会提示"未知来源"，允许安装即可。

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `SET_WALLPAPER` | 设置壁纸，安装即授予 |
| `MANAGE_EXTERNAL_STORAGE`（所有文件访问） | Android 14+ 读取真实壁纸原图的唯一途径，用于备份与还原原壁纸，需在系统设置中手动开启 |
| `READ_EXTERNAL_STORAGE`（Android 10-） | 老设备读取壁纸；Android 13+ 不再申请 |
| `WRITE_EXTERNAL_STORAGE`（Android 9-） | 老设备保存壁纸到相册；Android 10+ 免权限 |

App 不读取、不上传壁纸以外的任何文件。

## 自己构建

```bash
git clone <本仓库地址>
cd WallTodo
gradlew.bat assembleDebug
```

产物在 `app/build/outputs/apk/debug/`。需要 JDK 17、Android SDK（compileSdk 36）。也可以直接用 Android Studio 打开项目构建。

## 技术要点

- Kotlin + ViewBinding，双 Activity 极简架构（主界面 + 裁剪页）
- Android 14+ 无法直接拿到当前壁纸的原图（`WallpaperManager.getDrawable()` 拿到的是低分辨率缩略图），本项目通过 `MANAGE_EXTERNAL_STORAGE` + `WallpaperManager.getWallpaperFile()` 读取真实壁纸文件实现无损备份与还原
- 裁剪页用 `DisplayManager` + `Display.Mode` 取显示面板物理分辨率（折叠屏取最大形态），裁剪框比例与其一致，所见即所得
- 系统照片选择器（`GetContent`）返回的 URI 跨 Activity 使用不可靠，图片立即拷贝到 App 私有缓存后再处理
- 待办文字渲染在备份原图之上，保证还原时零损失

## License

MIT
