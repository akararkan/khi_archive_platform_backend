package ak.dev.khi_archive_platform;

import ak.dev.khi_archive_platform.user.exceptions.UserStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3ServiceTest {

    private static final int PART_SIZE = 16 * 1024 * 1024;

    private RecordingS3 recordingS3;
    private S3Service service;

    @BeforeEach
    void setUp() {
        recordingS3 = new RecordingS3();
        S3Client s3Client = (S3Client) Proxy.newProxyInstance(
                S3Client.class.getClassLoader(),
                new Class<?>[]{S3Client.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createMultipartUpload" ->
                            CreateMultipartUploadResponse.builder().uploadId("upload-1").build();
                    case "uploadPart" -> {
                        recordingS3.parts.add((UploadPartRequest) args[0]);
                        if (recordingS3.failUpload) {
                            throw S3Exception.builder().message("upload failed").build();
                        }
                        yield UploadPartResponse.builder()
                                .eTag("etag-" + recordingS3.parts.size())
                                .build();
                    }
                    case "completeMultipartUpload" -> {
                        recordingS3.completed = (CompleteMultipartUploadRequest) args[0];
                        yield CompleteMultipartUploadResponse.builder().build();
                    }
                    case "abortMultipartUpload" -> {
                        recordingS3.aborted = (AbortMultipartUploadRequest) args[0];
                        yield AbortMultipartUploadResponse.builder().build();
                    }
                    case "serviceName" -> "s3";
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        service = new S3Service(s3Client);
        ReflectionTestUtils.setField(service, "bucket", "test-bucket");
        ReflectionTestUtils.setField(service, "baseFolder", "archive");
        ReflectionTestUtils.setField(service, "region", "us-east-1");
    }

    @Test
    void largeFileIsUploadedInBoundedMultipartChunks() {
        byte[] content = new byte[PART_SIZE + 123];
        MockMultipartFile file = new MockMultipartFile(
                "videoFile", "large.mp4", "video/mp4", content);

        String url = service.upload(file, "videos/VID_1");

        List<UploadPartRequest> parts = recordingS3.parts;
        assertEquals(2, parts.size());
        assertEquals(PART_SIZE, parts.get(0).contentLength());
        assertEquals(123, parts.get(1).contentLength());
        assertEquals(1, parts.get(0).partNumber());
        assertEquals(2, parts.get(1).partNumber());

        assertEquals(2, recordingS3.completed.multipartUpload().parts().size());
        assertTrue(url.startsWith(
                "https://test-bucket.s3.us-east-1.amazonaws.com/archive/videos/VID_1/"));
        assertTrue(url.endsWith("-large.mp4"));
    }

    @Test
    void failedLargeUploadIsAborted() {
        recordingS3.failUpload = true;
        MockMultipartFile file = new MockMultipartFile(
                "videoFile", "large.mp4", "video/mp4", new byte[PART_SIZE + 1]);

        assertThrows(UserStorageException.class, () -> service.upload(file, "videos/VID_1"));

        assertNotNull(recordingS3.aborted);
        assertEquals("upload-1", recordingS3.aborted.uploadId());
    }

    private static final class RecordingS3 {
        private final List<UploadPartRequest> parts = new ArrayList<>();
        private CompleteMultipartUploadRequest completed;
        private AbortMultipartUploadRequest aborted;
        private boolean failUpload;
    }
}
