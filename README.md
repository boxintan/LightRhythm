# LightRhythm
Android-based LED flicker banding detection and photo restoration

## App 内检查更新

安装包含此功能的版本后，点击页面右上方的 **检查更新 → 下载新版 → 安装更新**。
第一次安装时，手机可能要求允许“光律”安装应用；完成后会返回并打开系统安装确认界面。
无需电脑、数据线或打开浏览器找下载链接。下载可取消，失败可重试；照片处理不受检查更新影响。

目前更新信息和 APK 仍托管于 GitHub，部分网络仍需要 VPN。App 内更新不会绕过网络访问限制，也不会静默安装。
现有旧版本还没有此入口，需要先通过原下载链接安装一次新版本，以后再从 App 内更新。

### 更新源与校验

CI 会从实际 APK 读取版本信息，发布 `update.json`、APK 和 SHA-256 校验文件；manifest 指向同一发布的固定版本 APK，避免混用版本。
App 只提供更高版本，下载到应用私有缓存，校验长度、SHA-256、应用包名、版本号和当前安装签名之后才开放安装。
安装权限只在用户点击安装时请求。更新不会自动卸载应用或覆盖用户的原始照片。

后续如有国内服务器，可通过 Gradle 参数 `-PupdateMetadataUrl=https://你的域名/update.json` 指定 HTTPS 更新信息地址。
JSON 格式参见 Release 附件 `update.json`；把其中的 `apkUrl` 改为国内 APK 地址，并保持实际版本、大小和 SHA-256 一致。
请使用固定团队签名。仅换信息地址、但仍让 `apkUrl` 指向 GitHub，不能解决手机访问 GitHub 的问题。

## 用手机更新 App（无需连接电脑）

[点击下载最新团队测试版 APK](https://github.com/boxintan/LightRhythm/releases/latest/download/LightRhythm.apk)

1. 代码通过 PR 合并到 `main` 后，GitHub 自动编译并运行单元测试。
2. 等仓库 Actions 中的 `Android build and phone download` 成功后，用手机打开上面的固定链接。
3. 下载 `LightRhythm.apk`，打开文件并确认“更新”。首次通过浏览器安装时，按手机提示允许该浏览器安装应用。

不用插数据线，也不用在自己的电脑上打包。新版本需要确认安装，不会在后台静默更新。
若构建失败，链接继续提供上一次成功发布的版本。每次更新会提高版本号，使用相同的团队调试签名。
本仓库尚未发布成功的首次构建之前，下载链接会暂时不可用。

如果手机上旧版由其他成员的电脑独立签名，首次换用团队测试版可能提示签名不一致；此时需卸载旧版后安装一次（会清除旧版的本地应用数据）。之后可以直接覆盖更新。

## 合并前用手机试预览版

[光律预览版下载页](https://github.com/boxintan/LightRhythm/blob/team-preview-download/README.md)

1. 在自己的功能分支提交代码，发 PR 到 `main` 供评审。需要真机测试时，将同一功能分支合入共享的 `team-preview` 分支并推送，或另开一个以 `team-preview` 为目标的 PR。
2. 等 GitHub Actions 的 `Android build and phone download` 成功，用手机打开上面的下载页。可以在页面核对当前预览的提交和构建记录。
3. 首次安装后，手机上会多出「光律预览版」，可与 `main` 版同时使用。以后从预览版内点击「检查更新」，或重新打开固定下载页。
4. 验证通过后，把原来指向 `main` 的功能 PR 正常合并。团队用 `team-preview` 作为共享候选版，同一时间下载页提供一个包。

预览下载页和版本信息由 Actions 更新，首次发布成功后链接生效。手机下载仍需能访问 GitHub。

### 自动打包维护说明

- `.github/workflows/android.yml`：PR 只检查编译和测试；`main` 发布原来的固定下载包，`team-preview` 使用独立包名并发布为 GitHub 预发行版。
- `LIGHTRHYTHM_DEBUG_KEYSTORE_B64`：仓库 Actions Secret，保存固定的 Android 调试签名文件，不能放进源码。维护者换密钥后，旧版可能无法直接覆盖更新。
- `ciVersionCode` 和 `ciVersionName`：CI 传入的版本信息；本地构建仍默认为 `1 / 1.0`。
- GitHub Release 明确标记为团队测试版，保留每次成功发布的包；固定下载链接始终指向最新成功版本。
- 当前为开发调试包；正式比赛交付或上架前应另行配置正式发布签名。
