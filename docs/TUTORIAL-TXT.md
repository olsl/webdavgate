# 飞牛NAS：lucky + DNS TXT 记录，手机 WebDAV 无感直连（4）

> 本文是「书生意气」NAS 穿透系列的续篇。
>
> - 前几篇：[STUN 穿透](https://cloud.tencent.com/developer/article/2619406) → [CF 无端口访问](https://cloud.tencent.com/developer/article/2623438) → [手机网关 WebDavGate](docs/TUTORIAL.md)
>
> STUN 穿透的老问题：**公网端口是动态的**，NAS 一重启端口就变。
> 本篇介绍一种极简解法——把直连地址写进 **DNS TXT 记录**，手机自动查、自动跟，全程无感。

## 一、原理

核心思路一句话：**lucky 把当前的 STUN 地址自动写进域名的 TXT 记录，手机网关查一次 DNS 就知道该连哪。**

```
                ┌── lucky DNSHE：端口变了自动更新 TXT 记录 ──┐
                │                                          │
NAS ◀── STUN 穿透 ──▶ 203.0.113.10:31076（动态端口）   TXT: "203.0.113.10:31076"
                              ▲                              ▲
                              │ 2.按解析结果直连               │ 1.查 DNS TXT
                              │                              │
CX ──▶ WebDavGate(手机本地:8889) ────────────────────────────┘
        3.请求带 Host: nas.example.com ──▶ lucky 反代按域名路由 ──▶ NAS
```

涉及三个角色：

| 角色 | 职责 |
|------|------|
| lucky DNSHE | 端口变化时，自动把新的 `IP:端口` 写进 TXT 记录 |
| DNS TXT 记录 | 存放当前真实的 STUN 直连地址 |
| WebDavGate（手机） | 查 TXT 拿地址 → 携带域名直连转发 |

**不需要 Cloudflare，不需要任何第三方中转**，一条 TXT 记录解决端口漂移。

## 二、前置条件

- 已按[系列第一篇](https://cloud.tencent.com/developer/article/2619406)配置好 lucky STUN 穿透
- lucky 开启 **DNSHE** 模块（DNS 自动更新）
- lucky 开启 **Web 服务**模块（反向代理）
- 一个托管在任意 DNS 服务商的域名（本文以 `example.com` 为例）
- 手机安装 [WebDavGate](../README.md)

## 三、第一步：lucky DNSHE 配置通配符 TXT 记录

打开 lucky → **DNSHE** 模块 → 新建记录：

| 配置项 | 填什么 | 说明 |
|--------|--------|------|
| 记录名 | `*.example.com` | 通配符，任意子域名都命中这条记录 |
| 记录类型 | `TXT` | 注意是 TXT，不是 A |
| 记录内容 | `{STUN_stun_ADDR}` | lucky 内置变量，自动展开为当前 STUN 地址 |
| TTL | 自动 | 即可 |
| 同步开关 | 开启 | 端口变化时自动更新 |

【此处插入截图：lucky DNSHE 编辑记录弹窗】

保存后 lucky 通过域名服务商 API 自动创建记录。到域名 DNS 管理面板确认：

| 名称 | 类型 | 内容 |
|------|------|------|
| * | TXT | "203.0.113.10:31076" |

【此处插入截图：域名 DNS 面板中的 TXT 记录】

在电脑上验证（返回 `text = "203.0.113.10:31076"` 即生效）：

```
nslookup -type=TXT nas.example.com
```

> `{STUN_stun_ADDR}` 展开为 `IP:端口` 形式，WebDavGate 会自动补全。
> 另外还支持这些写法：`stun=http://IP:端口`、`ip=IP&port=端口`、`http://IP:端口`、`域名:端口`。

## 四、第二步：lucky Web 服务添加反代规则

lucky → **Web 服务** → 添加子规则：

| 配置项 | 填什么 |
|--------|--------|
| 规则名称 | `nas`（随便起） |
| 类型 | 反向代理 |
| 域名 | `nas.example.com` |
| 目标 | `http://192.168.1.100:5088`（NAS 内网 WebDAV 地址） |

【此处插入截图：lucky Web 服务反代规则列表】

**关键：这个域名必须和第五步 APP 里填的一字不差。** lucky 靠请求的 Host 头匹配路由，对不上就 404。

一套架构还能扩展：NAS、Alist、路由器后台各一条反代规则，各用一个子域名，共享同一条 TXT 记录和同一个 STUN 通道。

## 五、第三步：手机 WebDavGate 配置

打开 APP → ➕ 添加节点：

| 配置项 | 填什么 | 说明 |
|--------|--------|------|
| 名称 | 随便起，如 `NAS-TXT` | 仅用于展示 |
| 地址发现方式 | **TXT 直连** | 本篇主角 |
| 地址 | `nas.example.com` | **只填域名**，不要带 `http://`，必须和第四步反代规则一致 |
| 本地端口 | `8889` | 默认 8888，多节点并存时换一个 |

【此处插入截图：WebDavGate 节点编辑，选中 TXT 直连】

启动网关后，CX 文件管理器里添加 WebDAV：

```
服务器：127.0.0.1:8889
账号/密码：NAS 的账号密码
```

## 六、验证

浏览一次目录，打开 APP 日志页，完整链路一目了然：

```
DnsTxtResolver: TXT resolved: nas.example.com → http://203.0.113.10:31076
Forwarder: TXT mode: forwarding to http://203.0.113.10:31076/dav/
Forwarder: TXT response: 207
```

- **TXT resolved**：DNS 解析成功（内置阿里/腾讯/Google 三个 DoH 源，国内直连秒回）
- **forwarding to**：已按 TXT 记录直连，请求自动携带 `Host: nas.example.com`
- **207**：WebDAV 目录列表成功

## 七、常见问题

**Q：访问返回 404？**
九成是域名对不上——APP 里填的地址和 lucky 反代规则里的域名必须完全一致。lucky 匹配不到 Host 就落到默认规则，直接 404。

**Q：STUN 端口变了要手动改吗？**
不用。lucky DNSHE 自动把新地址写进 TXT，网关缓存 2 分钟自动跟上，全程无感。

**Q：TXT 记录支持通配符吗？**
支持（Cloudflare、主流国内注册商都允许）。用通配符的好处：以后新增 `alist.example.com` 反代规则时，TXT 一条不用加，APP 建个新节点填域名即可。

**Q：为什么地址不能带 `http://`？**
这个地址有两个用途：① DNS 查询的域名；② 转发请求的 Host 头，两者都只认纯域名。（误填了 APP 也会自动去掉，但建议规范填写。）

**Q：手机在哪查的 DNS？**
内置 DoH 依次尝试：阿里（dns.alidns.com）→ 腾讯（doh.pub）→ Google，国内网络直连可用。

## 八、结语

一条通配符 TXT + lucky 自动同步 + 手机网关自动查询，端口怎么漂移都与你无关。手机上从此只记一个域名、一个固定端口。

> 下载地址：（发布后补充）
