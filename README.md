# Aurora - 无障碍电子书阅读器

专为视障用户设计的 Android 电子书阅读器，支持 EPUB、TXT、MOBI、AZW、PDF、Markdown、FB2 等主流格式。

## 核心特性

- **完全无障碍** — TalkBack 全程可用，读屏双击任意段落弹出阅读菜单
- **精确位置记忆** — 退出后重新打开，读屏焦点精准落在上次阅读的段落
- **多格式支持** — EPUB/PDF（Readium 引擎）、TXT/MOBI/Markdown/FB2（本地引擎）
- **阅读菜单** — 目录、书签、批注、搜索、阅读进度，点击正文随时调出
- **树状目录** — 层级折叠展开，支持三层目录结构
- **TXT 编辑器** — 原生编码检测，支持 UTF-8/GBK 等编码的编辑和保存
- **书架管理** — 浏览导入、排序、搜索，一键获取电子书

## 技术栈

- Kotlin + Gradle KTS
- Android SDK 35
- Readium Kotlin Toolkit (EPUB/PDF)
- Room + KSP
- Coil (图片)
- ViewBinding + DataBinding

## 构建

```bash
# 需要 Android Studio + JDK 21 + SDK 35
./gradlew assembleRelease
```

## APK

预编译 APK 见 [apk/](./apk/) 目录。

## 包名

`com.nous.aurora`

## 许可

GNU General Public License v3.0
