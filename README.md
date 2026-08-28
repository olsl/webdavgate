# WebDavGate

**安卓本机 WebDAV 反向代理网关 —— 让不支持 302/307 重定向的 WebDAV 客户端（CX 文件管理器等）无缝使用 lucky STUN 穿透 / Cloudflare 动态跳转的 NAS**

## 这是什么

用 lucky/natmap 做 STUN 打洞的用户会遇到这样的困境：

| 直接填 CF 跳转域名 | 手动改填 STUN 直连地址 |
|---|---|
| 多数客户端不跟随 302/307，浏览都打不开 | 能用了，但 NAS/LUCKY 一重启端口就变，又得查端口改配置 |
| 个别客户端能浏览 | 上传失败（客户端不会把请求体重发到新地址） |

WebDavGate 安装在你的安卓手机上，监听 `127.0.0.1:8888`，替你的 App 消化所有跳转：

```
CX/Ott/ES 等 App ──▶ WebDavGate (127.0.0.1:8888) ──▶ NAS 直连端口
   只配固定域名            · 首次请求自动学习 302 目标
                           · 缓存直连地址（TTL 2 分钟）
                           · 端口漂移后秒级自适应
```

## 特性

- **302/301/303/307/308 全支持** — 浏览、上传、下载不再手动折腾
- **动态端口自适应** — NAS 重启、STUN 端口漂移后自动重新学习，约 3~5 秒恢复
- **流式转发** — 上传下载全流式处理，大文件不吃内存，实测速度接近直连
- **写操作修正** — MOVE/COPY 的 `Destination` 头自动改写为目标源，覆盖上传/重命名不再 502
- **跨域清理** — 跳转到其他域时清理 Authorization 头，兼容 Alist/OSS 302 直链下载
- **多节点** — 不同上游可映射到不同本地端口并存
- **传输日志面板** — 方便排查问题
- **前台服务保活** — WakeLock/WifiLock + 电池白名单引导，后台不被冻结

## 快速开始

1. 从 [Releases](../../releases) 下载 APK 安装
2. 添加节点，填入你在 CF/客户端里用的**固定入口域名**（如 `https://dav.example.com/dav`），本地端口默认 8888
3. 启动网关（首次建议允许「忽略电池优化」）
4. 在 CX 文件管理器中添加 WebDAV：服务器填 `127.0.0.1:8888`，账号密码照旧

从此无论端口怎么漂移，手机里再也不用改任何配置。

> 详细图文教程：
> - [302 重定向模式](docs/TUTORIAL.md)（依赖 Cloudflare）
> - [TXT 直连模式](docs/TUTORIAL-TXT.md)（零第三方依赖，配合任意支持 TXT 记录更新的域名服务商，免费收费均可）
>
> 配套服务端教程：
> - [飞牛 NAS 通过 lucky 进行 STUN 穿透实现公网访问](https://cloud.tencent.com/developer/article/2619406)
> - [飞牛 NAS 通过 lucky 和 CF 配置公网无端口访问](https://cloud.tencent.com/developer/article/2623438)

## 进阶玩法：电视盒子 / 家庭网关共享

APP 可运行在任何 Android 7.0+ 设备上，两种部署形态：

**① 设备本机自用** — 盒子装 WebDavGate，本机 Kodi/NPlayer/MX Player 的 WebDAV 源填 `http://127.0.0.1:8888`。
从此电视盒子上那些不支持 302 重定向的播放器也能直挂 NAS 了。

**② 一台装网关，全家共享** — 内网任意一台常开机的安卓设备（老手机/电视盒子最合适）跑网关，
其余设备全部照填它的局域网地址：

```
NAS ◀──lucky直连── 手机A (WebDavGate :8888) ──▶ 内网所有设备
                                       ├─ 手机B/CX   → http://192.168.x.x:8888
                                       ├─ 电视盒子/Kodi
                                       └─ PC 软件
```

> 安全说明：上游访问仍需 NAS 的账号密码；网关仅监听于你的局域网内，公网不可达。

## 工作原理

启动后在本机拉起 NanoHTTPD 服务；每个请求进入后：

1. 若有有效的直连缓存 → 直接转发到 STUN 直连地址
2. 否则访问 CF 入口，捕获 3xx 响应中的 `Location` 学习真实地址并缓存
3. 学习到的新地址在后端返回错误（连接失败/502 等）时立即失效重学
4. 所有方法与请求体原样透传（PROPFIND/PUT/GET/MKCOL/COPY/MOVE/DELETE…）

## 已知适用场景

- lucky / natmap STUN 穿透 + Cloudflare Page Rule 302
- 任何「固定域名 30x 跳转 → 动态地址」的 WebDAV 架构
- Alist/OpenList 302 直链盘的客户端兼容性补救

## 构建

```bash
# Android Studio 直接打开即可编译
./gradlew assembleDebug
```

## License

MIT © 2026 webdavgate contributors
