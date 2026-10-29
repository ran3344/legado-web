<div align="center">

# Legado 修改版

**在 [LegadoTeam/legado](https://github.com/LegadoTeam/legado) 基础上的个人改造版**

WebDAV 书库双向同步 · 多端共享书库 · Web 阅读器重构 · 本地书导出修复

</div>

> 上游功能全部保留，本仓库只做增量改造。
> 想了解原版请访问 [上游仓库](https://github.com/LegadoTeam/legado)；本 README 只记录**我们改了什么、为什么改、怎么用**。
> 更细的用法、排障见 [Wiki](https://127.0.0.1:3000/localadmin/legado/wiki)。

---

## 目录

- [一、这个版本改了什么](#一这个版本改了什么)
- [二、WebDAV 书库双向同步](#二webdav-书库双向同步)
- [三、共享书库（多端主从）](#三共享书库多端主从)
- [四、Web 阅读器重构](#四web-阅读器重构)
- [五、本地书导出修复](#五本地书导出修复)
- [六、其他修正](#六其他修正)
- [七、构建](#七构建)
- [八、与上游保持同步](#八与上游保持同步)
- [九、已知限制](#九已知限制)
- [致谢](#致谢)

---

## 一、这个版本改了什么

| # | 模块 | 上游情况 | 本分支 |
|---|------|----------|--------|
| 1 | WebDAV 书库同步 | 只有整体备份/恢复一个压缩包，手动触发 | **书籍文件级双向同步**：导入即上传、删除即删除、云端补齐、日志自动上传 |
| 2 | 多端共享书库 | 无 | 一台主设备当书库，多台从设备共用书架/目录/正文/进度 |
| 3 | Web 阅读器 | 基础分栏，章节边界跳页 / 空白页 / 末页丢失 | 翻页·滚动·点击三模式重构，覆盖式上下栏，设置面板，末页 100% |
| 4 | 本地书导出 | 章节表为空时直接写书头收尾 → 导出只剩书头 | 章节表为空自动重新解析；一章都取不到则报错并清理残缺文件 |
| 5 | 杂项 | — | 静态资源 MIME 补全、未知路由返回 404、minSdk 26、默认关闭自动更新检查 |

相对上游 merge-base（`a7d37f170`）：**12 个提交 / 148 个文件 / +19362 −1913 行**。

### 提交列表

```
682bf073d chore(update): 默认关闭自动检查更新
bcab80c5d feat(web-reader): 阅读设置顶部显示书名，点击可改名
ac1f456be fix(web-reader): 分栏翻页补齐上下留白，避免末行被视口底边裁切
1f8a6e26b feat(web-shelf): 列表视图、书名清洗与亚麻纸主题
be9d670ca feat(web-reader): 设置面板折叠分组、自定义字体与页边距滑杆
ec8eb07a9 fix(web-reader): 设置改动即时重排，页边距改由单一来源控制
22bfede21 fix: preserve WebDAV book identity, sync logs and improve web reading
231ff9b82 test: 合并后修正断言（syncAction 口径 / 条件上传范围）并保留导入命名
17d30a444 wip: 合并上游前的本地改动存档
7052e9e48 fix(export): 修复本地书籍导出只剩书头的问题
40ec5553c feat(web): 重构 Web 阅读器翻页与进度，修复章节边界问题
```

---

## 二、WebDAV 书库双向同步

### 解决的问题

原版 WebDAV 只能手动「备份 / 恢复」一个整包。实际使用中会出现：

- 换设备 / 重装后，云端有书、本地没有，需要整包恢复覆盖本地，风险大；
- 本地删掉的书，云端文件永远留着；
- 手机拍脑袋想不起来上次同步是什么时候。

本分支把同步粒度**下沉到单本书的文件级**，并且是增量的、可回退的。

### 配置入口

`设置 → 备份与恢复 → WebDAV 设置`

| 项 | 说明 |
|----|------|
| WebDAV 地址 | 例如 `https://dav.example.com/remote.php/dav/files/user/` |
| 账号 / 密码 | Basic Auth |
| 子目录 | 可选，多账号隔离用 |
| 设备名称 | 日志按设备分目录；留空用设备型号 |

### 云端目录结构

```
<WebDAV 根>/
├── books/                 # 书籍文件，扁平存放，按文件名去重
│   ├── 三体.epub
│   └── 明朝那些事儿.txt
└── logs/
    ├── <设备A>/
    │   ├── appLog.log
    │   └── crash/
    └── <设备B>/
```

### 五个开关

| 设置项 | 默认 | 作用 |
|--------|------|------|
| 导入后自动上传 | 开 | 本地导入书籍后立刻上传到 `books/` |
| 删除时同步删除远程文件 | 开 | 删除本地书籍时删除云端同名文件（**真删，不保留**） |
| 书籍双向同步 | 开 | 定时对账：云端有本地没有 → 下载；本地有云端没有 → 上传 |
| 恢复缺失的本地书 | 关 | 只做「缺失补齐」，不上传不删除，最保守的档位 |
| 日志同步到 WebDAV | 开 | 应用日志 + 崩溃日志上传到 `logs/<设备>/`，需先在「其他设置」开启「记录日志」 |

### 去重、冲突、删除规则

- **去重口径**：只认**文件名**（含扩展名）。云端 `books/` 是扁平结构，不做子目录分层。
- **冲突裁决**：同名文件比较**修改时间**，谁新谁赢；不做内容哈希比对。
- **超长文件名**：按字节截断，保留扩展名并追加短哈希，避免 WebDAV 服务端文件名超限。
- **删除**：真删，不做回收站 / 软删除。**只有用户主动删除**才会推送远程删除，同步过程不会误删。
- **离线补偿**：删除失败（远端离线）时写入待删队列 `webDavBookPendingDeletes`，下次同步补做，直到成功。
- **周期**：`App.kt` 中每 5 分钟执行一次 `startPeriodicSync()`；书架菜单也可手动触发。
- **落地位置**：同步下来的书存到**应用私有目录**，不再出现「请选择文件夹 / 没有权限访问」。

### 进度同步

阅读进度 JSON 增加 `bookKey`（书库相对路径）与 `bookUrl` 双键，解决「同名不同书」和「同名同书跨设备」的进度串号。

### 涉及文件

| 文件 | 变更 |
|------|------|
| `app/src/main/java/io/legado/app/help/AppWebDav.kt` | +684，核心同步逻辑 |
| `app/src/main/java/io/legado/app/model/remote/RemoteBookWebDav.kt` | +172 |
| `app/src/main/java/io/legado/app/model/remote/WebDavLogUpload.kt` | 新增 |
| `app/src/main/java/io/legado/app/constant/PreferKey.kt` | 新增 7 个偏好键 |
| `app/src/main/res/xml/pref_config_backup.xml` | 新增 5 个开关 |

主要函数：`upConfig`、`autoUploadImportedBooks`、`deleteRemoteBook`、`syncLocalBooks`、`syncLocalBooksAsync`、`syncLocalBooksManually`、`startPeriodicSync`、`syncLogs`。

---

## 三、共享书库（多端主从）

### 解决的问题

局域网里 10 台设备想共用一份书库：一台主设备存文件，其余设备只读取和记录进度，不重复占空间。

### 结构

```
主设备：局域网共享程序（HTTP）→ /getBookshelf /getChapterList /getBookContent / progress
   ↑
从设备 A/B/C…：Legado 把主设备当「共享书库」数据源 + 书架镜像
```

### 行为

- 启动时 `App.kt` 调用 `SharedLibrary.syncLibraryAsync()`：
  1. 拉取主设备书架 → 补齐本地没有的书（书源名 `共享书库`）
  2. 同步目录 `tocSynced`
  3. 上报本地进度 `pushed`
  4. 同步删除 `removed`
- 返回 `SharedSyncReport(synced, removed, tocSynced, pushed, message)`，界面上给一行结果提示。
- 进度双向：本地读完 `pushProgress`，主设备进度更新则拉回。
- 删除：`pushDelete` 通知主设备。

### 配置

`sharedLibraryEnabled` / `sharedLibraryUrl` / `sharedLibraryToken` / `sharedLibraryLastSyncTime`

### 涉及文件

- `app/src/main/java/io/legado/app/model/shared/SharedLibrary.kt`（新增 190 行）
- `app/src/main/java/io/legado/app/model/shared/SharedLibraryApi.kt`（新增）
- `app/src/main/java/io/legado/app/model/shared/SharedLibraryMirror.kt`（新增）

> 这是本分支独有功能，上游没有，合并上游时注意别被覆盖。

---

## 四、Web 阅读器重构

### 打开方式

App 内开启 Web 服务（设置 → 其他设置 → Web 服务），浏览器访问 `http://<手机IP>:1122/`。

### 修掉的坑

| 现象 | 根因 | 修复 |
|------|------|------|
| 第一章 13/14 再翻变成第二章 1/21，14/14 永远看不到 | 分栏模式下末页被判成「没有剩余空间」，直接甩到下一章 | 章末补齐空白列，第 14/14 页正常出现，末页进度 100%，再甩动才进下一章 |
| 章末出现空白页（先空白、再翻才是下一页） | 视口上下留白未计入分栏高度，末行被底边裁切 | 分栏翻页补齐上下留白 |
| 8/8 时进度条的圆没顶满 | 进度按「可用列数」计算 | 末页归一到 100% |
| 改设置必须整页刷新才生效 | 重排入口分散，字体/边距多个来源互相覆盖 | 设置改动即时重排，页边距由单一来源控制 |
| 点「配置点击区域」直接弹窗，翻不了页 | 点击区域按钮与翻页热区冲突 | 点击区域独立弹窗 |

### 三个阅读方式

- **上下滚动**（连续）：补章 / 裁章时补偿滚动位置，`reSegmentText.ts` 重新分段，`readerPosition.ts` 记忆位置。
- **左右翻页**：CSS 分栏 + 甩动翻页。
- **左右点击**：点左 / 右区域翻页。

### 覆盖式上下栏（对齐 App 内阅读）

- 顶部 / 底部栏是**覆盖层**，默认隐藏；
- 点击正文显示，再点空白处隐藏；
- 显示 / 隐藏**不触发正文重新排版**，正文位置不跳；
- 呼出工具栏后仍可左右/上下滑动翻页；
- 底部常驻一行小字**全书百分比**进度。

### 设置面板

折叠分组：基础样式 / 版式（页边距滑杆）/ 阅读方式与点击区域；支持选择**系统字体**。

### 书架

- 网格 / 列表视图切换
- 书名清洗：去掉 `[sxsy.org]` 之类推广标签，全角【转半角 [
- 亚麻纸主题 `#E6E6DD`
- 内置 14 章 demo 书（`features/demo/demoBook.ts`），没导书也能直接体验阅读器

### 涉及文件

| 文件 | 说明 |
|------|------|
| `modules/web/src/views/BookChapter.vue` | 3204 行改动，阅读器主体 |
| `modules/web/src/views/BookShelf.vue` | 书架视图与书名清洗 |
| `modules/web/src/features/reader/` | 新增 5 个文件（翻页窗口、位置、重分段等） |
| `modules/web/src/vendor/foliate/` | vendored foliate |
| `modules/web/e2e/` | Playwright 用例 `bookshelf.spec.ts` / `reader.spec.ts` |
| `modules/web/tests/chapterWindow.test.js` | 源码级单测 |

### 前端构建与测试

```bash
cd modules/web
npm install
npm run build          # 产物输出到 app/src/main/assets/web/vue/
npx playwright test    # e2e
```

---

## 五、本地书导出修复

### 现象

导出的 txt **只有第一页 / 只有书头**，后面全空；本地导出和 WebDAV 导出都一样。图片类中断也是同一处。

### 根因

`ExportBookService` 依赖书籍的 `BookChapter` 章节表。本地导入的书如果**从没打开过**，章节表是空的；旧逻辑遇到空章节表直接跳过解析，把书头写完就收尾 → 文件残缺。同时章节计数把书头也算进 `chapterNum`，导致「看起来导了几章」实际没正文。

### 修复

1. `ExportChapterList.shouldRefreshChapterList(chapterCount, localModified)`：章节表为空 → 强制重新解析目录（即使 `localModified` 没变）。
2. 只有**真正取到章节正文**才 `chapterNum++`，书头不计入。
3. 一章正文都没取到 → 报错并清理残缺文件，不再静默产出空书。

### 涉及文件

- `app/src/main/java/io/legado/app/service/ExportBookService.kt`
- `app/src/main/java/io/legado/app/service/ExportChapterContent.kt`
- `app/src/main/java/io/legado/app/service/ExportChapterList.kt`（新增）
- 测试：`ExportChapterListTest`、`ExportTxtChapterCountTest`

---

## 六、其他修正

| 位置 | 改动 |
|------|------|
| `app/src/main/java/io/legado/app/web/HttpServer.kt` | 删除永远走不到的 `/getBookContent` 分支；未知 POST 路由返回 404（原先是 200 + `null`） |
| `app/src/main/java/io/legado/app/web/utils/AssetsWeb.kt` | 补全静态资源 MIME，避免字体/css 被当 octet-stream 下发 |
| `app/build.gradle` | `minSdk 23 → 26`；`versionCode` 改为基于 git 提交数（base `9639f02` + 37540） |
| `AppConfig.kt` / `pref_config_other.xml` | 自动检查更新默认**关闭**（修改版签名非官方，检查会误报官方新版本） |
| `AppDatabase` | 升到 migration 112 |

---

## 七、构建

```bash
# 桌面 Web 模块（产物会进 app/src/main/assets/web/vue/）
cd modules/web && npm install && npm run build

# Android
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/app/debug/app-app-debug.apk
```

环境：JDK 17+、Android SDK 36、Node 18+。

---

## 八、与上游保持同步

本仓库配了三个远端：

| 远端 | 地址 | 用途 |
|------|------|------|
| `origin` | `https://github.com/LegadoTeam/legado` | 上游原始仓库，只读 |
| `gitea` | `http://127.0.0.1:3000/localadmin/legado` | 本地 Gitea，本分支主线（默认 push 目标） |
| `github` | `https://github.com/ran3344/legado-web` | 个人 GitHub fork，对外发布用 |

合并上游的标准流程：

```bash
git fetch origin
git merge origin/master        # 或指定 tag
git push gitea main
```

合并冲突时优先保留本分支的自有实现，重点检查：

`AppWebDav.kt`、`model/shared/*`、`model/remote/*WebDav*`、`service/Export*`、`modules/web/src/features/reader/*`、`modules/web/src/views/BookChapter.vue`。

---

## 九、已知限制

- WebDAV 冲突只按**修改时间**裁决，不看内容哈希；两边同时改同一本书时，慢的一方会被覆盖（这是刻意选择的简单策略）。
- `books/` 是扁平目录，同一本书改名后会被当成两本。
- 共享书库依赖局域网共享程序在线，主设备离线时从设备只能读本地缓存。
- Web 阅读器是浏览器实现，排版细节无法做到与 App 内阅读像素级一致。

---

## 致谢

本仓库 99% 的代码来自 [LegadoTeam/legado](https://github.com/LegadoTeam/legado) 及其贡献者，感谢上游团队。

> 感谢 [PiPio 中转站](https://pipio.io/offer/miaogongzi) 对项目的赞助支持。

## Grateful-感谢 [![](https://img.shields.io/badge/-Grateful-F5F5F5.svg)](#Grateful-感谢-)

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
