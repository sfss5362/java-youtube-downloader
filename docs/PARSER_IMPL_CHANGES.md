# ParserImpl 代理认证修复详解

## 修改的四个核心方法

在 `ParserImpl.java` 中，为了修复代理认证断链问题，我们修改了以下四个关键方法的签名：

### 🔧 **方法签名变化对比**

#### 1. **parseVideo() - 主解析入口方法**
```java
// 原版本
private VideoInfo parseVideo(String videoId, YoutubeCallback<VideoInfo> callback, ClientType client)

// 增强版 - 新增 originalRequest 参数
private VideoInfo parseVideo(String videoId, YoutubeCallback<VideoInfo> callback, ClientType client, RequestVideoInfo originalRequest)
```
- **职责**: 解析协调器，决定使用 Android API 还是 Web 解析
- **修改原因**: 需要将原始请求的代理信息传递给子方法

#### 2. **parseVideoAndroid() - Android API 解析方法**
```java
// 原版本
private VideoInfo parseVideoAndroid(String videoId, YoutubeCallback<VideoInfo> callback, ClientType client)

// 增强版 - 新增 originalRequest 参数
private VideoInfo parseVideoAndroid(String videoId, YoutubeCallback<VideoInfo> callback, ClientType client, RequestVideoInfo originalRequest)
```
- **职责**: 通过 YouTube Android API 获取视频信息
- **关键请求**: `https://www.youtube.com/youtubei/v1/player?key=ANDROID_APIKEY`
- **修改原因**: API 请求需要代理认证才能成功

#### 3. **parseVideoWeb() - Web 页面解析方法**
```java
// 原版本  
private VideoInfo parseVideoWeb(String videoId, YoutubeCallback<VideoInfo> callback)

// 增强版 - 新增 originalRequest 参数
private VideoInfo parseVideoWeb(String videoId, YoutubeCallback<VideoInfo> callback, RequestVideoInfo originalRequest)
```
- **职责**: 通过 YouTube 网页解析获取视频信息（Android API 失败时的备选方案）
- **依赖**: 调用 `downloadPlayerConfig()` 方法
- **修改原因**: 需要传递代理信息给 `downloadPlayerConfig()`

#### 4. **downloadPlayerConfig() - 播放器配置下载方法**
```java
// 原版本
private JSONObject downloadPlayerConfig(String videoId, YoutubeCallback<VideoInfo> callback)

// 增强版 - 新增 originalRequest 参数  
private JSONObject downloadPlayerConfig(String videoId, YoutubeCallback<VideoInfo> callback, RequestVideoInfo originalRequest)
```
- **职责**: 下载 YouTube 播放器配置页面，提取播放器参数
- **关键请求**: `https://www.youtube.com/watch?v=videoId`
- **修改原因**: 网页请求必须通过代理才能成功

## 🔗 **调用链修复详解**

### 问题诊断: 代理认证断链位置

**原版本的问题调用链:**
```
用户请求: RequestVideoInfo.proxy("host", 8080, "user", "pass") ✅ 设置代理
    ↓
ParserImpl.parseVideo(videoId, callback, client) ❌ 未传递代理
    ↓
parseVideoAndroid(videoId, callback, client) ❌ 创建新RequestWebpage无代理
    ↓
new RequestWebpage(apiUrl, "POST", body) ❌ 代理认证信息丢失
    ↓
DownloaderImpl.downloadWebpage() → HttpURLConnection ❌ 407错误
```

**问题根源分析:**
1. `parseVideo()` 方法没有接收原始的 `RequestVideoInfo` 对象
2. `parseVideoAndroid()` 重新创建 `RequestWebpage`，丢失代理设置
3. `downloadPlayerConfig()` 同样创建新请求，无代理信息
4. HttpURLConnection 无法处理 HTTPS 代理隧道认证

### 修复后的完整调用链

