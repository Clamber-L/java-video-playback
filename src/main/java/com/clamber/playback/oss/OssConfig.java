package com.clamber.playback.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;

@Slf4j
@Setter
@Getter
@Configuration
public class OssConfig {

	/** SDK 连接用的 endpoint。内网自建网关若证书不被信任，可写成 http:// 走明文 */
	@Value("${aliyun.oss.endpoint}")
	private String endpoint;

	@Value("${aliyun.oss.access-key-id}")
	private String accessKeyId;

	@Value("${aliyun.oss.access-key-secret}")
	private String accessKeySecret;

	@Value("${aliyun.oss.bucket-name}")
	private String bucketName;

	/**
	 * 对外播放地址的前缀，写入数据库的 playUrl 和 m3u8 里的切片地址都用它。
	 *
	 * 与 endpoint 分开配置的原因：
	 * 1. endpoint 可以不带协议头（SDK 默认按 HTTP 处理），但播放地址必须是带协议的绝对地址，
	 *    否则浏览器会当成相对路径。
	 * 2. SDK 上传时会把 bucket 拼成子域名（ClientBuilderConfiguration 默认 supportCname=false），
	 *    所以真实可访问地址是 {bucket}.{endpoint}/{key}，而不是 {endpoint}/{key}。
	 * 3. 以后播放若改走 CDN 或独立域名，只改这一项，不影响上传。
	 *
	 * 留空则按「协议 + bucket 子域名 + endpoint」自动推导。
	 */
	@Value("${aliyun.oss.public-base-url:}")
	private String publicBaseUrl;

	/** 推导并缓存下来的最终播放前缀，不含尾部斜杠 */
	private String resolvedPublicBaseUrl;

	@PostConstruct
	public void init() {
		this.resolvedPublicBaseUrl = resolvePublicBaseUrl();
		log.info("OSS 播放地址前缀：{}", resolvedPublicBaseUrl);
	}

	/**
	 * 对外播放地址前缀（不含尾部斜杠）。拼接方式：getPublicUrl() + "/" + ossKey
	 */
	public String getPublicBaseUrl() {
		return resolvedPublicBaseUrl;
	}

	/** 由 ossKey 拼出完整的对外播放地址 */
	public String buildPublicUrl(String ossKey) {
		return resolvedPublicBaseUrl + "/" + ossKey;
	}

	private String resolvePublicBaseUrl() {
		// 显式配置优先
		if (StringUtils.hasText(publicBaseUrl)) {
			String v = publicBaseUrl.trim();
			if (!v.startsWith("http://") && !v.startsWith("https://")) {
				throw new IllegalStateException(
						"aliyun.oss.public-base-url 必须以 http:// 或 https:// 开头，当前：" + v);
			}
			return stripTrailingSlash(v);
		}

		// 未配置时按 SDK 的实际行为推导：{scheme}://{bucket}.{host}
		String ep = stripTrailingSlash(endpoint.trim());
		String scheme = "http";
		String host = ep;
		if (ep.startsWith("https://")) {
			scheme = "https";
			host = ep.substring("https://".length());
		} else if (ep.startsWith("http://")) {
			host = ep.substring("http://".length());
		}

		String derived = scheme + "://" + bucketName + "." + host;
		log.warn("未配置 aliyun.oss.public-base-url，按 SDK 行为推导为 {}。"
				+ "如果内网走的是 CNAME 绑定域名（不需要 bucket 前缀），请显式配置该项。", derived);
		return derived;
	}

	private String stripTrailingSlash(String v) {
		return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
	}

	@Bean
	public OSS ossClient() {
		return new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
	}
}