package com.example.demo.report.controller;

import com.example.demo.idleland.service.IdleLandReportService;
import com.example.demo.report.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.http.HttpHeaders;
import java.util.Map;

@RestController
@RequestMapping("/pdf")
public class ReportController {
    @Autowired
    private ReportService reportService;

    @Autowired
    private IdleLandReportService idleLandReportService;

    @PostMapping("/generate")
    public ResponseEntity<byte[]> getPdf(@RequestBody Map<String, String> request) {
        String address = request.get("address");
        try {
            byte[] pdfContents = reportService.generateReportPdf(address);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "report.pdf");
            return new ResponseEntity<>(pdfContents, headers, HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // 유휴부지 검색 결과에서 선택한 후보지 1건에 대한 보고서 다운로드 (백엔드 DB + Ranking_ML 실연동)
    @PostMapping("/generate/idle-land/{id}")
    public ResponseEntity<byte[]> getIdleLandPdf(@PathVariable Long id) throws Exception {
        byte[] pdfContents = idleLandReportService.generateReportPdf(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "report.pdf");
        return new ResponseEntity<>(pdfContents, headers, HttpStatus.OK);
    }

    // AI 서버 연동 전 더미 데이터로 PDF를 확인하기 위한 테스트용 엔드포인트
    // 예: GET /api/pdf/generate/sample?type=ROOF  또는  ?type=LAND
    // 추후 머신 만들어질때 제거 예정
    @GetMapping("/generate/sample")
    public ResponseEntity<byte[]> getSamplePdf(@RequestParam(defaultValue = "ROOF") String type) {
        try {
            byte[] pdfContents = reportService.generateSampleReportPdf(type);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "sample_report.pdf");
            return new ResponseEntity<>(pdfContents, headers, HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}