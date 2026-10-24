package com.indushostel.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

@Service
public class MonthlyReportService {
    private final JdbcTemplate db;
    private final Path reportDir;
    private final Path fileDir;
    private final ObjectProvider<JavaMailSender> mailProvider;
    private final String configuredOwner;
    private final String mailFrom;
    private final java.time.ZoneId zone;

    public MonthlyReportService(JdbcTemplate db,ObjectProvider<JavaMailSender> mailProvider,
            @Value("${app.report-storage}") String reportDir,@Value("${app.owner-email:}") String configuredOwner,
            @Value("${spring.mail.username:}") String mailFrom,@Value("${app.file-storage}")String fileDir,@Value("${app.hostel-timezone:Asia/Karachi}")String timezone){this.db=db;this.mailProvider=mailProvider;this.reportDir=Path.of(reportDir).toAbsolutePath().normalize();this.fileDir=Path.of(fileDir).toAbsolutePath().normalize();this.configuredOwner=configuredOwner;this.mailFrom=mailFrom;this.zone=java.time.ZoneId.of(timezone);}

    public Map<String,Object> generate(YearMonth period,String kind,Authentication auth) {
        if("MANUAL".equals(kind)) requireOwner(auth);
        LocalDate month=period.atDay(1);
        List<Map<String,Object>> existing=db.queryForList("SELECT id,reporting_month AS reportingMonth,generated_at AS generatedAt,generation_kind AS generationKind,delivery_status AS deliveryStatus,delivery_error AS deliveryError,delivered_at AS deliveredAt,file_key AS fileKey FROM monthly_reports WHERE reporting_month=? AND generation_kind=?",month,kind);
        if(!existing.isEmpty()) {
            Map<String,Object> row=existing.get(0);
            if("SCHEDULED".equals(kind) && "FAILED".equals(row.get("deliveryStatus")) && attemptCount(((Number)row.get("id")).longValue())<3) deliver(((Number)row.get("id")).longValue(),(String)row.get("fileKey"),period);
            return report(((Number)row.get("id")).longValue());
        }
        try {
            byte[] workbook=build(period);
            Files.createDirectories(reportDir);
            String key="monthly-"+period+"-"+kind.toLowerCase(Locale.ROOT)+"-"+UUID.randomUUID()+".xlsx";
            Path file=reportDir.resolve(key).normalize();if(!file.startsWith(reportDir))throw new IllegalStateException("Invalid report path");Files.write(file,workbook);
            Long actor=null;
            if(auth!=null) actor=db.queryForObject("SELECT id FROM users WHERE email=?",Long.class,auth.getName());
            Long id=insert("INSERT INTO monthly_reports(reporting_month,file_key,generation_kind,generated_by,delivery_status) VALUES(?,?,?,?,?)",month,key,kind,actor,"PENDING");
            if("SCHEDULED".equals(kind)) deliver(id,key,period); else db.update("UPDATE monthly_reports SET delivery_status='NOT_CONFIGURED' WHERE id=?",id);
            return report(id);
        } catch(Exception e) { throw new IllegalStateException("Monthly report generation failed",e); }
    }

    public void scheduledPreviousMonth(YearMonth period) { generate(period,"SCHEDULED",null); }

