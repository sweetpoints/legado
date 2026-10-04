# Legado 阅读

Legado 是免费的 Android 开源阅读器，支持自定义书源、本地书籍、订阅和网络书架。
本仓库基于 [gedoor/legado](https://github.com/gedoor/legado) 继续开发。

## 主要功能

- 添加书源，搜索和阅读网络书籍，管理书架与阅读记录。
- 导入 TXT、EPUB、PDF 等本地书籍，也可以通过系统分享或“打开方式”导入。
- 添加订阅源，阅读订阅内容。
- 自定义阅读排版、字体、主题，使用音频播放功能。
- 备份和恢复书源、书架及设置。
- 开启应用内的 Web 服务，在浏览器中访问书架和编辑书源。

## 下载与安装

需要 **Android 8.0 或更高版本**。

当前安装包通过 [GitHub 构建页面](https://github.com/sweetpoints/legado/actions/workflows/test.yml) 提供：

1. 登录 GitHub，打开构建页面，选择对应版本。
2. 在页面底部的 Artifacts 中下载 APK 压缩包。
3. 解压后，将 APK 安装到设备。

这些安装包是开发版本，可能存在问题。

不确定设备架构时，选择文件名含 `universal` 的安装包；`arm` 包适用于 ARM 手机和平板。
`release` 和 `releaseA` 是不同包名的版本，可以同时安装，但数据不互通。
本仓库的构建与上游应用商店版本也是不同的发行版本。

更新或更换版本前，建议先在应用内备份。遇到“应用未安装”或签名不一致时，
请确认下载的包名和来源与已安装版本一致；需要卸载旧版本时，请先确认备份可恢复。

## 使用

安装后，可以添加书源搜索书籍，或直接导入设备上的本地书籍。
书源的可用性取决于对应网站与规则；遇到某个书源无法使用时，可以更换书源或检查规则。

使用 Web 书架前，需要在应用内开启 Web 服务，再通过浏览器访问应用显示的地址。

## 问题反馈

请在 [Issues](https://github.com/sweetpoints/legado/issues) 中反馈问题，注明应用版本、
安装包来源、设备型号、Android 版本和复现步骤；有错误提示时附上错误文字或截图。

## 许可证

项目采用 [GNU GPL v3](LICENSE)。

## 致谢

> * org.jsoup:jsoup
> * cn.wanghaomiao:JsoupXpath
> * com.jayway.jsonpath:json-path
> * com.github.gedoor:rhino-android
> * com.squareup.okhttp3:okhttp
> * com.github.bumptech.glide:glide
> * org.nanohttpd:nanohttpd
> * org.nanohttpd:nanohttpd-websocket
> * cn.bingoogolapple:bga-qrcode-zxing
> * com.jaredrummler:colorpicker
> * org.apache.commons:commons-text
> * io.noties.markwon:core
> * io.noties.markwon:image-glide
> * com.hankcs:hanlp
> * com.positiondev.epublib:epublib-core
> * com.github.Moriafly:LyricViewX
> * io.github.rosemoe:editor
