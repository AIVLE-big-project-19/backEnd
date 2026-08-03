package com.example.demo.board.storage;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class S3BoardFileStorage implements BoardFileStorage {

    private final S3Client s3Client;

    @Value("${app.board-storage.bucket}")
    private String bucket;

    @Value("${app.board-storage.prefix}")
    private String prefix;

    @Override
    public String upload(MultipartFile file, String contentType) {
        validateBucket();
        String objectKey = createObjectKey(file.getOriginalFilename());
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(contentType)
                            .contentLength(file.getSize())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize())
            );
            return objectKey;
        } catch (IOException | SdkException exception) {
            throw new CustomException(ErrorCode.ATTACHMENT_STORAGE_FAILED);
        }
    }

    @Override
    public byte[] download(String objectKey) {
        validateBucket();
        try {
            ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(objectKey).build()
            );
            return response.asByteArray();
        } catch (NoSuchKeyException exception) {
            throw new CustomException(ErrorCode.ATTACHMENT_NOT_FOUND);
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new CustomException(ErrorCode.ATTACHMENT_NOT_FOUND);
            }
            throw new CustomException(ErrorCode.ATTACHMENT_STORAGE_FAILED);
        } catch (SdkException exception) {
            throw new CustomException(ErrorCode.ATTACHMENT_STORAGE_FAILED);
        }
    }

    @Override
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        validateBucket();
        try {
            s3Client.deleteObject(request -> request.bucket(bucket).key(objectKey));
        } catch (SdkException exception) {
            throw new CustomException(ErrorCode.ATTACHMENT_STORAGE_FAILED);
        }
    }

    private void validateBucket() {
        if (bucket == null || bucket.isBlank()) {
            throw new CustomException(ErrorCode.ATTACHMENT_STORAGE_FAILED);
        }
    }

    private String createObjectKey(String originalFilename) {
        LocalDate today = LocalDate.now();
        String normalizedPrefix = prefix == null ? "board-attachments" : prefix.replaceAll("^/+|/+$", "");
        return "%s/%04d/%02d/%s%s".formatted(
                normalizedPrefix,
                today.getYear(),
                today.getMonthValue(),
                UUID.randomUUID(),
                safeExtension(originalFilename)
        );
    }

    private String safeExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        String extension = filename.substring(dot).toLowerCase();
        return extension.matches("\\.[a-z0-9]{1,10}") ? extension : "";
    }
}
