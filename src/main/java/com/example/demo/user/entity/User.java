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

    @Convert(converter = PiiCryptoConverter.class)
    @Column(nullable = false, length = 255)
    private String email;

    // email의 암호문은 저장할 때마다 달라져 email 컬럼으로는 정확 일치 조회가 불가능하다.
    // 검색(중복확인/로그인)은 이 해시 컬럼으로 한다.
    @Column(unique = true, length = 64)
    private String emailHash;

    @Column(length = 100)
    private String password;

    @Convert(converter = PiiCryptoConverter.class)
    @Column(nullable = false, length = 255)
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