    private void deliver(long id,String key,YearMonth period) {
        Map<String,Object> brand=db.queryForMap("SELECT hostel_name AS hostelName,owner_email AS ownerEmail FROM branding_settings WHERE id=1");
        String to=Objects.toString(brand.get("ownerEmail"),"");if(to.isBlank())to=configuredOwner;
        JavaMailSender mail=mailProvider.getIfAvailable();
        if(to.isBlank()||mail==null) {db.update("UPDATE monthly_reports SET delivery_status='NOT_CONFIGURED',delivery_error=? WHERE id=?","Owner email or SMTP is not configured",id);recordAttempt(id,"NOT_CONFIGURED","Owner email or SMTP is not configured");return;}
        try {
            Path path=reportDir.resolve(key).normalize();if(!path.startsWith(reportDir))throw new IllegalStateException("Invalid report path");
            MimeMessage message=mail.createMimeMessage();MimeMessageHelper helper=new MimeMessageHelper(message,true,"UTF-8");
            if(mailFrom!=null&&!mailFrom.isBlank())helper.setFrom(mailFrom);
            helper.setTo(to);helper.setSubject(brand.get("hostelName")+" — Monthly report "+period);
            String hostelName=html(Objects.toString(brand.get("hostelName"),"INDUS HOSTEL SERVICE BADIN"));
            Path logo=emailLogoPath();
            String logoMarkup=logo==null?"":"<img src=\"cid:hostel-logo\" alt=\"Hostel logo\" style=\"max-width:240px;max-height:100px\">";
            helper.setText("<html><body style=\"font-family:Arial,sans-serif;color:#10263b\">"+logoMarkup+"<h2 style=\"color:#087f82\">"+hostelName+"</h2><p>The attached workbook covers "+period+".</p><p>Verified collections include approved payment records only. Owner withdrawals are listed separately from operating expenses.</p><p>Generated by INDUS HOSTEL SERVICE BADIN.</p></body></html>",true);
            if(logo!=null)helper.addInline("hostel-logo",logo.toFile());
            helper.addAttachment("indus-hostel-monthly-"+period+".xlsx",path.toFile());mail.send(message);
            db.update("UPDATE monthly_reports SET delivery_status='SENT',delivery_error=NULL,delivered_at=CURRENT_TIMESTAMP WHERE id=?",id);recordAttempt(id,"SENT",null);
        } catch(Exception e) {
            String error=Objects.toString(e.getMessage(),"Email delivery failed");if(error.length()>950)error=error.substring(0,950);
            db.update("UPDATE monthly_reports SET delivery_status='FAILED',delivery_error=? WHERE id=?",error,id);recordAttempt(id,"FAILED",error);
        }
    }

    public void retryFailed() {
        for(Map<String,Object> r:db.queryForList("SELECT id,reporting_month,file_key FROM monthly_reports WHERE delivery_status='FAILED' AND generation_kind='SCHEDULED' ORDER BY generated_at")) {
            long id=((Number)r.get("id")).longValue();if(attemptCount(id)>=3)continue;
            deliver(id,(String)r.get("file_key"),YearMonth.from(((Date)r.get("reporting_month")).toLocalDate()));
        }
    }

    public Path pathFor(long id,Authentication auth) {
        requireOwner(auth);String key=db.queryForObject("SELECT file_key FROM monthly_reports WHERE id=?",String.class,id);Path p=reportDir.resolve(key).normalize();if(!p.startsWith(reportDir)||!Files.exists(p))throw new IllegalArgumentException("Report file not found");return p;
    }

