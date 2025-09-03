# 代理认证修复详解

## 问题背景

原项目 [sealedtx/java-youtube-downloader](https://github.com/sealedtx/java-youtube-downloader) 在使用 HTTP 代理认证时经常出现 407 错误，无法正常工作。

## 核心问题分析

### 1. 代理认证断链问题

**原版本请求链路:**
```
用户请求 → RequestVideoInfo.proxy(host,port,user,pass)  ✅ 设置代理
    ↓
ParserImpl.parseVideo() → parseVideoAndroid()           ❌ 代理信息丢失
    ↓  
new RequestWebpage() → DownloaderImpl                   ❌ 无代理认证
    ↓
HttpURLConnection                                       ❌ 407错误
```

**问题根源:**
- `ParserImpl` 中创建子请求时没有传递原始请求的代理设置
- `parseVideoAndroid()` 和 `parseVideoWeb()` 方法重新创建 `RequestWebpage` 对象
- 新创建的请求对象丢失了用户设置的代理认证信息

### 2. HttpURLConnection 技术限制

**HTTPS 代理隧道认证问题:**
- HttpURLConnection 在处理 HTTPS 代理隧道认证时存在已知限制
- 407 Proxy Authentication Required 响应处理不完善
- 无法正确发送 `Proxy-Authorization` 头部

## 解决方案

### 1. 方法签名改造 - 代理传递

#### 修改前
```java
// 原版本 - 无法传递代理信息
private VideoInfo parseVideo(String videoId, YoutubeCallback<VideoInfo> callback, ClientType client)
private VideoInfo parseVideoAndroid(String videoId, YoutubeCallback<VideoInfo> callback, ClientType client)  
private VideoInfo parseVideoWeb(String videoId, YoutubeCallback<VideoInfo> callback)
private JSONObject downloadPlayerConfig(String videoId, YoutubeCallback<VideoInfo> callback)
```

#### 修改后
```java
// 增强版 - 传递完整请求对象
private VideoInfo parseVideo(String videoId, YoutubeCallback<VideoInfo> callback, ClientType client, RequestVideoInfo originalRequest)
private VideoInfo parseVideoAndroid(String videoId, YoutubeCallback<VideoInfo> callback, ClientType client, RequestVideoInfo originalRequest)
private VideoInfo parseVideoWeb(String videoId, YoutubeCallback<VideoInfo> callback, RequestVideoInfo originalRequest)  
private JSONObject downloadPlayerConfig(String videoId, YoutubeCallback<VideoInfo> callback, RequestVideoInfo originalRequest)
```

### 2. 代理认证信息提取与重设

#### 核心逻辑
```java
// 在每个子请求中重新设置代理认证
if (originalRequest.getProxy() != null) {
    InetSocketAddress proxyAddress = (InetSocketAddress) originalRequest.getProxy().address();
    String host = proxyAddress.getHostString();
    int port = proxyAddress.getPort();
    
    // 从全局 ProxyAuthenticator 获取认证信息
    ProxyAuthenticator auth = ProxyAuthenticator.getDefault();
    if (auth != null) {
        // 通过模拟认证请求获取用户名密码
        PasswordAuthentication credentials = Authenticator.requestPasswordAuthentication(
            host, null, port, "http", "Proxy Authentication", "basic");
        if (credentials != null) {
            // 重新设置完整的代理认证
            request.proxy(host, port, credentials.getUserName(), new String(credentials.getPassword()));
        } else {
            request.proxy(host, port);
        }
    }
}
```

#### 技术细节
1. **代理地址解析**: 从 `Proxy` 对象中提取主机名和端口
2. **认证信息获取**: 通过 `ProxyAuthenticator` 和 `requestPasswordAuthentication()` 获取存储的用户名密码
3. **重新设置代理**: 调用 `request.proxy(host, port, username, password)` 重新触发认证设置

### 3. OkHttp 集成 - HTTP 客户端替换

#### DownloaderImpl.java 完全重写
- **替换**: `HttpURLConnection` → `OkHttp 4.12.0`
- **优势**: OkHttp 的 `proxyAuthenticator` 正确处理 HTTPS 代理隧道认证
- **实现**: 创建 `createHttpClient()` 方法，支持代理认证配置

```java
// OkHttp 代理认证配置
if (config.getProxy() != null) {
    builder.proxy(config.getProxy());
    ProxyAuthenticator auth = ProxyAuthenticator.getDefault();
    if (auth != null) {
        okhttp3.Authenticator proxyAuthenticator = (route, response) -> {
            PasswordAuthentication credentials = auth.getPasswordAuthentication();
            if (credentials != null) {
                String credential = Credentials.basic(credentials.getUserName(), new String(credentials.getPassword()));
                return response.request().newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build();
            }
            return null;
        };
        builder.proxyAuthenticator(proxyAuthenticator);
    }
}
```

## 修复效果

### 修复前
```
[步骤1] 获取 visitorData... ✅ 成功（直接OkHttp请求）
[步骤2] 使用 visitorData 请求视频信息... ❌ 407错误（ParserImpl断链）
```

### 修复后  
```
[步骤1] 获取 visitorData... ✅ 成功（直接OkHttp请求）
[步骤2] 使用 visitorData 请求视频信息... ✅ 成功（ParserImpl代理传递）
[步骤3] 开始下载... ✅ 成功（DownloaderImpl OkHttp）
```

## 技术要点

### 1. 代理认证生命周期
1. **设置阶段**: `request.proxy(host, port, username, password)`
   - 设置 `Proxy` 对象
   - 调用 `ProxyAuthenticator.addAuthentication()` 存储认证信息

2. **传递阶段**: ParserImpl 方法参数传递 `originalRequest`
   - 所有子方法都接收原始请求对象
   - 保持代理配置的完整性

3. **重建阶段**: 子请求中重新设置代理
   - 提取代理地址信息
   - 获取存储的认证信息  
   - 重新调用 `proxy()` 方法设置认证

4. **执行阶段**: OkHttp 处理实际认证
   - `proxyAuthenticator` 响应 407 质询
   - 发送 `Proxy-Authorization` 头部
   - 建立 HTTPS 隧道连接

### 2. 关键修复点

**修复点1: parseVideoAndroid() - API 请求**
```java
// YouTube API 请求: https://www.youtube.com/youtubei/v1/player
String url = BASE_API_URL + "/player?key=" + ANDROID_APIKEY;
RequestWebpage request = new RequestWebpage(url, "POST", clientBody);
// + 代理认证传递逻辑
```

**修复点2: downloadPlayerConfig() - 网页请求**
```java
// YouTube 网页请求: https://www.youtube.com/watch?v=videoId  
String htmlUrl = "https://www.youtube.com/watch?v=" + videoId;
RequestWebpage request = new RequestWebpage(htmlUrl);
// + 代理认证传递逻辑
```

### 3. 为什么 OkHttp 是必要的

**HttpURLConnection 的限制:**
- HTTPS 代理需要建立 CONNECT 隧道
- 隧道建立过程中的认证处理复杂
- Java 的全局 `Authenticator` 机制在某些场景下失效

**OkHttp 的优势:**
- 专门的 `proxyAuthenticator` 接口
- 正确处理 407 认证质询
- 自动重试认证失败的请求
- 更好的 HTTPS 隧道支持

## 测试验证

### 验证代理认证链路
```bash
# 测试命令
java YoutubeVideoParser us.decodo.com 10001 spw31iyoeh password123

# 预期结果
✅ [步骤1] 获取 visitorData 成功        # OkHttp 直接请求
✅ [步骤2] 获取视频信息成功             # ParserImpl + DownloaderImpl 链路
✅ [步骤3] 下载成功                    # DownloaderImpl OkHttp
```

### 支持的代理场景
1. **无代理**: 直接连接
2. **无认证代理**: `proxy(host, port)`  
3. **认证代理**: `proxy(host, port, username, password)`

## 总结

这次修复的核心是解决了**代理认证信息在请求链中的传递断链问题**，通过：

1. **方法签名改造**: 所有解析方法都传递原始请求对象
2. **认证信息提取**: 从全局 ProxyAuthenticator 获取存储的认证信息
3. **代理重设机制**: 在每个子请求中重新设置完整的代理认证
4. **HTTP客户端升级**: 使用 OkHttp 替代 HttpURLConnection

最终实现了完整、稳定的 HTTP 代理认证支持，解决了长期困扰的 407 认证错误问题。