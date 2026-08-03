package com.example.demo.board.storage;

import org.springframework.web.multipart.MultipartFile;

public interface BoardFileStorage {

    String upload(MultipartFile file, String contentType);

    byte[] download(String objectKey);

    void delete(String objectKey);
}
