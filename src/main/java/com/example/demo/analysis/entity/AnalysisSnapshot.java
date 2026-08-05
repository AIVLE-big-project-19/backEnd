package com.example.demo.analysis.entity;

import com.example.demo.global.entity.BaseEntity;
import com.example.demo.user.entity.User;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "analysis_snapshot")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class AnalysisSnapshot extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(length = 100)
    private String sourceId;

    private Long candidateId;

    @Column(nullable = false, length = 500)
    private String address;

    @Column(length = 20)
    private String siteType;

    private Double latitude;
    private Double longitude;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String analysisJson;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String responseJson;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "LONGBLOB")
    private byte[] pdfBytes;

    @Column(nullable = false)
    private boolean favorite;

    @Column(nullable = false, length = 20)
    private String status;

    public void cachePdf(byte[] pdfBytes) {
        this.pdfBytes = pdfBytes;
    }

    public void updateManagement(boolean favorite, String status) {
        this.favorite = favorite;
        this.status = status;
    }
}
