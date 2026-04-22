package ak.dev.khi_archive_platform;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ak.dev.khi_archive_platform.user.exceptions.UserStorageException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Client s3Client;

    @Value("${aws.s3.bucket}")
    private String bucket;

    @Value("${aws.s3.base-folder:khi-archive-platform-folders}")
    private String baseFolder;

    @Value("${aws.s3.region}")
    private String region;

    @Value("${aws.s3.person-folder:persons}")
    private String personFolder;

    private static final String DEFAULT_FOLDER = "files";
    private static final String PROFILE_FOLDER = "user_profile_images";

    // ============================================================
    // UPLOAD METHODS
    // ============================================================

    public String upload(byte[] fileBytes, String folder, String originalFilename, String contentType) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new UserStorageException("File is empty.");
        }

        String safeFolder = normalizeFolder(folder);
        String key = buildKey(safeFolder, originalFilename);

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .build();

            s3Client.putObject(request, RequestBody.fromBytes(fileBytes));

            String publicUrl = getPublicUrl(key);
            log.info("S3 upload successful: bucket={}, key={}, url={}", bucket, key, publicUrl);
            return publicUrl;
        } catch (S3Exception e) {
            log.error("S3 upload failed for key={}: {}", key, e.getMessage(), e);
            throw new UserStorageException("Failed to upload file to S3.", e);
        }
    }

    public String upload(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) {
            throw new UserStorageException("File is empty.");
        }

        try {
            String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
            return upload(file.getBytes(), folder, originalFilename, file.getContentType());
        } catch (IOException e) {
            log.error("Failed to read MultipartFile for S3 upload", e);
            throw new UserStorageException("Failed to read uploaded file.", e);
        }
    }

    public String uploadProfileImage(byte[] fileBytes, String originalFilename, String contentType) {
        return upload(fileBytes, PROFILE_FOLDER, originalFilename, contentType);
    }

    public String uploadProfileImage(MultipartFile file) {
        return upload(file, PROFILE_FOLDER);
    }

    public String uploadPersonPortrait(byte[] fileBytes, String originalFilename, String contentType, String personCode) {
        String safePersonCode = normalizeFolder(personCode);
        return upload(fileBytes, personFolder + "/" + safePersonCode, originalFilename, contentType);
    }

    public String uploadPersonPortrait(MultipartFile file, String personCode) {
        if (file == null || file.isEmpty()) {
            throw new UserStorageException("File is empty.");
        }

        try {
            String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "portrait";
            return uploadPersonPortrait(file.getBytes(), originalFilename, file.getContentType(), personCode);
        } catch (IOException e) {
            log.error("Failed to read MultipartFile for person portrait upload", e);
            throw new UserStorageException("Failed to read uploaded file.", e);
        }
    }

    // ============================================================
    // DOWNLOAD METHODS
    // ============================================================

    public byte[] downloadByUrl(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new UserStorageException("File URL is required.");
        }

        String key = extractKeyFromUrl(fileUrl);
        if (key == null || key.isBlank()) {
            throw new UserStorageException("Could not extract S3 key from URL.");
        }

        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(request);
            return objectBytes.asByteArray();
        } catch (S3Exception e) {
            log.error("S3 download failed for key={}: {}", key, e.getMessage(), e);
            throw new UserStorageException("Failed to download file from S3.", e);
        }
    }

    // ============================================================
    // DELETE METHODS
    // ============================================================

    public boolean deleteByUrl(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            log.warn("S3 delete skipped: URL is blank");
            return false;
        }

        String key = extractKeyFromUrl(fileUrl);
        if (key == null || key.isBlank()) {
            log.warn("S3 delete skipped: could not extract key from URL={}", fileUrl);
            return false;
        }

        return deleteByKey(key);
    }

    public void deleteFile(String fileUrl) {
        deleteByUrl(fileUrl);
    }

    public boolean deleteByKey(String key) {
        if (key == null || key.isBlank()) {
            log.warn("S3 delete skipped: key is blank");
            return false;
        }

        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            s3Client.deleteObject(request);
            log.info("S3 delete successful: bucket={}, key={}", bucket, key);
            return true;
        } catch (S3Exception e) {
            log.error("S3 delete failed for key={}: {}", key, e.getMessage(), e);
            return false;
        }
    }

    public void deleteFiles(List<String> fileUrls) {
        if (fileUrls == null || fileUrls.isEmpty()) {
            return;
        }

        log.info("Batch deleting {} files from S3", fileUrls.size());
        for (String url : fileUrls) {
            deleteByUrl(url);
        }
    }

    // ============================================================
    // URL & KEY HELPERS
    // ============================================================

    public String extractKeyFromUrl(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return null;
        }

        try {
            URI uri = new URI(fileUrl);
            String path = uri.getPath();

            if (path == null || path.isBlank()) {
                return null;
            }

            if (path.startsWith("/")) {
                path = path.substring(1);
            }

            if (path.startsWith(bucket + "/")) {
                path = path.substring(bucket.length() + 1);
            }

            return path;
        } catch (Exception e) {
            log.debug("Standard URL parsing failed, using fallback for: {}", fileUrl, e);
            return extractKeyFallback(fileUrl);
        }
    }

    private String extractKeyFallback(String fileUrl) {
        int baseIndex = fileUrl.indexOf(baseFolder);
        if (baseIndex != -1) {
            String key = fileUrl.substring(baseIndex);
            int queryIndex = key.indexOf("?");
            if (queryIndex != -1) {
                key = key.substring(0, queryIndex);
            }
            return key;
        }

        int lastSlash = fileUrl.lastIndexOf('/');
        if (lastSlash != -1 && lastSlash < fileUrl.length() - 1) {
            return baseFolder + "/" + DEFAULT_FOLDER + "/" + fileUrl.substring(lastSlash + 1);
        }

        return null;
    }

    public String getPublicUrl(String key) {
        return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + key;
    }

    public boolean isOurS3Url(String url) {
        return url != null && url.contains(bucket) && url.contains(".s3.");
    }

    private String buildKey(String folder, String originalFilename) {
        String safeName = sanitizeFilename(originalFilename);
        return baseFolder + "/" + folder + "/" + UUID.randomUUID() + "-" + safeName;
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "file";
        }
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String normalizeFolder(String folder) {
        if (folder == null || folder.isBlank()) {
            return DEFAULT_FOLDER;
        }
        return folder.replaceAll("^/+", "").replaceAll("/+$", "");
    }
}