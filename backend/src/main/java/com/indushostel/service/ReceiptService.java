package com.indushostel.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Service
public class ReceiptService {
    private final JdbcTemplate db;
    private final Path files;
    public ReceiptService(JdbcTemplate db,@Value("${app.file-storage}") String files){this.db=db;this.files=Path.of(files).toAbsolutePath().normalize();}

    public byte[] create(long paymentId,Authentication auth) {
        Map<String,Object> p;
        try {p=db.queryForMap("SELECT p.id,p.student_id AS studentId,p.amount,p.payment_date AS paymentDate,p.method,p.reference,p.status,s.student_code AS studentCode,s.full_name AS studentName,i.billing_month AS billingMonth,rec.previous_balance AS previousBalance,rec.remaining_balance AS remainingBalance,room.room_number AS room,rec.receipt_number AS receiptNumber,rec.issued_at AS issuedAt,rec.voided_at AS voidedAt,rec.reversal_reason AS reversalReason,u.role,u.student_id AS callerStudentId,b.hostel_name AS hostelName,b.logo_file_id AS logoFileId FROM payment_submissions p JOIN students s ON s.id=p.student_id JOIN fee_invoices i ON i.id=p.invoice_id JOIN receipts rec ON rec.payment_submission_id=p.id JOIN users u ON u.email=? JOIN branding_settings b ON b.id=1 LEFT JOIN student_assignments a ON a.student_id=s.id AND a.ended_at IS NULL LEFT JOIN rooms room ON room.id=a.room_id WHERE p.id=?",auth.getName(),paymentId);}
        catch(Exception e){throw new IllegalArgumentException("Receipt not found");}
        if(!"APPROVED".equals(p.get("status")) && !"REVERSED".equals(p.get("status"))) throw new IllegalArgumentException("A receipt is available only after payment approval");
        if("STUDENT".equals(p.get("role")) && !p.get("studentId").equals(p.get("callerStudentId"))) throw new AccessDeniedException("Forbidden");
        try(PDDocument doc=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            PDPage page=new PDPage(PDRectangle.LETTER);doc.addPage(page);
            try(PDPageContentStream c=new PDPageContentStream(doc,page)) {
                float y=730;
                Object logoId=p.get("logoFileId");
                if(logoId!=null) {
                    try {
                        Map<String,Object> f=db.queryForMap("SELECT storage_key AS storageKey,content_type AS contentType FROM files WHERE id=?",logoId);
                        String type=(String)f.get("contentType");
                        if(type.equals("image/png")||type.equals("image/jpeg")) {
                            Path file=files.resolve((String)f.get("storageKey")).normalize();
                            if(file.startsWith(files)&&Files.exists(file)) {PDImageXObject image=PDImageXObject.createFromByteArray(doc,Files.readAllBytes(file),file.getFileName().toString());float w=90,h=w*image.getHeight()/image.getWidth();c.drawImage(image,50,665,w,h);y=640;}
                        }
                    } catch(Exception ignored) { /* Missing or unsupported logo must not block receipt delivery. */ }
                }
                text(c,50,y,(String)p.get("hostelName"),new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),20); y-=30;
                text(c,50,y,"OFFICIAL PAYMENT RECEIPT",new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),13);y-=35;
                line(c,50,y,562,y);y-=28;
                y=row(c,"Receipt number",String.valueOf(p.get("receiptNumber")),y);
                y=row(c,"Student",String.valueOf(p.get("studentName"))+"  ("+p.get("studentCode")+")",y);
                y=row(c,"Room / bed",p.get("room")==null?"Not assigned":String.valueOf(p.get("room")),y);
                y=row(c,"Billing month",String.valueOf(p.get("billingMonth")),y);
                y=row(c,"Payment date",String.valueOf(p.get("paymentDate")),y);
                y=row(c,"Payment method",String.valueOf(p.getOrDefault("method","Not specified")),y);
                y=row(c,"Reference",String.valueOf(p.getOrDefault("reference","—")),y);
                y-=12;line(c,50,y,562,y);y-=28;
                BigDecimal amount=(BigDecimal)p.get("amount"),paid=(BigDecimal)p.get("paidAmount"),fee=(BigDecimal)p.get("feeAmount");
                y=row(c,"Previous balance","PKR "+String.valueOf(p.get("previousBalance")),y);
                y=row(c,"Amount received","PKR "+amount.toPlainString(),y);
                y=row(c,"Remaining balance","PKR "+String.valueOf(p.get("remainingBalance")),y);
                y-=20;
                if("REVERSED".equals(p.get("status"))) {text(c,50,y,"REVERSED — this historical receipt is retained for audit only.",new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),10);y-=16;text(c,50,y,"Reason: "+String.valueOf(p.get("reversalReason")),new PDType1Font(Standard14Fonts.FontName.HELVETICA),9);}
                else text(c,50,y,"Payment verified and recorded by INDUS HOSTEL SERVICE BADIN.",new PDType1Font(Standard14Fonts.FontName.HELVETICA),10);
                y-=18;text(c,50,y,"Issued: "+String.valueOf(p.get("issuedAt")),new PDType1Font(Standard14Fonts.FontName.HELVETICA),9);
                y-=42;line(c,50,y,562,y);y-=20;text(c,50,y,"Keep this receipt for your records.",new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE),10);
            }
            doc.save(out);return out.toByteArray();
        } catch(Exception e){throw new IllegalStateException("Unable to generate receipt PDF");}
    }

    private float row(PDPageContentStream c,String label,String value,float y)throws Exception {text(c,50,y,label,new PDType1Font(Standard14Fonts.FontName.HELVETICA),11);text(c,210,y,value,new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),11);return y-24;}
    private void text(PDPageContentStream c,float x,float y,String value,PDType1Font font,int size)throws Exception {c.beginText();c.setFont(font,size);c.newLineAtOffset(x,y);c.showText(ascii(value));c.endText();}
    private void line(PDPageContentStream c,float x1,float y1,float x2,float y2)throws Exception {c.moveTo(x1,y1);c.lineTo(x2,y2);c.stroke();}
    private String ascii(String s){return s==null?"":s.replaceAll("[^\\x20-\\x7E]","?");}
}
