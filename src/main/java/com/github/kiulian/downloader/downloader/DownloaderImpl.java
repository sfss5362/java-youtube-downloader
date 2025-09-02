package com.github.kiulian.downloader.downloader;

import com.github.kiulian.downloader.Config;
import com.github.kiulian.downloader.YoutubeException;
import com.github.kiulian.downloader.downloader.request.*;
import com.github.kiulian.downloader.downloader.response.ResponseImpl;
import com.github.kiulian.downloader.model.videos.formats.Format;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

import okhttp3.*;
import java.util.concurrent.TimeUnit;

import static com.github.kiulian.downloader.model.Utils.closeSilently;

public class DownloaderImpl implements Downloader {

    private static final int BUFFER_SIZE = 4096;
    private static final int PART_LENGTH = 2 * 1024 * 1024;

    private final Config config;
    private final OkHttpClient httpClient;

    public DownloaderImpl(Config config) {
        this.config = config;
        this.httpClient = createHttpClient(config);
    }

    /**
     * 创建OkHttpClient，支持代理认证
     */
    private OkHttpClient createHttpClient(Config config) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS);

        if (config.getProxy() != null) {
            builder.proxy(config.getProxy());
            
            // 检查是否有代理认证
            com.github.kiulian.downloader.downloader.proxy.ProxyAuthenticator auth = 
                com.github.kiulian.downloader.downloader.proxy.ProxyAuthenticator.getDefault();
            if (auth != null) {
                okhttp3.Authenticator proxyAuthenticator = (route, response) -> {
                    java.net.PasswordAuthentication credentials = auth.getPasswordAuthentication();
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

        return builder.build();
    }

    @Override
    public ResponseImpl<String> downloadWebpage(RequestWebpage request) {
        if (request.isAsync()) {
            ExecutorService executorService = config.getExecutorService();
            Future<String> result = executorService.submit(() -> download(request));
            return ResponseImpl.fromFuture(result);
        }
        try {
            String result = download(request);
            return ResponseImpl.from(result);
        } catch (IOException | YoutubeException e) {
            return ResponseImpl.error(e);
        }
    }

    private String download(RequestWebpage request) throws IOException, YoutubeException {
        String downloadUrl = request.getDownloadUrl();
        Map<String, String> headers = request.getHeaders();
        YoutubeCallback<String> callback = request.getCallback();
        int maxRetries = request.getMaxRetries() != null ? request.getMaxRetries() : config.getMaxRetries();
        Proxy requestProxy = request.getProxy();

        IOException exception = null;
        String result = null;
        int attempts = maxRetries + 1;
        
        do {
            try {
                result = downloadWithOkHttp(downloadUrl, headers, requestProxy, config.isCompressionEnabled(), request.getMethod(), request.getBody());
                exception = null; // reset on success
            } catch (IOException e) {
                exception = e;
                attempts--;
            }
        } while (exception != null && attempts > 0);

        if (exception != null) {
            if (callback != null) {
                callback.onError(exception);
            }
            throw exception;
        }

        if (callback != null) {
            callback.onFinished(result);
        }
        return result;
    }

    /**
     * 使用OkHttp下载网页内容
     */
    private String downloadWithOkHttp(String downloadUrl, Map<String, String> headers, Proxy requestProxy, boolean acceptCompression, String method, String body) throws IOException {
        OkHttpClient client = httpClient;
        
        // 如果请求指定了不同的代理，创建新的客户端
        if (requestProxy != null && !requestProxy.equals(config.getProxy())) {
            client = createHttpClientWithProxy(requestProxy);
        }

        okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
            .url(downloadUrl);

        // 设置请求方法和body
        if ("POST".equalsIgnoreCase(method) && body != null) {
            RequestBody requestBody = RequestBody.create(body, MediaType.parse("application/json; charset=utf-8"));
            requestBuilder.post(requestBody);
        } else {
            requestBuilder.get();
        }

        // 添加配置的headers
        for (Map.Entry<String, String> entry : config.getHeaders().entrySet()) {
            requestBuilder.header(entry.getKey(), entry.getValue());
        }
        
        if (acceptCompression) {
            requestBuilder.header("Accept-Encoding", "gzip");
        }
        
        // 添加请求特定的headers
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.header(entry.getKey(), entry.getValue());
            }
        }

        okhttp3.Request okRequest = requestBuilder.build();
        
        try (okhttp3.Response response = client.newCall(okRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to download: HTTP " + response.code());
            }
            
            ResponseBody responseBody = response.body();
            if (responseBody == null || responseBody.contentLength() == 0) {
                throw new IOException("Failed to download: Response is empty");
            }

            InputStream in = responseBody.byteStream();
            if (acceptCompression && "gzip".equals(response.header("content-encoding"))) {
                in = new GZIPInputStream(in);
            }
            
            StringBuilder result = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String inputLine;
                while ((inputLine = br.readLine()) != null) {
                    result.append(inputLine).append('\n');
                }
            }
            
            return result.toString();
        }
    }

    /**
     * 为特定代理创建OkHttpClient
     */
    private OkHttpClient createHttpClientWithProxy(Proxy proxy) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .proxy(proxy);

        // 检查是否有代理认证
        com.github.kiulian.downloader.downloader.proxy.ProxyAuthenticator auth = 
            com.github.kiulian.downloader.downloader.proxy.ProxyAuthenticator.getDefault();
        if (auth != null) {
            okhttp3.Authenticator proxyAuthenticator = (route, response) -> {
                java.net.PasswordAuthentication credentials = auth.getPasswordAuthentication();
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

        return builder.build();
    }

    @Override
    public ResponseImpl<File> downloadVideoAsFile(RequestVideoFileDownload request) {
        if (request.isAsync()) {
            ExecutorService executorService = config.getExecutorService();
            Future<File> result = executorService.submit(() -> download(request));
            return ResponseImpl.fromFuture(result);
        }
        try {
            File result = download(request);
            return ResponseImpl.from(result);
        } catch (IOException e) {
            return ResponseImpl.error(e);
        }
    }

    @Override
    public ResponseImpl<Void> downloadVideoAsStream(RequestVideoStreamDownload request) {
        if (request.isAsync()) {
            ExecutorService executorService = config.getExecutorService();
            Future<Void> result = executorService.submit(() -> download(request));
            return ResponseImpl.fromFuture(result);
        }
        try {
            download(request);
            return ResponseImpl.from(null);
        } catch (IOException e) {
            return ResponseImpl.error(e);
        }
    }

    private File download(RequestVideoFileDownload request) throws IOException {
        Format format = request.getFormat();
        File outputFile = request.getOutputFile();
        YoutubeCallback<File> callback = request.getCallback();
        OutputStream os = new FileOutputStream(outputFile);

        download(request, format, os);
        if (callback != null) {
            callback.onFinished(outputFile);
        }
        return outputFile;
    }

    private Void download(RequestVideoStreamDownload request) throws IOException {
        Format format = request.getFormat();
        YoutubeCallback<Void> callback = request.getCallback();
        OutputStream os = request.getOutputStream();

        download(request, format, os);
        if (callback != null) {
            callback.onFinished(null);
        }
        return null;
    }

    private void download(com.github.kiulian.downloader.downloader.request.Request<?, ?> request, Format format, OutputStream os) throws IOException {
        Map<String, String> headers = request.getHeaders();
        YoutubeCallback<?> callback = request.getCallback();
        int maxRetries = request.getMaxRetries() != null ? request.getMaxRetries() : config.getMaxRetries();
        Proxy proxy = request.getProxy();

        IOException exception;
        do {
            try {
                if (format.isAdaptive() && format.contentLength() != null) {
                    downloadByPart(format, os, headers, proxy, callback);
                } else {
                    downloadStraight(format, os, headers, proxy, callback);
                }
                // reset error in case of successful retry
                exception = null;
            } catch (IOException e) {
                exception = e;
            }
        } while (exception != null && maxRetries-- > 0);

        closeSilently(os);

        if (exception != null) {
            if (callback != null) {
                callback.onError(exception);
            }
            throw exception;
        }
    }

    // Downloads the format in one single request
    private void downloadStraight(Format format, OutputStream os, Map<String, String> headers, Proxy proxy, YoutubeCallback<?> callback) throws IOException {
        OkHttpClient client = httpClient;
        
        // 如果请求指定了不同的代理，创建新的客户端
        if (proxy != null && !proxy.equals(config.getProxy())) {
            client = createHttpClientWithProxy(proxy);
        }

        okhttp3.Request request = new okhttp3.Request.Builder()
            .url(format.url())
            .build();

        // 添加配置的headers
        okhttp3.Request.Builder requestBuilder = request.newBuilder();
        for (Map.Entry<String, String> entry : config.getHeaders().entrySet()) {
            requestBuilder.header(entry.getKey(), entry.getValue());
        }
        
        // 添加请求特定的headers
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.header(entry.getKey(), entry.getValue());
            }
        }

        request = requestBuilder.build();
        
        try (okhttp3.Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Failed to download: HTTP " + response.code());
            }
            
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new RuntimeException("Response body is null");
            }
            
            int contentLength = (int) responseBody.contentLength();
            InputStream is = responseBody.byteStream();

            byte[] buffer = new byte[BUFFER_SIZE];
            if (callback == null) {
                copyAndCloseInput(is, os, buffer);
            } else {
                copyAndCloseInput(is, os, buffer, 0, contentLength, callback);
            }
        }
    }

    // Downloads the format part by part, with as many requests as needed
    private void downloadByPart(Format format, OutputStream os, Map<String, String> headers, Proxy proxy, YoutubeCallback<?> listener) throws IOException {
        long done = 0;
        int partNumber = 0;

        final String pathPrefix = "&cver=" + format.clientVersion() + "&range=";
        final long contentLength = format.contentLength();
        byte[] buffer = new byte[BUFFER_SIZE];

        OkHttpClient client = httpClient;
        
        // 如果请求指定了不同的代理，创建新的客户端
        if (proxy != null && !proxy.equals(config.getProxy())) {
            client = createHttpClientWithProxy(proxy);
        }

        while (done < contentLength) {
            long toRead = PART_LENGTH;
            if (done + toRead > contentLength) {
                toRead = (int) (contentLength - done);
            }

            partNumber++;
            String partUrl = format.url() + pathPrefix
                    + done + "-" + (done + toRead - 1)    // range first-last byte positions
                    + "&rn=" + partNumber;                // part number

            okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
                .url(partUrl);

            // 添加配置的headers
            for (Map.Entry<String, String> entry : config.getHeaders().entrySet()) {
                requestBuilder.header(entry.getKey(), entry.getValue());
            }
            
            // 添加请求特定的headers
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    requestBuilder.header(entry.getKey(), entry.getValue());
                }
            }

            okhttp3.Request request = requestBuilder.build();
            
            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("Failed to download: HTTP " + response.code());
                }

                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    throw new RuntimeException("Response body is null");
                }
                
                InputStream is = responseBody.byteStream();
                if (listener == null) {
                    done += copyAndCloseInput(is, os, buffer);
                } else {
                    done += copyAndCloseInput(is, os, buffer, done, contentLength, listener);
                }
            }
        }
    }

    // Copies as many bytes as possible then closes input stream
    private static long copyAndCloseInput(InputStream is, OutputStream os, byte[] buffer, long offset, long totalLength, final YoutubeCallback<?> listener) throws IOException {
        long done = 0;

        try {
            int read = 0;
            long lastProgress = offset == 0 ? 0 : (offset * 100) / totalLength;

            while ((read = is.read(buffer)) != -1) {
                if (Thread.interrupted()) {
                    throw new CancellationException();
                }
                os.write(buffer, 0, read);
                done += read;
                long progress = ((offset + done) * 100) / totalLength;
                if (progress > lastProgress) {
                    if (listener instanceof YoutubeProgressCallback) {
                        ((YoutubeProgressCallback<?>) listener).onDownloading((int) progress);
                    }
                    lastProgress = progress;
                }
            }
        } finally {
            closeSilently(is);
        }
        return done;
    }

    private static long copyAndCloseInput(InputStream is, OutputStream os, byte[] buffer) throws IOException {
        long done = 0;

        try {
            int count = 0;
            while ((count = is.read(buffer)) != -1) {
                if (Thread.interrupted()) {
                    throw new CancellationException();
                }
                os.write(buffer, 0, count);
                done += count;
            }
        } finally {
            closeSilently(is);
        }
        return done;
    }

}