    private byte[] build(YearMonth period)throws Exception {
        LocalDate start=period.atDay(1),end=period.plusMonths(1).atDay(1);
        try(XSSFWorkbook wb=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            CellStyle title=wb.createCellStyle();Font tf=wb.createFont();tf.setBold(true);tf.setFontHeightInPoints((short)14);tf.setColor(IndexedColors.DARK_TEAL.getIndex());title.setFont(tf);
            CellStyle header=wb.createCellStyle();Font hf=wb.createFont();hf.setBold(true);hf.setColor(IndexedColors.WHITE.getIndex());header.setFont(hf);header.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle currency=wb.createCellStyle();currency.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            String name=db.queryForObject("SELECT hostel_name FROM branding_settings WHERE id=1",String.class);
            Sheet summary=wb.createSheet("Monthly Summary");summary.setColumnWidth(0,9000);summary.setColumnWidth(1,5400);
            summary.createRow(0).createCell(0).setCellValue(safe(name));summary.getRow(0).getCell(0).setCellStyle(title);
            embedLogo(wb,summary);
            summary.createRow(1).createCell(0).setCellValue("Reporting period");summary.getRow(1).createCell(1).setCellValue(period.toString());
            summary.createRow(2).createCell(0).setCellValue("Report generated");summary.getRow(2).createCell(1).setCellValue(java.time.ZonedDateTime.now(zone).toLocalDateTime().toString());
            summary.createRow(4).createCell(0).setCellValue("Metric");summary.getRow(4).createCell(1).setCellValue("PKR");summary.getRow(4).getCell(0).setCellStyle(header);summary.getRow(4).getCell(1).setCellStyle(header);
            List<Object[]> sums=List.of(
                new Object[]{"Fees invoiced",scalar("SELECT COALESCE(SUM(fee_amount),0) FROM fee_invoices WHERE billing_month=?",start)},
                new Object[]{"Verified fee collections",scalar("SELECT COALESCE(SUM(amount),0) FROM payment_submissions WHERE status='APPROVED' AND payment_date>=? AND payment_date<?",start,end)},
                new Object[]{"Pending submissions (not revenue)",scalar("SELECT COALESCE(SUM(amount),0) FROM payment_submissions WHERE status='PENDING' AND payment_date>=? AND payment_date<?",start,end)},
                new Object[]{"Outstanding student fees",scalar("SELECT COALESCE(SUM(fee_amount-paid_amount),0) FROM fee_invoices WHERE fee_amount>paid_amount")},
                new Object[]{"Operating expenses",scalar("SELECT COALESCE(SUM(amount),0) FROM expenses WHERE reversed_at IS NULL AND expense_date>=? AND expense_date<?",start,end)},
                new Object[]{"Owner withdrawals (separate)",scalar("SELECT COALESCE(SUM(amount),0) FROM owner_withdrawals WHERE withdrawal_date>=? AND withdrawal_date<?",start,end)},
                new Object[]{"Operating position (collections minus expenses)",scalar("SELECT COALESCE((SELECT SUM(amount) FROM payment_submissions WHERE status='APPROVED' AND payment_date>=? AND payment_date<?),0)-COALESCE((SELECT SUM(amount) FROM expenses WHERE reversed_at IS NULL AND expense_date>=? AND expense_date<?),0)",start,end,start,end)});
            int rowNo=5;for(Object[] sum:sums){Row row=summary.createRow(rowNo++);row.createCell(0).setCellValue((String)sum[0]);Cell c=row.createCell(1);c.setCellValue(((Number)sum[1]).doubleValue());c.setCellStyle(currency);}
            writeMap(wb,"Student Fee Status",new String[]{"Student ID","Name","Room","Month","Fee","Paid","Balance","Due date","Status"},
                "SELECT s.student_code AS StudentID,s.full_name AS Name,COALESCE(r.room_number,'') AS Room,i.billing_month AS Month,i.fee_amount AS Fee,i.paid_amount AS Paid,(i.fee_amount-i.paid_amount) AS Balance,i.due_date AS `Due date`,CASE WHEN i.fee_amount=i.paid_amount THEN 'PAID' WHEN i.due_date<CURDATE() THEN 'OVERDUE' WHEN i.paid_amount>0 THEN 'PARTIALLY PAID' ELSE 'UNPAID' END AS Status FROM fee_invoices i JOIN students s ON s.id=i.student_id LEFT JOIN student_assignments a ON a.student_id=s.id AND a.ended_at IS NULL LEFT JOIN rooms r ON r.id=a.room_id WHERE i.billing_month=? ORDER BY s.full_name",start);
            writeMap(wb,"Paid Students",new String[]{"Student ID","Name","Room","Fee","Paid"},"SELECT s.student_code AS `Student ID`,s.full_name AS Name,COALESCE(r.room_number,'') AS Room,i.fee_amount AS Fee,i.paid_amount AS Paid FROM fee_invoices i JOIN students s ON s.id=i.student_id LEFT JOIN student_assignments a ON a.student_id=s.id AND a.ended_at IS NULL LEFT JOIN rooms r ON r.id=a.room_id WHERE i.billing_month=? AND i.paid_amount>=i.fee_amount ORDER BY s.full_name",start);
            writeMap(wb,"Unpaid and Overdue",new String[]{"Student ID","Name","Room","Balance","Due date","Status"},"SELECT s.student_code AS `Student ID`,s.full_name AS Name,COALESCE(r.room_number,'') AS Room,(i.fee_amount-i.paid_amount) AS Balance,i.due_date AS `Due date`,IF(i.due_date<CURDATE(),'OVERDUE','UNPAID') AS Status FROM fee_invoices i JOIN students s ON s.id=i.student_id LEFT JOIN student_assignments a ON a.student_id=s.id AND a.ended_at IS NULL LEFT JOIN rooms r ON r.id=a.room_id WHERE i.billing_month=? AND i.paid_amount<i.fee_amount ORDER BY i.due_date",start);
            writeMap(wb,"Pending Payments",new String[]{"Payment ID","Student ID","Name","Month","Amount","Date","Reference"},"SELECT p.id AS `Payment ID`,s.student_code AS `Student ID`,s.full_name AS Name,i.billing_month AS Month,p.amount AS Amount,p.payment_date AS Date,p.reference AS Reference FROM payment_submissions p JOIN students s ON s.id=p.student_id JOIN fee_invoices i ON i.id=p.invoice_id WHERE p.status='PENDING' AND p.payment_date>=? AND p.payment_date<? ORDER BY p.payment_date",start,end);
            writeMap(wb,"Rejected Payments",new String[]{"Payment ID","Student ID","Name","Amount","Date","Reason"},"SELECT p.id AS `Payment ID`,s.student_code AS `Student ID`,s.full_name AS Name,p.amount AS Amount,p.payment_date AS Date,p.rejection_reason AS Reason FROM payment_submissions p JOIN students s ON s.id=p.student_id WHERE p.status='REJECTED' AND p.payment_date>=? AND p.payment_date<? ORDER BY p.payment_date",start,end);
            writeMap(wb,"Expense Details",new String[]{"Date","Category","Amount","Description","Vendor"},"SELECT e.expense_date AS Date,c.name AS Category,e.amount AS Amount,e.description AS Description,e.vendor AS Vendor FROM expenses e JOIN expense_categories c ON c.id=e.category_id WHERE e.reversed_at IS NULL AND e.expense_date>=? AND e.expense_date<? ORDER BY e.expense_date,e.id",start,end);
            writeMap(wb,"Salary and Advances",new String[]{"Employee","Salary period","Amount due","Amount paid","Advance issued","Advance outstanding"},"SELECT e.full_name AS Employee,o.salary_period AS `Salary period`,o.amount_due AS `Amount due`,o.amount_paid AS `Amount paid`,0 AS `Advance issued`,0 AS `Advance outstanding` FROM salary_obligations o JOIN employees e ON e.id=o.employee_id WHERE o.salary_period>=? AND o.salary_period<? UNION ALL SELECT e.full_name, a.issued_date,0,0,a.amount,(a.amount-a.repaid_amount) FROM employee_advances a JOIN employees e ON e.id=a.employee_id WHERE a.issued_date>=? AND a.issued_date<?",start,end,start,end);
            writeMap(wb,"Owner Withdrawals",new String[]{"Date","Amount","Reason","Method"},"SELECT withdrawal_date AS Date,amount AS Amount,reason AS Reason,method AS Method FROM owner_withdrawals WHERE withdrawal_date>=? AND withdrawal_date<? ORDER BY withdrawal_date",start,end);
            writeMap(wb,"Room Occupancy",new String[]{"Room","Section","Capacity","Occupied","Available"},"SELECT r.room_number AS Room,r.section_name AS Section,r.capacity AS Capacity,COUNT(a.id) AS Occupied,r.capacity-COUNT(a.id) AS Available FROM rooms r LEFT JOIN student_assignments a ON a.room_id=r.id AND a.ended_at IS NULL WHERE r.status='ACTIVE' GROUP BY r.id ORDER BY r.room_number");
            for(Sheet sheet:wb) {sheet.createFreezePane(0,1);}
            wb.write(out);return out.toByteArray();
        }
    }

