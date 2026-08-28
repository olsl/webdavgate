[OPEN] Debug Session: body-parse

## 症状
- HTTP PROPFIND 请求的 body（XML）未被读取
- Body 被当成 HTTP header 解析
- 错误: `IllegalArgumentException: Unexpected char 0x20 at 5 in header name: <?xml version="1.0"...`

## 证据
```
readRequest: request line = PROPFIND /dav/ HTTP/1.1
readRequest: parsed PROPFIND /dav/ (headers=10, body=false)
handler error: Unexpected char 0x20 at 5 in header name: <?xml version="1.0"...
```

## 假设列表
1. Content-Length header 未被正确识别（大小写、冒号后空格等格式问题）
2. PROPFIND 请求使用 chunked 传输编码，readChunked 未正确实现
3. readRequest 的 header 解析循环提前中断
4. body 已被读取但 headers 构建逻辑错误地包含了 body 行

## 下一步
- 加日志打印所有 headers 和 Content-Length 值
- 验证 body 读取逻辑
