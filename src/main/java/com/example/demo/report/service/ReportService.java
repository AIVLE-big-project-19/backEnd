package com.example.demo.report.service;

import java.io.ByteArrayOutputStream;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import org.springframework.stereotype.Service;

@Service
public class ReportService {

    public byte[] generateAddressPdf(String address) throws Exception {


        // PDF 데이터를 메모리에 저장하기 위한 바이트 배열 출력 스트림
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // PDF 쓰기 및 문서 초기화
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdf = new PdfDocument(writer);
        Document document = new Document(pdf);

        // 문서 내용 추가
        document.add(new Paragraph("SolarAivle 부지 분석 보고서"));
        document.add(new Paragraph("--------------------------------"));
        String fontPath = "src/main/resources/malgun.ttf";
        PdfFont font = PdfFontFactory.createFont(fontPath, PdfEncodings.IDENTITY_H);
        document.setFont(font);
        document.add(new Paragraph("분석 대상 주소: " + address));
        document.add(new Paragraph("--------------------------------"));
        document.add(new Paragraph("해당 부지에 대한 태양광 설치 분석 결과입니다."));

        // 문서 닫기 (이 시점에 PDF 데이터가 baos에 채워짐)
        document.close();

        return baos.toByteArray();
    }
}