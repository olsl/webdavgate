# 飞牛NAS：不靠 Cloudflare，lucky + DNS TXT 记录也能打通手机 WebDAV（4）

> 本文是「书生意气」NAS 穿透系列的续篇。
>
> - 上上上篇：[飞牛nas通过lucky进行stun穿透实现公网访问](https://cloud.tencent.com/developer/article/2619406)
> - 上上篇：[飞牛nas通过lucky和CF配置公网无端口访问（2）](https://cloud.tencent.com/developer/article/2623438)
> - 上篇：[WebDavGate 手机网关，302 一键搞定（3）](docs/TUTORIAL.md)
>
> 上篇的方案依赖 Cloudflare 302 跳转。本篇介绍 **TXT 直连模式**——
> 连 Cloudflare 都不用，直接从 **DNS TXT 记录**里读 STUN 地址，更简单、更纯粹。

## 一、TXT 模式是什么

思路一句话：**把当前的 STUN 直连地址写进域名的 TXT 记录里，手机网关直接查 DNS 拿地址，不再走任何 HTTP 重定向。**

```
CX文件管理器 ──▶ WebDavGate(手机本地)
                    │
                    │ 1. 查 DNS TXT：nas.example.com → "203.0.113.10:31076"
                    │ 2. 携带 Host: nas.example.com 转发
                    ▼
             lucky 反向代理(按域名路由) ──▶ NAS
```

和 302 模式相比，这个方案有三个好处：

| 对比项 | 302 模式（上篇） | TXT 直连模式（本篇） |
|--------|----------------|-------------------|
| 依赖 Cloudflare | 需要 | **完全不需要** |
| 地址解析方式 | HTTP 302 跳转 | DNS TXT 记录 |
| 端口变化后更新 | lucky 改 CF DNS + 页面规则 | lucky DNSHE 自动改 TXT |
| 支持非 WebDAV 网页 | 主要面向 WebDAV | **任何 HTTP 网页/服务都能代理** |

## 二、前置条件

- 已按[系列第一篇](https://cloud.tencent.com/developer/article/2619406)配置好 lucky STUN 穿透
- lucky 开启了 **DNSHE**（DNS 自动更新）和 **Web 服务**（反向代理）模块
- 一个托管在任意 DNS 服务商的域名（本文以 `example.com` 为例）
- 手机安装 WebDavGate

## 三、第一步：lucky DNSHE 配置通配符 TXT 记录

打开 lucky → **DNSHE** 模块 → 新建/编辑记录：

| 配置项 | 填什么 | 说明 |
|--------|--------|------|
| 记录名 | `*.example.com` | 通配符，任意子域名都能查到这条记录 |
| 记录类型 | `TXT` | 注意是 TXT，不是 A |
| 记录内容 | `{STUN_stun_ADDR}` | lucky 的内置变量，会自动展开成当前 STUN 地址（如 `203.0.113.10:31076`） |
| TTL | 自动 | 即可 |
| 同步开关 | 开启 | 端口变化时自动更新记录 |

【此处可插入截图：lucky DNSHE 编辑记录弹窗，记录名 `*.example.com`、类型 TXT、内容 `{STUN_stun_ADDR}`】

保存后 lucky 会通过域名服务商的 API 自动创建这条 TXT 记录。到你的域名 DNS 管理页面，应该能看到：

| 名称 | 类型 | 内容 | TTL |
|------|------|------|-----|
| * | TXT | "203.0.113.10:31076" | 600 |

【此处可插入截图：域名 DNS 管理面板里的 TXT 记录】

### 验证记录是否生效

在电脑上执行：

```
nslookup -type=TXT nas.example.com
```

返回 `text = "203.0.113.10:31076"` 即成功。

> 说明：
> - `{STUN_stun_ADDR}` 展开的是 `IP:端口` 形式，网关会自动补全成 `http://IP:端口`
> - WebDavGate 还支持其它写法：`stun=http://IP:端口`、`ip=IP&port=端口`、`http://IP:端口`、`域名:端口`，任选

## 四、第二步：lucky Web 服务添加反代规则

lucky → **Web 服务** → 你的监听规则下添加子规则：

| 配置项 | 填什么 |
|--------|--------|
| 规则名称 | `nas`（随便起） |
| 类型 | 反向代理 |
| 域名 | `nas.example.com` |
| 目标 | `http://192.168.1.100:5088`（你 NAS 的内网 WebDAV 地址） |

【此处可插入截图：lucky Web 服务反代规则列表，nas.example.com → http://192.168.1.100:5088】

**关键点：这里的域名必须和后面 APP 里填的完全一致**——lucky 靠请求里的 Host 头匹配路由规则，两边不一致就会 404。

一条 STUN 穿透 + 一个通配符 TXT + 多条反代规则，还能同时代理 NAS、Alist、路由器后台等多个服务，每个服务一个域名。

## 五、第三步：手机网关 APP 配置

打开 WebDavGate → ➕ 添加/编辑节点：

| 配置项 | 填什么 | 说明 |
|--------|--------|------|
| 名称 | 随便起，如 `NAS-TXT` | 仅用于展示 |
| 地址发现方式 | **TXT 直连** | 本篇主角 |
| 地址 | `nas.example.com` | **只填域名**，不要带 `http://` |
| 本地端口 | `8889` | 默认 8888，多节点并存时换一个 |

【此处可插入截图：WebDavGate 节点编辑界面，选中 TXT 直连】

> 为什么不带 `http://`？因为这个地址有两个用途：① 作为 DNS 查询的域名；② 作为转发请求的 Host 头。两者都只认纯域名。
> （就算误填了 `http://` 前缀，APP 也会自动去掉，但建议规范填写。）

回到主页点击**「启动网关」**，然后在 CX 里这样配：

```
服务器：127.0.0.1:8889      ← 对应节点端口
账号/密码：NAS 的账号密码
```

## 六、验证

浏览一次目录后，打开 APP 的日志页面，应该能看到三步链路：

```
DnsTxtResolver: TXT resolved: nas.example.com → http://203.0.113.10:31076
Forwarder: TXT mode: forwarding to http://203.0.113.10:31076/dav/
Forwarder: TXT response: 207
```

- TXT resolved：DNS 解析成功（已内置阿里/腾讯/Google 多 DoH 源，国内直连秒回）
- Host 头自动携带 `nas.example.com`，lucky 按域名路由
- 207 = WebDAV 目录列表成功

## 七、常见问题

**Q：访问返回 404？**
九成是域名对不上：APP 里填的地址和 lucky 反代规则里的域名必须一字不差。lucky 匹配不到域名就落到默认规则，直接 404。

**Q：STUN 端口变了要手动改吗？**
不用。lucky DNSHE 会自动把新的 `IP:新端口` 写进 TXT 记录，网关查询结果缓存 2 分钟，最多两分钟后自动跟上。

**Q：TXT 记录支持通配符吗？**
支持。Cloudflare、多数国内注册商都允许 TXT 记录的名称填 `*`。用通配符的好处是：以后在 lucky 里新增 `alist.example.com` 反代规则时，TXT 记录一条都不用加，APP 里直接建个新节点填这个域名即可。

**Q：手机查 DNS 用的是哪家服务？**
内置三个 DoH 源依次尝试：阿里（dns.alidns.com）→ 腾讯（doh.pub）→ Google（dns.google）。前两个国内直连，任何网络环境都能查。

**Q：和 302 模式怎么选？**
有 Cloudflare、想要"隐藏端口 + 固定 HTTPS 入口"→ 302 模式；不想依赖 CF、追求极简直连、或要代理非 WebDAV 的网页服务 → TXT 模式。两者可以在 APP 里并存，一个节点一种模式，互不干扰。

## 八、结语

至此这个系列凑齐了三种玩法：

1. **lucky STUN 穿透**——打通公网
2. **CF 302 跳转**——固定域名 + 隐藏端口
3. **TXT 直连**——零第三方依赖，DNS 即配置

lucky 负责"打通"，WebDavGate 负责"用好"。NAS 重启、端口漂移、域名扩充，手机上全程无感。

> 下载地址：（发布后补充）
