package com.example.demo.global.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApiResponse<T> {

    private boolean success;

    private String message;

    private T data;

    public static <T> ApiResponse<T> success(
            SuccessCode code,
            T data){

        return ApiResponse.<T>builder()
                .success(true)
                .message(code.getMessage())
                .data(data)
                .build();
    }


    public static ApiResponse<Void> success(
            SuccessCode code){

        return ApiResponse.<Void>builder()
                .success(true)
                .message(code.getMessage())
                .build();
    }

    public static <T> ApiResponse<T> fail(String message){

        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .build();
    }

}