package com.github.kiulian.downloader.test;

import com.github.kiulian.downloader.YoutubeDownloader;
import com.github.kiulian.downloader.Config;
import com.github.kiulian.downloader.downloader.client.ClientType;
import com.github.kiulian.downloader.downloader.request.RequestVideoInfo;
import com.github.kiulian.downloader.downloader.response.Response;
import com.github.kiulian.downloader.model.videos.VideoInfo;
import com.github.kiulian.downloader.model.videos.formats.Format;

import java.io.*;
import java.net.*;
import java.util.HashMap;
import java.util.Map;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import okhttp3.*;

import java.util.concurrent.TimeUnit;

public class YoutubeVideoParser {

    public static void main(String[] args) {

        if (args.length < 2 || args.length > 4) {
            System.out.println("用法: java YoutubeVideoParser <proxyHost> <proxyPort> [username] [password]");
            System.out.println("示例: java YoutubeVideoParser us.decodo.com 10001 spw31iyoeh xxxxxxxxxx");
            System.out.println("或者: java YoutubeVideoParser 127.0.0.1 10808");
            return;
        }

        String proxyHost = args[0];
        int proxyPort = Integer.parseInt(args[1]);
        String proxyUser = args.length > 2 ? args[2] : null;
        String proxyPass = args.length > 3 ? args[3] : null;

        testProxy(proxyHost, proxyPort, proxyUser, proxyPass);
    }

    public static void testProxy(String host, int port, String username, String password) {
        System.out.println("=== YouTube下载测试 ===");
        if (username != null && password != null) {
            System.out.println("代理配置: " + host + ":" + port + " (用户: " + username + ")");
        } else {
            System.out.println("代理配置: " + host + ":" + port);
        }
        System.out.println();

        Config config;
        if (username != null && password != null) {
            System.out.println("设置代理认证: " + username + " / " + password);
            config = new Config.Builder()
                    .proxy(host, port, username, password)
                    .maxRetries(1)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .build();
        } else if (host != null && port > 0) {
            System.out.println("设置无认证代理: " + host + ":" + port);
            config = new Config.Builder()
                    .proxy(host, port)
                    .maxRetries(1)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .build();
        } else {
            System.out.println("不使用代理");
            config = new Config.Builder()
                    .maxRetries(1)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .build();
        }

        YoutubeDownloader downloader = new YoutubeDownloader(config);
        String testVideoId = "GDlkCkcIqTs";

        try {
            System.out.println("[步骤1] 获取 visitorData...");
            String newVisitorData = null;

            if (host != null && port > 0) {
                newVisitorData = getVisitorDataFromYouTubeAPI(testVideoId, host, port, username, password);
            } else {
                newVisitorData = getVisitorDataFromYouTubeAPI(testVideoId, null, 0, null, null);
            }

            if (newVisitorData != null) {
                System.out.println("✓ [步骤1] 获取 visitorData 成功");
                System.out.println("visitorData: " + newVisitorData);

                System.out.println("\n[步骤2] 使用 visitorData 请求视频信息...");

                ClientType client = new ClientType(
                        "ANDROID_VR",
                        "1.60.19",
                        ClientType.baseJson(),
                        ClientType.queryParam("context/client/visitorData", newVisitorData),
                        ClientType.queryParam("context/client/androidSdkVersion", "34"),
                        ClientType.queryParam("racyCheckOk", "true"),
                        ClientType.queryParam("contentCheckOk", "true")
                );

                RequestVideoInfo request = new RequestVideoInfo(testVideoId).clientType(client);
                if (username != null && password != null) {
                    request.proxy(host, port, username, password);
                }
                Response<VideoInfo> response = downloader.getVideoInfo(request);

                if (response != null && response.ok()) {
                    VideoInfo video = response.data();
                    System.out.println("✓ [步骤2] 获取视频信息成功!");
                    System.out.println("视频标题: " + video.details().title());
                    System.out.println("可用格式数量: " + video.formats().size());

                    if (!video.formats().isEmpty()) {
                        Format format = video.formats().get(0);
                        System.out.println("\n[步骤3] 开始下载: " + getExtension(format) + " (itag:" + format.itag().id() + ")");
                        System.out.println(format.url());

                        downloadVideoWithOkHttp(format, video.details().title(), host, port, username, password, false);

                    }
                } else {
                    System.out.println("✗ [步骤2] 视频信息请求失败");
                    if (response != null && response.error() != null) {
                        String errorMsg = response.error().getMessage();
                        System.out.println("错误信息: " + extractKeyErrorInfo(errorMsg));
                    }
                }
            } else {
                System.out.println("✗ [步骤1] 无法获取 visitorData");
            }

        } catch (Exception e) {
            System.out.println("✗ 程序异常: " + extractKeyErrorInfo(e.getMessage()));
        }
    }

