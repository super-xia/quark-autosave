# 夸克自动转存

一个面向老年用户的安卓应用，粘贴夸克网盘分享链接 → 一键自动转存到自己的夸克网盘。界面由 **Jetpack Compose + Material3** 编写。

## 功能特性

- **粘贴分享链接**：主页粘贴夸克分享链接，每行一个，支持批量转存
- **Cookie 登录**：设置页粘贴夸克网页 Cookie（登录 [pan.quark.cn](https://pan.quark.cn) 后按 F12 复制），自动验证并显示昵称
- **目标文件夹**：可设置默认转存目录（留空 = 网盘根目录），不存在的目录自动创建
- **重名处理**：与目标文件夹已有同名文件/文件夹时，强制重新保存并自动追加后缀 `(1)(2)(3)…`
- **整份转存**：单文件夹整体移动、分散文件直接移动，并支持 `#/list/share/...` 子目录链接（只转该子目录）
- **转存进度**：转存时显示进度条、百分比与实时日志

## 技术实现

- **界面**：Jetpack Compose + Material3，纯 Kotlin，浅蓝主色调
- **构建**：Gradle 8.9 + JDK 17 + Android Gradle Plugin 8.4.2，`targetSdk 34 / minSdk 26`
- **网络/API**：`QuarkApi.java`（Java HttpURLConnection + org.json）——**API 调用逻辑移植自开源仓库 [Cp0204/quark-auto-save](https://github.com/Cp0204/quark-auto-save)**（其 Python 版 `quark_auto_save.py`），包含手机端签名（`drive-m.quark.cn` + `kps/sign/vcode` 签名参数 + 手机 UA）以规避风控，以及分享详情解析、转存、重命名、移动等接口。

## 界面

夸克自动转存的主界面包含两个页面：

- **主页**：顶部标题栏（含设置齿轮入口）+「粘贴分享链接」说明 + 分享链接输入框 +「开始转存」按钮 + 转存进度卡（进度条、百分比、实时日志）
- **设置页**：夸克账号卡片（Cookie 输入 + 登录/切换账号）+ 转存目标卡片（目标文件夹）+ 保存设置按钮

## 构建

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
./gradlew :app:assembleRelease --no-daemon
```

签名使用 `apksigner`（`keystore` 需要自行配置，本仓库不含密钥）。

## 安装

- 直接安装 `夸克自动转存.apk`（`app/build/outputs/apk/release/`），需允许"未知来源"
- 覆盖安装请确保签名一致

## 使用说明

1. 打开应用 → 点右上角齿轮进入设置
2. 粘贴夸克网页 Cookie → 登录；设置目标文件夹 → 保存
3. 回到主页粘贴分享链接（每行一个）→ 点「开始转存」

## 许可证

本界面与构建代码为个人项目。**API 网络逻辑移植自 [Cp0204/quark-auto-save](https://github.com/Cp0204/quark-auto-save)**，请同时遵守该上游仓库的开源许可。