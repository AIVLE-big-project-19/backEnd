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

    // AES-256-GCM 암호문 저장. 같은 평문도 매번 다른 암호문이 나오므로 이 컬럼엔
    // unique 제약을 걸 수 없다 — unique 제약은 emailHash로 옮겼다.
    // 길이 여유: IV(12B)+평문+GCM태그(16B)를 Base64 인코딩하면 원문의 약 1.4배 +
    // "ENC:" 접두사 4자. 이메일/이름 모두 255면 충분히 여유롭다.
    @Column(nullable = false, length = 255)
    @Convert(converter = PiiCryptoConverter.class)
    private String email;

    // HMAC-SHA256(정규화된 이메일) — 정확 일치 조회 전용. nullable: 마이그레이션 전
    // 행은 일시적으로 null이며(PiiMigrationRunner가 채움), unique 컬럼에서 NULL은
    // 여러 개 허용되므로 제약 위반 없이 공존 가능하다.
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