    /**
     * 使用OkHttp从YouTube API获取visitorData
     */
    private static String getVisitorDataFromYouTubeAPI(String videoId, String proxyHost, int proxyPort, String username, String password) {
        try {
            String apiUrl = "https://www.youtube.com/youtubei/v1/player";

            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("videoId", videoId);

            Map<String, Object> context = new HashMap<>();
            Map<String, Object> client = new HashMap<>();
            client.put("hl", "en");
            client.put("gl", "US");
            client.put("clientName", "WEB");
            client.put("clientVersion", "2.20220918");

            context.put("client", client);
            requestBody.put("context", context);

            // 创建OkHttpClient
            OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS);

            // 配置代理
            if (proxyHost != null && proxyPort > 0) {
                Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
                clientBuilder.proxy(proxy);

                // 如果有用户名密码，设置代理认证
                if (username != null && password != null) {
                    okhttp3.Authenticator proxyAuthenticator = (route, response) -> {
                        String credential = Credentials.basic(username, password);
                        return response.request().newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build();
                    };
                    clientBuilder.proxyAuthenticator(proxyAuthenticator);
                }
            }

            OkHttpClient httpClient = clientBuilder.build();

            // 创建请求
            String jsonBody = JSON.toJSONString(requestBody);
            RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8"));

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build();

            // 发送请求
            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    JSONObject responseJson = JSON.parseObject(responseBody);
                    if (responseJson.containsKey("responseContext")) {
                        JSONObject responseContext = responseJson.getJSONObject("responseContext");
                        if (responseContext.containsKey("visitorData")) {
                            return responseContext.getString("visitorData");
                        }
                    }
                } else {
                    System.out.println("API请求失败: HTTP " + response.code());
                }
            }

            return null;

        } catch (Exception e) {
            System.out.println("获取visitorData异常: " + extractKeyErrorInfo(e.getMessage()));
            return null;
        }
    }

    /**
     * 使用OkHttp下载视频
     */
    public static void downloadVideoWithOkHttp(Format format, String title, String proxyHost, int proxyPort, String username, String password, boolean fullDownload) {
        try {
            String fileName = "test_video_" + format.itag().id() + ".mp4";
            File outputFile = new File(fileName);

            // 创建OkHttpClient
            OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS);

            // 配置代理
            if (proxyHost != null && proxyPort > 0) {
                Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
                clientBuilder.proxy(proxy);

                // 如果有用户名密码，设置代理认证
                if (username != null && password != null) {
                    okhttp3.Authenticator proxyAuthenticator = (route, response) -> {
                        String credential = Credentials.basic(username, password);
                        return response.request().newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build();
                    };
                    clientBuilder.proxyAuthenticator(proxyAuthenticator);
                }
            }

            OkHttpClient httpClient = clientBuilder.build();

            // 创建下载请求
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(format.url())
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build();

            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    long totalSize = response.body().contentLength();
                    if (totalSize > 0) {
                        System.out.println("文件大小: " + (totalSize / 1024 / 1024) + " MB");
                    } else {
                        System.out.println("文件大小: 未知");
                    }

                    if (!fullDownload) {
                        System.out.println("当前是测试下载，只下载1MB");
                    }

                    try (InputStream inputStream = response.body().byteStream();
                         FileOutputStream fos = new FileOutputStream(outputFile)) {

                        byte[] buffer = new byte[8192];
                        long totalDownloaded = 0;
                        int bytesRead;
                        long maxTestSize = 1024 * 1024;
                        long lastProgressTime = System.currentTimeMillis();

                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            if (!fullDownload && totalDownloaded >= maxTestSize) {
                                break;
                            }

                            int bytesToWrite = bytesRead;
                            if (!fullDownload && totalDownloaded + bytesRead > maxTestSize) {
                                bytesToWrite = (int) (maxTestSize - totalDownloaded);
                            }

                            fos.write(buffer, 0, bytesToWrite);
                            totalDownloaded += bytesToWrite;

                            // 显示进度（每秒一次，单行刷新）
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastProgressTime > 1000) {
                                if (totalSize > 0) {
                                    double progress = (double) totalDownloaded / totalSize * 100;
                                    System.out.printf("\r下载进度: %.1f%% (%d/%d MB)",
                                            progress, totalDownloaded / 1024 / 1024, totalSize / 1024 / 1024);
                                } else {
                                    System.out.printf("\r已下载: %d MB", totalDownloaded / 1024 / 1024);
                                }
                                System.out.flush();
                                lastProgressTime = currentTime;
                            }

                            if (!fullDownload && totalDownloaded >= maxTestSize) {
                                break;
                            }
                        }

                        // 显示最终进度
                        if (totalSize > 0) {
                            double finalProgress = (double) totalDownloaded / totalSize * 100;
                            System.out.printf("\r下载进度: %.1f%% (%d/%d MB)",
                                    finalProgress, totalDownloaded / 1024 / 1024, totalSize / 1024 / 1024);
                        } else {
                            System.out.printf("\r已下载: %d MB", totalDownloaded / 1024 / 1024);
                        }

                        if (!fullDownload) {
                            System.out.println("\n✓ 测试下载完成: " + totalDownloaded / 1024 / 1024 + " MB (仅测试1MB)");
                        } else {
                            System.out.println("\n✓ 下载完成: " + totalDownloaded / 1024 / 1024 + " MB");
                        }
                        System.out.println("保存位置: " + outputFile.getAbsolutePath());
                    }

                } else {
                    System.out.println("✗ 下载失败 HTTP " + response.code());
                }

            }

        } catch (Exception e) {
            System.out.println("✗ 下载异常: " + extractKeyErrorInfo(e.getMessage()));
        }
    }

    private static String extractKeyErrorInfo(String errorMessage) {
        if (errorMessage == null) return "Unknown error";

        // 提取HTTP状态码
        if (errorMessage.contains("HTTP ")) {
            int start = errorMessage.indexOf("HTTP ");
            int end = start + 8; // "HTTP " + 3位数字
            if (end <= errorMessage.length()) {
                String httpCode = errorMessage.substring(start, Math.min(end, errorMessage.length()));
                if (httpCode.contains("407")) {
                    return httpCode + " - 代理认证失败";
                } else if (httpCode.contains("403")) {
                    return httpCode + " - 访问被拒绝";
                } else if (httpCode.contains("404")) {
                    return httpCode + " - 资源不存在";
                } else {
                    return httpCode;
                }
            }
        }

        // 提取关键错误信息
        if (errorMessage.contains("playabilityStatus")) {
            int start = errorMessage.indexOf("playabilityStatus");
            int end = errorMessage.indexOf("}", start) + 1;
            if (end > start) {
                return errorMessage.substring(start, end);
            }
        }

        if (errorMessage.contains("streamingData not found")) {
            return "streamingData not found";
        }

        if (errorMessage.contains("Connection refused")) {
            return "Connection refused (代理连接失败)";
        }

        if (errorMessage.contains("UnknownHostException")) {
            return "DNS解析失败";
        }

        if (errorMessage.contains("SocketTimeoutException")) {
            return "连接超时";
        }

        if (errorMessage.contains("Unable to tunnel through proxy")) {
            return "代理隧道失败 - 认证问题";
        }

        // 返回原始错误信息的前100个字符
        return errorMessage.length() > 100 ? errorMessage.substring(0, 100) + "..." : errorMessage;
    }

    private static String getExtension(Format format) {
        if (format == null) {
            return "unknown";
        }
        try {
            if (format.extension() != null && format.extension().value() != null) {
                return format.extension().value();
            }
            // 根据itag推测格式
            if (format.itag() != null) {
                int itag = format.itag().id();
                if (itag == 18 || itag == 22) return "mp4";
                if (itag >= 243 && itag <= 248) return "webm";
                if (itag >= 133 && itag <= 160) return "mp4";
            }
        } catch (Exception e) {
            // 忽略异常
        }
        return "unknown";
    }
}