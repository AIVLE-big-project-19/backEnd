package com.example.demo.report.controller;

import com.example.demo.idleland.service.IdleLandReportService;
import com.example.demo.analysis.service.AnalysisSnapshotService;
import com.example.demo.report.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.http.HttpHeaders;

@RestController
@RequestMapping("/pdf")
public class ReportController {
    @Autowired
    private ReportService reportService;

    @Autowired
    private IdleLandReportService idleLandReportService;

    @Autowired
    private AnalysisSnapshotService analysisSnapshotService;

    @PostMapping("/generate/idle-land/{id}")
    public ResponseEntity<byte[]> getIdleLandPdf(@PathVariable Long id) throws Exception {
        byte[] pdfContents = idleLandReportService.generateReportPdf(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "report.pdf");
        return new ResponseEntity<>(pdfContents, headers, HttpStatus.OK);
    }

    @PostMapping("/generate/analysis/{analysisId}")
    public ResponseEntity<byte[]> getAnalysisSnapshotPdf(@PathVariable Long analysisId) throws Exception {
        byte[] pdfContents = analysisSnapshotService.getOrCreatePdf(analysisId, currentUserId());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "report.pdf");
        return new ResponseEntity<>(pdfContents, headers, HttpStatus.OK);
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal instanceof Long id ? id : null;
    }
}