    private void writeMap(Workbook wb,String name,String[] headers,String sql,Object...args) {
        Sheet sheet=wb.createSheet(name);Row head=sheet.createRow(0);CellStyle style=wb.createCellStyle();Font f=wb.createFont();f.setBold(true);f.setColor(IndexedColors.WHITE.getIndex());style.setFont(f);style.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        for(int i=0;i<headers.length;i++){Cell c=head.createCell(i);c.setCellValue(headers[i]);c.setCellStyle(style);sheet.setColumnWidth(i,Math.min(12000,Math.max(3600,headers[i].length()*550)));}
        List<Map<String,Object>> rows=db.queryForList(sql,args);
        for(int r=0;r<rows.size();r++){Row row=sheet.createRow(r+1);Map<String,Object> data=rows.get(r);
            for(int c=0;c<headers.length;c++){
                String key=findMatchingKey(data,headers[c]);
                Object value=data.get(key);
                Cell cell=row.createCell(c);
                if(value instanceof Number n)cell.setCellValue(n.doubleValue());
                else if(value instanceof Date d)cell.setCellValue(d.toLocalDate().toString());
                else if(value instanceof java.sql.Timestamp t)cell.setCellValue(t.toLocalDateTime().toString());
                else if(value==null)cell.setCellValue("");
                else cell.setCellValue(safe(String.valueOf(value)));
            }
        }
        if(rows.isEmpty())sheet.createRow(1).createCell(0).setCellValue("No records for this period");
    }

