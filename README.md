Java YouTube 下载器 (增强版)
============

[![](https://jitpack.io/v/sealedtx/java-youtube-downloader.svg)](https://jitpack.io/#sealedtx/java-youtube-downloader)

简单的 Java YouTube 视频元数据解析器。

**增强版本**，具有改进的代理支持、更好的错误处理和 OkHttp 集成。

## 本分支的新功能

此增强版本在[原项目](https://github.com/sealedtx/java-youtube-downloader)基础上包含了多项改进：

### 主要增强功能
- 🚀 **增强代理支持**: 完整的 HTTP 代理认证（用户名/密码）
- 🔧 **OkHttp 集成**: 替换 HttpURLConnection，更好地处理代理认证
- 📊 **改进进度显示**: 单行刷新进度，每秒更新
- 🎯 **直接获取 visitorData**: 优化 API 请求流程
- 🛠️ **更好的错误信息**: 仅显示关键错误信息，不输出完整堆栈
- 📦 **移除 Nutz 依赖**: 替换为 JDK 原生 + FastJSON 实现

### 快速测试使用

包含了用于快速代理测试的测试类：

**示例:**

```bash
# 带认证
java com.github.kiulian.downloader.test.YoutubeVideoParser us.decodo.com 10001 spw31iyoeh password123

# 无认证
java com.github.kiulian.downloader.test.YoutubeVideoParser 127.0.0.1 10808
```

## 使用说明

### 配置
```java
// 使用默认配置初始化下载器
YoutubeDownloader downloader = new YoutubeDownloader();

// 或使用自定义配置
Config config = new Config.Builder()
    .executorService(executorService) // 用于异步请求，默认 Executors.newCachedThreadPool()
    .maxRetries(1) // 失败重试次数，默认 0
    .header("Accept-language", "zh-CN,zh;") // 额外的请求头
    .proxy("192.168.0.1", 2005) // 无认证代理
    .proxy("192.168.0.1", 2005, "用户名", "密码") // 带认证代理
    .build();
YoutubeDownloader downloader = new YoutubeDownloader(config);
```

### 请求
```java
// 每个请求都接受可选参数，会覆盖全局配置
Request request = new Request(...)
        .maxRetries(...) 
        .proxy(...) 
        .header(...)
        .callback(...) // 添加回调用于异步处理
        .async(); // 设为异步请求
```

### 视频信息
```java
String videoId = "abc12345"; // 对应 URL https://www.youtube.com/watch?v=abc12345

// 同步解析
RequestVideoInfo request = new RequestVideoInfo(videoId);
Response<VideoInfo> response = downloader.getVideoInfo(request);
VideoInfo video = response.data();

// 异步解析
RequestVideoInfo request = new RequestVideoInfo(videoId)
        .callback(new YoutubeCallback<VideoInfo>() {
            @Override
            public void onFinished(VideoInfo videoInfo) {
                System.out.println("解析完成");
            }
    
            @Override
            public void onError(Throwable throwable) {
                System.out.println("错误: " + throwable.getMessage());
            }
        })
        .async();

// 视频详情
VideoDetails details = video.details();
System.out.println("标题: " + details.title());
System.out.println("观看次数: " + details.viewCount());
details.thumbnails().forEach(image -> System.out.println("缩略图: " + image));

// 获取包含音频的视频格式
List<VideoWithAudioFormat> videoWithAudioFormats = video.videoWithAudioFormats();
videoWithAudioFormats.forEach(it -> {
    System.out.println(it.audioQuality() + ", " + it.videoQuality() + " : " + it.url());
});

// 获取最佳格式
video.bestVideoWithAudioFormat();
video.bestVideoFormat();
video.bestAudioFormat();
```

### 下载视频
```java
File outputDir = new File("我的视频");
Format format = videoFormats.get(0);

// 同步下载
RequestVideoFileDownload request = new RequestVideoFileDownload(format)
    .saveTo(outputDir) // 默认为 "videos" 目录
    .renameTo("视频文件名") // 默认使用 YouTube 上的视频标题
    .overwriteIfExists(true); // false 时如果文件存在会添加后缀 video(1).mp4
Response<File> response = downloader.downloadVideoFile(request);
File data = response.data();

// 带进度回调的异步下载
RequestVideoFileDownload request = new RequestVideoFileDownload(format)
    .callback(new YoutubeProgressCallback<File>() {
        @Override
        public void onDownloading(int progress) {
            System.out.printf("已下载 %d%%\n", progress);
        }
    
        @Override
        public void onFinished(File videoInfo) {
            System.out.println("完成文件: " + videoInfo);
        }
    
        @Override
        public void onError(Throwable throwable) {
            System.out.println("错误: " + throwable.getLocalizedMessage());
        }
    })
    .async();
```

### 代理认证支持

本增强版本完全支持 HTTP 代理认证：

```java
// 场景1: 无代理
Config config = new Config.Builder().build();

// 场景2: 无认证代理
Config config = new Config.Builder()
    .proxy("127.0.0.1", 10808)
    .build();

// 场景3: 带认证代理
Config config = new Config.Builder()
    .proxy("proxy.example.com", 8080, "username", "password")
    .build();

// 针对单个请求设置代理
RequestVideoInfo request = new RequestVideoInfo(videoId)
    .proxy("proxy.example.com", 8080, "username", "password");
```

### 错误处理改进

增强版本提供更清晰的错误信息：

```java
// 原版本: 显示完整异常堆栈
// java.io.IOException: Server returned HTTP response code: 407...

// 增强版本: 显示关键信息
// HTTP 407 - 代理认证失败
// streamingData not found
// 连接超时
```

## 引用此增强版本

### 通过 JitPack 引用

#### Maven

在 `pom.xml` 中添加 JitPack 仓库：

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>
```

添加依赖：
```xml
<dependency>
  <groupId>com.github.sfss5362</groupId>
  <artifactId>java-youtube-downloader</artifactId>
  <version>v3.3.1-enhanced</version>
</dependency>
```

#### Gradle

在 `build.gradle` 中添加：

```gradle
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

```gradle
dependencies {
    implementation 'com.github.sfss5362:java-youtube-downloader:v3.3.1-enhanced'
}
```

#### JitPack 构建状态

查看构建状态: [![](https://jitpack.io/v/sfss5362/java-youtube-downloader.svg)](https://jitpack.io/#sfss5362/java-youtube-downloader)

如果首次引用，JitPack 会自动构建项目，可能需要几分钟时间。

## 编译安装

### Maven
```bash
# 编译
mvn compile

# 安装到本地仓库（跳过测试）
mvn install -DskipTests
```

### 依赖项

本项目使用以下主要依赖：

```xml
<dependencies>
    <!-- JSON 解析 -->
    <dependency>
        <groupId>com.alibaba</groupId>
        <artifactId>fastjson</artifactId>
        <version>1.2.83</version>
    </dependency>
    
    <!-- HTTP 客户端 -->
    <dependency>
        <groupId>com.squareup.okhttp3</groupId>
        <artifactId>okhttp</artifactId>
        <version>4.12.0</version>
    </dependency>
    
    <!-- 日志 -->
    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
        <version>2.0.9</version>
    </dependency>
</dependencies>
```

## 原项目

基于 [sealedtx/java-youtube-downloader](https://github.com/sealedtx/java-youtube-downloader) 开发