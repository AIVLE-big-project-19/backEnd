package com.example.demo.board.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3BoardFileStorageTest {

    @Mock
    private S3Client s3Client;

    private S3BoardFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new S3BoardFileStorage(s3Client);
        ReflectionTestUtils.setField(storage, "bucket", "test-board-bucket");
        ReflectionTestUtils.setField(storage, "prefix", "board-attachments");
    }

    @Test
    void 첨부파일을_S3_객체로_업로드한다() {
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "guide.PNG",
                "image/png",
                new byte[]{1, 2, 3}
        );
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String objectKey = storage.upload(file, "image/png");

        assertThat(objectKey).startsWith("board-attachments/").endsWith(".png");
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}
