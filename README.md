# LightRhythm
Android-based LED flicker banding detection and photo restoration

## 用手机更新 App（无需连接电脑）

[点击下载最新团队测试版 APK](https://github.com/boxintan/LightRhythm/releases/latest/download/LightRhythm.apk)

1. 代码通过 PR 合并到 `main` 后，GitHub 自动编译并运行单元测试。
2. 等仓库 Actions 中的 `Android build and phone download` 成功后，用手机打开上面的固定链接。
3. 下载 `LightRhythm.apk`，打开文件并确认“更新”。首次通过浏览器安装时，按手机提示允许该浏览器安装应用。

不用插数据线，也不用在自己的电脑上打包。新版本需要确认安装，不会在后台静默更新。
若构建失败，链接继续提供上一次成功发布的版本。每次更新会提高版本号，使用相同的团队调试签名。
本仓库尚未发布成功的首次构建之前，下载链接会暂时不可用。

如果手机上旧版由其他成员的电脑独立签名，首次换用团队测试版可能提示签名不一致；此时需卸载旧版后安装一次（会清除旧版的本地应用数据）。之后可以直接覆盖更新。

### 自动打包维护说明

- `.github/workflows/android.yml`：PR 只检查编译和测试；只有 `main` 的成功构建才发布手机安装包。
- `LIGHTRHYTHM_DEBUG_KEYSTORE_B64`：仓库 Actions Secret，保存固定的 Android 调试签名文件，不能放进源码。维护者换密钥后，旧版可能无法直接覆盖更新。
- `ciVersionCode` 和 `ciVersionName`：CI 传入的版本信息；本地构建仍默认为 `1 / 1.0`。
- GitHub Release 明确标记为团队测试版，保留每次成功发布的包；固定下载链接始终指向最新成功版本。
- 当前为开发调试包；正式比赛交付或上架前应另行配置正式发布签名。
