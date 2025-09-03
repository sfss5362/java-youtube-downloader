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

#### 修改的四个核心方法

**方法1: parseVideo() - 主解析入口**
```java
// 原版本
private VideoInfo parseVideo(String videoId, YoutubeCallback<VideoInfo> callback, ClientType client)

// 增强版 - 新增 originalRequest 参数
private VideoInfo parseVideo(String videoId, YoutubeCallback<VideoInfo> callback, ClientType client, RequestVideoInfo originalRequest)
```

**方法2: parseVideoAndroid() - Android API 解析**  
```java
// 原版本
private VideoInfo parseVideoAndroid(String videoId, YoutubeCallback<VideoInfo> callback, ClientType client)

// 增强版 - 新增 originalRequest 参数
private VideoInfo parseVideoAndroid(String videoId, YoutubeCallback<VideoInfo> callback, ClientType client, RequestVideoInfo originalRequest)
```

**方法3: parseVideoWeb() - Web 页面解析**
```java
// 原版本  
private VideoInfo parseVideoWeb(String videoId, YoutubeCallback<VideoInfo> callback)

// 增强版 - 新增 originalRequest 参数
private VideoInfo parseVideoWeb(String videoId, YoutubeCallback<VideoInfo> callback, RequestVideoInfo originalRequest)
```

**方法4: downloadPlayerConfig() - 播放器配置下载**
```java
// 原版本
private JSONObject downloadPlayerConfig(String videoId, YoutubeCallback<VideoInfo> callback)

// 增强版 - 新增 originalRequest 参数  
private JSONObject downloadPlayerConfig(String videoId, YoutubeCallback<VideoInfo> callback, RequestVideoInfo originalRequest)
```

#### 方法职责说明

| 方法 | 职责 | 关键请求 | 失败影响 |
|------|------|----------|----------|
| parseVideo() | 解析协调器 | - | 整个解析流程失败 |
| parseVideoAndroid() | Android API解析 | `youtubei/v1/player` | 降级到Web解析 |
| parseVideoWeb() | Web页面解析 | `youtube.com/watch` | 完全解析失败 |
| downloadPlayerConfig() | 配置下载 | `youtube.com/watch` | 无法获取播放器配置 |

#### 修改前后调用链对比

**修改前的断链:**
```
parseVideo() 
├── parseVideoAndroid() ❌ 代理信息丢失
└── parseVideoWeb() 
    └── downloadPlayerConfig() ❌ 代理信息丢失
```

**修改后的完整链:**
```
parseVideo(originalRequest) 
├── parseVideoAndroid(originalRequest) ✅ 代理传递
└── parseVideoWeb(originalRequest) 
    └── downloadPlayerConfig(originalRequest) ✅ 代理传递
```

### 2. 代理认证信息提取与重设

#### 统一的代理处理逻辑

每个方法都添加了相同的代理认证传递代码块：

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
        PasswordAuthentication credentials = null;
        try {
            Authenticator.setDefault(auth);
            credentials = Authenticator.requestPasswordAuthentication(
                host, null, port, "http", "Proxy Authentication", "basic");
        } catch (Exception e) {
            // 忽略异常
        }
        
        if (credentials != null) {
            // 重新设置完整的代理认证
            request.proxy(host, port, credentials.getUserName(), new String(credentials.getPassword()));
        } else {
            // 设置无认证代理
            request.proxy(host, port);
        }
    } else {
        request.proxy(host, port);
    }
}
```

#### 具体应用示例

**parseVideoAndroid() 中的应用:**
```java
// 创建 YouTube API 请求
RequestWebpage request = new RequestWebpage(url, "POST", clientBody)
    .header("Content-Type", "application/json");

// + 上述代理认证传递逻辑
// 确保 YouTube API 请求带有正确的代理认证
```

**downloadPlayerConfig() 中的应用:**
```java
// 创建 YouTube 网页请求  
RequestWebpage request = new RequestWebpage(htmlUrl);

// + 上述代理认证传递逻辑
// 确保网页解析请求带有正确的代理认证
```

#### 技术细节说明

1. **代理地址提取**: 
   - 从 `Proxy` 对象中提取 `InetSocketAddress`
   - 获取主机名和端口号

2. **认证信息获取**:
   - 通过全局 `ProxyAuthenticator.getDefault()` 获取认证器
   - 使用 `requestPasswordAuthentication()` 模拟认证请求
   - 提取存储的用户名和密码

3. **代理重设**:
   - 调用 `request.proxy(host, port, username, password)` 
   - 触发 `ProxyAuthenticator.addAuthentication()` 重新存储认证
   - 确保子请求具有完整的代理配置
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