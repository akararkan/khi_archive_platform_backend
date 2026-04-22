package ak.dev.khi_archive_platform.platform.config;


import org.springframework.context.annotation.Configuration;

/**
 * ✅ Multipart JSON support (Spring Boot 3/4)
 *
 * In Spring Boot 3+ / 4+, you do NOT need to register any HttpMessageConverter
 * to parse JSON inside multipart/form-data.
 *
 * Spring Boot auto-configures Jackson converters automatically and supports:
 *   - @RequestPart("data") MyDto dto
 *   - @RequestPart(value="file", required=false) MultipartFile file
 *
 * Postman:
 * 1) form-data
 * 2) key: data
 * 3) type: Text
 * 4) Set "Content-Type" of that part to: application/json
 * 5) Paste JSON
 *
 * ✅ No beans required here.
 */
@Configuration
public class MultipartJsonConfig {
    // Intentionally empty — Spring Boot handles multipart JSON automatically.
}