    private String findMatchingKey(Map<String,Object> row,String expectedHeader) {
        if (row == null || expectedHeader == null || expectedHeader.isBlank()) return null;
        String normalized = normalizeHeader(expectedHeader);
        for (String key : row.keySet()) {
            if (normalizeHeader(key).equals(normalized)) return key;
        }
        return null;
    }

    private String normalizeHeader(String value) {
        if (value == null) return "";
        return value.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private Object scalar(String sql,Object...args){return db.queryForObject(sql,Object.class,args);}
    private String html(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    private void embedLogo(Workbook workbook,Sheet sheet) {
        try {
            Long id=db.queryForObject("SELECT logo_file_id FROM branding_settings WHERE id=1",Long.class);
            if(id==null)return;
            Map<String,Object> f=db.queryForMap("SELECT storage_key AS storageKey,content_type AS contentType FROM files WHERE id=?",id);
            String type=(String)f.get("contentType");int pictureType=type.equals("image/png")?Workbook.PICTURE_TYPE_PNG:type.equals("image/jpeg")?Workbook.PICTURE_TYPE_JPEG:-1;
            if(pictureType<0)return;
            Path path=fileDir.resolve((String)f.get("storageKey")).normalize();if(!path.startsWith(fileDir)||!Files.exists(path))return;
            int picture=workbook.addPicture(Files.readAllBytes(path),pictureType);var helper=workbook.getCreationHelper();var drawing=sheet.createDrawingPatriarch();var anchor=helper.createClientAnchor();anchor.setCol1(3);anchor.setRow1(0);anchor.setCol2(5);anchor.setRow2(3);drawing.createPicture(anchor,picture).resize(.8);
        } catch(Exception ignored) { /* A missing/unsupported logo must not prevent monthly reporting. */ }
    }
    private Path emailLogoPath() {
        try {
            Long id=db.queryForObject("SELECT logo_file_id FROM branding_settings WHERE id=1",Long.class);
            if(id==null)return null;
            Map<String,Object> f=db.queryForMap("SELECT storage_key AS storageKey,content_type AS contentType FROM files WHERE id=?",id);
            String type=Objects.toString(f.get("contentType"),"");
            if(!type.equals("image/png")&&!type.equals("image/jpeg"))return null;
            Path path=fileDir.resolve((String)f.get("storageKey")).normalize();
            return path.startsWith(fileDir)&&Files.exists(path)?path:null;
        } catch(Exception ignored) { return null; /* Missing or unsupported logos must not prevent email delivery. */ }
    }
    private void requireOwner(Authentication a){if(a==null||!db.queryForObject("SELECT COUNT(*) FROM users WHERE email=? AND role='OWNER'",Integer.class,a.getName()).equals(1))throw new AccessDeniedException("Owner access required");}
    private long attemptCount(long id){Integer n=db.queryForObject("SELECT COUNT(*) FROM email_delivery_attempts WHERE report_id=?",Integer.class,id);return n==null?0:n;}
    private void recordAttempt(long id,String status,String error){db.update("INSERT INTO email_delivery_attempts(report_id,status,error_message) VALUES(?,?,?)",id,status,error);}
    private Map<String,Object> report(long id){return db.queryForMap("SELECT id,reporting_month AS reportingMonth,generated_at AS generatedAt,generation_kind AS generationKind,delivery_status AS deliveryStatus,delivery_error AS deliveryError,delivered_at AS deliveredAt FROM monthly_reports WHERE id=?",id);}
    private long insert(String sql,Object...args){var key=new org.springframework.jdbc.support.GeneratedKeyHolder();db.update(c->{var s=c.prepareStatement(sql,java.sql.Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},key);return Objects.requireNonNull(key.getKey()).longValue();}
    private String safe(String value){if(value==null)return "";String s=value.replaceAll("[\\p{Cntrl}&&[^\\t]]","");return !s.isEmpty()&&"=+-@".indexOf(s.charAt(0))>=0?"'"+s:s;}
}
