package com.example.demo.user.entity;

import com.example.demo.global.crypto.PiiCryptoConverter;
import com.example.demo.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 50)
    private String loginId;

    // 참고: AES-256-GCM 암호문은 같은 평문도 매번 달라지므로 고유성은 emailHash로 보장한다.
    // 참고: IV와 GCM 태그를 포함한 Base64 암호문 및 "ENC:" 접두사를 고려해 길이를 255로 둔다.
    @Column(nullable = false, length = 255)
    @Convert(converter = PiiCryptoConverter.class)
    private String email;

    // 참고: 정규화된 이메일의 HMAC-SHA256 값으로 정확 일치 조회와 고유성을 보장한다.
    // 참고: 마이그레이션 전 데이터는 PiiMigrationRunner가 값을 채울 때까지 null일 수 있다.
    @Column(unique = true, length = 64)
    private String emailHash;

    @Column(length = 100)
    private String password;

    @Column(nullable = false, length = 255)
    @Convert(converter = PiiCryptoConverter.class)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Provider provider;

    @Column(length = 100)
    private String providerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

}