**增强版的完整调用链:**
```
用户请求: RequestVideoInfo.proxy("host", 8080, "user", "pass") ✅ 设置代理
    ↓
ParserImpl.parseVideo(videoId, callback, client, originalRequest) ✅ 传递原始请求
    ↓
parseVideoAndroid(videoId, callback, client, originalRequest) ✅ 接收原始请求
    ↓
提取代理信息 → request.proxy(host, port, user, pass) ✅ 重新设置完整代理
    ↓
DownloaderImpl.downloadWebpage() → OkHttp.proxyAuthenticator ✅ 407认证成功
```

## 💡 **每个方法中的代理传递实现**

### 统一的代理认证传递代码

在四个方法中都添加了相同的代理处理逻辑：

```java
// 在每个修改的方法中添加的代理传递代码
if (originalRequest.getProxy() != null) {
    InetSocketAddress proxyAddress = (InetSocketAddress) originalRequest.getProxy().address();
    String host = proxyAddress.getHostString();
    int port = proxyAddress.getPort();
    
    // 从全局 ProxyAuthenticator 获取认证信息
    com.github.kiulian.downloader.downloader.proxy.ProxyAuthenticator auth = 
        com.github.kiulian.downloader.downloader.proxy.ProxyAuthenticator.getDefault();
    if (auth != null) {
        // 通过模拟认证请求获取用户名密码
        java.net.PasswordAuthentication credentials = null;
        try {
            java.net.Authenticator.setDefault(auth);
            credentials = java.net.Authenticator.requestPasswordAuthentication(
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
        // 设置无认证代理
        request.proxy(host, port);
    }
}
```

### 关键技术点解析

#### 1. **代理信息提取**
```java
InetSocketAddress proxyAddress = (InetSocketAddress) originalRequest.getProxy().address();
String host = proxyAddress.getHostString();  // 获取代理主机
int port = proxyAddress.getPort();           // 获取代理端口
```

#### 2. **认证信息获取**
```java
// 从全局 ProxyAuthenticator 中提取认证信息
ProxyAuthenticator auth = ProxyAuthenticator.getDefault();
PasswordAuthentication credentials = Authenticator.requestPasswordAuthentication(
    host, null, port, "http", "Proxy Authentication", "basic");
```

#### 3. **代理重新设置**
```java
// 重新调用 proxy() 方法，触发完整的代理认证设置
request.proxy(host, port, credentials.getUserName(), new String(credentials.getPassword()));
```

## 🎯 **每个方法的具体应用场景**

### parseVideoAndroid() - YouTube API 请求
```java
String url = BASE_API_URL + "/player?key=" + ANDROID_APIKEY;
RequestWebpage request = new RequestWebpage(url, "POST", clientBody)
    .header("Content-Type", "application/json");

// + 代理认证传递逻辑
// 确保对 https://www.youtube.com/youtubei/v1/player 的 API 请求通过代理认证
```

### downloadPlayerConfig() - YouTube 网页请求
```java
String htmlUrl = "https://www.youtube.com/watch?v=" + videoId;
RequestWebpage request = new RequestWebpage(htmlUrl);

// + 代理认证传递逻辑  
// 确保对 YouTube 网页的请求通过代理认证
```

## 📊 **修复效果验证**

### 修复前后对比

**修复前:**
```
✅ [步骤1] 获取 visitorData 成功        # 直接OkHttp请求，有代理认证
❌ [步骤2] 视频信息请求失败 HTTP 407     # ParserImpl断链，无代理认证
```

**修复后:**
```
✅ [步骤1] 获取 visitorData 成功        # 直接OkHttp请求，有代理认证  
✅ [步骤2] 获取视频信息成功             # ParserImpl完整传递，有代理认证
✅ [步骤3] 视频下载成功                 # DownloaderImpl OkHttp，有代理认证
```

## 🚀 **技术突破意义**

这四个方法的修改实现了：

1. **代理认证完整性**: 确保用户设置的代理认证在整个解析链中不丢失
2. **请求可靠性**: 所有 YouTube API 和网页请求都能通过代理认证
3. **向后兼容**: 保持原有 API 接口不变，只在内部传递代理信息
4. **错误消除**: 彻底解决了 407 Proxy Authentication Required 错误

这是解决原项目代理认证问题的**关键技术突破**！