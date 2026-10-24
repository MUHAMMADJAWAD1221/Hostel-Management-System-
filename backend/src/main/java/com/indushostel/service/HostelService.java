package com.indushostel.service;

import com.indushostel.api.Requests;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

@Service
public class HostelService {
    private static final String BRAND = "INDUS HOSTEL SERVICE BADIN";
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;

    public HostelService(JdbcTemplate db, PasswordEncoder passwords) { this.db = db; this.passwords = passwords; }

    public Map<String,Object> current(Authentication auth) {
        Map<String,Object> u = user(auth);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("id", u.get("id")); out.put("email", u.get("email")); out.put("displayName", u.get("display_name"));
        out.put("role", u.get("role")); out.put("studentId", u.get("student_id"));
        return out;
    }

    public Map<String,Object> branding() {
        return db.queryForMap("SELECT hostel_name AS hostelName,logo_file_id AS logoFileId FROM branding_settings WHERE id=1");
    }

    public Map<String,Object> ownerBranding(Authentication auth) { requireAny(auth,"OWNER");return db.queryForMap("SELECT hostel_name AS hostelName,logo_file_id AS logoFileId,owner_email AS ownerEmail FROM branding_settings WHERE id=1"); }

    public Map<String,Object> publicLogoFile() {
        Long id=db.queryForObject("SELECT logo_file_id FROM branding_settings WHERE id=1",Long.class);
        if(id==null)return null;
        Map<String,Object> file=db.queryForMap("SELECT id,storage_key AS storageKey,content_type AS contentType,original_name AS originalName FROM files WHERE id=?",id);
        if(!String.valueOf(file.get("contentType")).startsWith("image/"))return null;
        return file;
    }

    @Transactional
    public Map<String,Object> saveBranding(Requests.Branding req, Authentication auth) {
        Map<String,Object> actor = require(auth,"OWNER");
        if (req.logoFileId()!=null) {ensureFileExists(req.logoFileId());Map<String,Object> file=db.queryForMap("SELECT content_type AS contentType FROM files WHERE id=?",req.logoFileId());if(!Set.of("image/png","image/jpeg","image/webp").contains(file.get("contentType")))throw new IllegalArgumentException("Logo file must be PNG, JPEG, or WebP");}
        db.update("UPDATE branding_settings SET hostel_name=?,owner_email=?,logo_file_id=? WHERE id=1", req.hostelName().trim(), blankNull(req.ownerEmail()), req.logoFileId());
        audit(actor,"BRANDING_UPDATED","branding",1L,null,Map.of("hostelName",req.hostelName()));
        return ownerBranding(auth);
    }

    public Map<String,Object> dashboard(Authentication auth) {
        Map<String,Object> u=user(auth); String role=(String)u.get("role");
        if ("STUDENT".equals(role)) {
            Long sid=longValue(u.get("student_id"));
            Map<String,Object> s=studentById(sid);
            List<Map<String,Object>> invoices=studentInvoices(sid,0,100);
            return Map.of("student",s,"invoices",invoices,"notifications",notifications(auth,0,10),"summary",Map.of(
                "monthlyFee",s.get("monthlyFee"),"balance",invoices.stream().map(x->decimal(x.get("balance"))).reduce(BigDecimal.ZERO,BigDecimal::add),
                "pendingPayments",db.queryForObject("SELECT COUNT(*) FROM payment_submissions WHERE student_id=? AND status='PENDING'",Integer.class,sid)));
        }
        BigDecimal outstanding=money("SELECT COALESCE(SUM(fee_amount-paid_amount),0) FROM fee_invoices WHERE fee_amount>paid_amount");
        BigDecimal expenses=money("SELECT COALESCE(SUM(amount),0) FROM expenses WHERE reversed_at IS NULL AND expense_date>=DATE_FORMAT(CURDATE(),'%Y-%m-01')");
        BigDecimal collections=money("SELECT COALESCE(SUM(amount),0) FROM payment_submissions WHERE status='APPROVED' AND payment_date>=DATE_FORMAT(CURDATE(),'%Y-%m-01')");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("activeStudents", count("SELECT COUNT(*) FROM students WHERE status='ACTIVE'"));
        result.put("capacity",count("SELECT COALESCE(SUM(capacity),0) FROM rooms WHERE status='ACTIVE'"));
        result.put("occupiedBeds",count("SELECT COUNT(*) FROM student_assignments WHERE ended_at IS NULL"));
        result.put("availableBeds",count("SELECT COALESCE(SUM(r.capacity),0)-COUNT(a.id) FROM rooms r LEFT JOIN student_assignments a ON a.room_id=r.id AND a.ended_at IS NULL WHERE r.status='ACTIVE'"));
        int capacity=((Number)result.get("capacity")).intValue(), occupied=((Number)result.get("occupiedBeds")).intValue();
        result.put("occupancyPercent",capacity==0?0:Math.round((occupied*1000.0/capacity))/10.0);
        result.put("feesDue",money("SELECT COALESCE(SUM(fee_amount),0) FROM fee_invoices WHERE billing_month=DATE_FORMAT(CURDATE(),'%Y-%m-01')"));
        result.put("verifiedCollections",collections);
        result.put("pendingPayments",count("SELECT COUNT(*) FROM payment_submissions WHERE status='PENDING'"));
        result.put("outstanding",outstanding);
        result.put("overdue",money("SELECT COALESCE(SUM(fee_amount-paid_amount),0) FROM fee_invoices WHERE due_date<CURDATE() AND fee_amount>paid_amount"));
        result.put("expenses",expenses);
        result.put("withdrawals",money("SELECT COALESCE(SUM(amount),0) FROM owner_withdrawals WHERE withdrawal_date>=DATE_FORMAT(CURDATE(),'%Y-%m-01')"));
        result.put("advancesOutstanding",money("SELECT COALESCE(SUM(amount-repaid_amount),0) FROM employee_advances"));
        result.put("operatingPosition",FinancialRules.operatingPosition(collections,expenses));
        result.put("upcomingBills",count("SELECT COUNT(*) FROM bill_payments WHERE status<>'PAID' AND due_date<=DATE_ADD(CURDATE(),INTERVAL 14 DAY)"));
        result.put("monthly",monthlyTrend());
        result.put("categories",db.queryForList("SELECT c.name AS name,COALESCE(SUM(e.amount),0) AS value FROM expense_categories c LEFT JOIN expenses e ON e.category_id=c.id AND e.reversed_at IS NULL AND e.expense_date>=DATE_SUB(CURDATE(),INTERVAL 5 MONTH) GROUP BY c.id,c.name ORDER BY value DESC"));
        result.put("recentPayments",payments(auth,0,6,null));
        result.put("recentExpenses",expenses(auth,0,6,null,null));
        result.put("upcomingBillList",db.queryForList("SELECT id,category,vendor,expected_amount AS expectedAmount,due_date AS dueDate,status FROM bill_payments WHERE status<>'PAID' ORDER BY due_date LIMIT 5"));
        if ("MANAGER".equals(role)) result.put("recentStudents",students(auth,0,5,null,null));
        return result;
    }

    private List<Map<String,Object>> monthlyTrend() {
        List<Map<String,Object>> rows=new ArrayList<>();
        YearMonth month=YearMonth.now().minusMonths(5);
        for(int i=0;i<6;i++,month=month.plusMonths(1)) {
            LocalDate start=month.atDay(1), end=month.plusMonths(1).atDay(1);
            BigDecimal paid=db.queryForObject("SELECT COALESCE(SUM(amount),0) FROM payment_submissions WHERE status='APPROVED' AND payment_date>=? AND payment_date<?",BigDecimal.class,start,end);
            BigDecimal spent=db.queryForObject("SELECT COALESCE(SUM(amount),0) FROM expenses WHERE reversed_at IS NULL AND expense_date>=? AND expense_date<?",BigDecimal.class,start,end);
            rows.add(Map.of("month",month.getMonth().name().substring(0,3)+" "+month.getYear(),"collections",paid,"expenses",spent));
        }
        return rows;
    }

    public List<Map<String,Object>> rooms(int page,int size,Authentication auth) {
        requireAny(auth,"OWNER","MANAGER"); int limit=pageSize(size),offset=pageIndex(page)*limit;
        return db.queryForList("SELECT r.id,r.room_number AS roomNumber,r.section_name AS section,r.capacity,COUNT(a.id) AS occupied,r.capacity-COUNT(a.id) AS available,r.status,r.notes FROM rooms r LEFT JOIN student_assignments a ON a.room_id=r.id AND a.ended_at IS NULL GROUP BY r.id ORDER BY r.room_number LIMIT ? OFFSET ?",limit,offset);
    }

    @Transactional
    public Map<String,Object> createRoom(Requests.Room req,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER");
        long id=insert("INSERT INTO rooms(room_number,section_name,capacity,notes) VALUES(?,?,?,?)",req.roomNumber().trim(),blankNull(req.section()),req.capacity(),blankNull(req.notes()));
        audit(actor,"ROOM_CREATED","room",id,null,Map.of("roomNumber",req.roomNumber()));
        return db.queryForMap("SELECT id,room_number AS roomNumber,section_name AS section,capacity,status,notes FROM rooms WHERE id=?",id);
    }

    @Transactional
    public Map<String,Object> updateRoom(long id,Requests.Room req,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER");
        int occupied=count("SELECT COUNT(*) FROM student_assignments WHERE room_id=? AND ended_at IS NULL",id);
        if(req.capacity()<occupied) throw new IllegalArgumentException("Capacity cannot be lower than current occupied beds");
        db.update("UPDATE rooms SET room_number=?,section_name=?,capacity=?,notes=? WHERE id=?",req.roomNumber().trim(),blankNull(req.section()),req.capacity(),blankNull(req.notes()),id);
        audit(actor,"ROOM_UPDATED","room",id,null,Map.of("capacity",req.capacity()));
        return rooms(0,100,auth).stream().filter(row->longValue(row.get("id"))==id).findFirst().orElseThrow(()->new IllegalArgumentException("Room not found"));
    }

    @Transactional
    public void deactivateRoom(long id,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER");
        if(count("SELECT COUNT(*) FROM student_assignments WHERE room_id=? AND ended_at IS NULL",id)>0)throw new IllegalArgumentException("Check out or transfer the current occupants before deactivating this room");
        if(db.update("UPDATE rooms SET status='INACTIVE' WHERE id=?",id)==0)throw new IllegalArgumentException("Room not found");
        audit(actor,"ROOM_DEACTIVATED","room",id,null,Map.of());
    }

    public List<Map<String,Object>> students(Authentication auth,int page,int size,String search,String status) {
        Map<String,Object> u=user(auth); int limit=pageSize(size),offset=pageIndex(page)*limit;
        if("STUDENT".equals(u.get("role"))) return List.of(studentById(longValue(u.get("student_id"))));
        requireAny(auth,"OWNER","MANAGER"); String q=search==null?"%":"%"+search.trim()+"%"; String st=blankNull(status);
        return db.queryForList("SELECT s.id,s.student_code AS studentCode,s.full_name AS fullName,s.contact,s.monthly_fee AS monthlyFee,s.due_day AS dueDay,s.status,s.check_in_date AS checkInDate,s.photo_file_id AS photoFileId,r.room_number AS room,a.bed_number AS bedNumber,u.email FROM students s LEFT JOIN student_assignments a ON a.student_id=s.id AND a.ended_at IS NULL LEFT JOIN rooms r ON r.id=a.room_id LEFT JOIN users u ON u.student_id=s.id WHERE (s.student_code LIKE ? OR s.full_name LIKE ?) AND (? IS NULL OR s.status=?) ORDER BY s.id DESC LIMIT ? OFFSET ?",q,q,st,st,limit,offset);
    }

    @Transactional
    public Map<String,Object> createStudent(Requests.Student req,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER");
        if((req.roomId()==null)!=(req.bedNumber()==null || req.bedNumber().isBlank())) throw new IllegalArgumentException("Choose both a room and bed, or leave both empty");
        long id=insert("INSERT INTO students(student_code,full_name,contact,emergency_contact,address,registration_date,check_in_date,monthly_fee,due_day,notes) VALUES(?,?,?,?,?,?,?,?,?,?)",
            req.studentCode().trim(),req.fullName().trim(),req.contact().trim(),blankNull(req.emergencyContact()),blankNull(req.address()),req.registrationDate(),req.checkInDate(),req.monthlyFee(),req.dueDay(),blankNull(req.notes()));
        db.update("INSERT INTO users(email,password_hash,display_name,role,student_id) VALUES(?,?,?,'STUDENT',?)",req.email().trim().toLowerCase(),passwords.encode(req.initialPassword()),req.fullName().trim(),id);
        if(req.roomId()!=null) assign(id,new Requests.Assignment(req.roomId(),req.bedNumber(),Objects.requireNonNullElse(req.checkInDate(),req.registrationDate())),actor);
        audit(actor,"STUDENT_CREATED","student",id,null,Map.of("studentCode",req.studentCode()));
        return studentById(id);
    }

    @Transactional
    public Map<String,Object> updateStudent(long id,Requests.StudentUpdate req,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER");
        Map<String,Object> old=studentById(id);
        db.update("UPDATE students SET full_name=?,contact=?,monthly_fee=?,due_day=?,emergency_contact=?,address=?,notes=? WHERE id=?",req.fullName().trim(),req.contact().trim(),req.monthlyFee(),req.dueDay(),blankNull(req.emergencyContact()),blankNull(req.address()),blankNull(req.notes()),id);
        db.update("UPDATE users SET display_name=? WHERE student_id=?",req.fullName().trim(),id);
        if(!decimal(old.get("monthlyFee")).equals(req.monthlyFee())) audit(actor,"STUDENT_FEE_CHANGED","student",id,"Monthly fee updated; prior invoices remain unchanged",Map.of("previous",old.get("monthlyFee"),"current",req.monthlyFee()));
        else audit(actor,"STUDENT_UPDATED","student",id,null,Map.of());
        return studentById(id);
    }

    @Transactional
    public Map<String,Object> assignStudent(long studentId,Requests.Assignment req,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER"); assign(studentId,req,actor);
        audit(actor,"ROOM_ASSIGNED","student",studentId,null,Map.of("roomId",req.roomId(),"bedNumber",req.bedNumber()));
        return studentById(studentId);
    }

    private void assign(long studentId,Requests.Assignment req,Map<String,Object> actor) {
        Map<String,Object> room;
        try { room=db.queryForMap("SELECT id,capacity,status FROM rooms WHERE id=? FOR UPDATE",req.roomId()); }
        catch(EmptyResultDataAccessException e){throw new IllegalArgumentException("Room not found");}
        if(!"ACTIVE".equals(room.get("status"))) throw new IllegalArgumentException("This room is inactive");
        try { int bed=Integer.parseInt(req.bedNumber()); if(bed<1 || bed>((Number)room.get("capacity")).intValue()) throw new IllegalArgumentException("Bed number must be within the room capacity"); }
        catch(NumberFormatException e){throw new IllegalArgumentException("Use a numeric bed number within the room capacity");}
        if(count("SELECT COUNT(*) FROM student_assignments WHERE room_id=? AND ended_at IS NULL AND student_id<>?",req.roomId(),studentId)>=((Number)room.get("capacity")).intValue()) throw new IllegalArgumentException("No beds are available in this room");
        db.update("UPDATE student_assignments SET ended_at=? WHERE student_id=? AND ended_at IS NULL",req.date(),studentId);
        db.update("INSERT INTO student_assignments(student_id,room_id,bed_number,started_at,created_by) VALUES(?,?,?,?,?)",studentId,req.roomId(),req.bedNumber(),req.date(),actor.get("id"));
        db.update("UPDATE students SET check_in_date=COALESCE(check_in_date,?),status='ACTIVE' WHERE id=?",req.date(),studentId);
    }

    @Transactional
    public Map<String,Object> checkout(long studentId,LocalDate date,String reason,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER");
        studentById(studentId);
        db.update("UPDATE student_assignments SET ended_at=? WHERE student_id=? AND ended_at IS NULL",date,studentId);
        db.update("UPDATE students SET status='CHECKED_OUT',check_out_date=? WHERE id=?",date,studentId);
        audit(actor,"STUDENT_CHECKED_OUT","student",studentId,reason,Map.of("date",date.toString()));
        return studentById(studentId);
    }

    public List<Map<String,Object>> invoices(Authentication auth,int page,int size) {
        Map<String,Object> u=user(auth); if("STUDENT".equals(u.get("role"))) return studentInvoices(longValue(u.get("student_id")),page,size);
        requireAny(auth,"OWNER","MANAGER"); int limit=pageSize(size),offset=pageIndex(page)*limit;
        return db.queryForList("SELECT i.id,i.student_id AS studentId,s.student_code AS studentCode,s.full_name AS studentName,r.room_number AS room,i.billing_month AS billingMonth,i.fee_amount AS feeAmount,i.paid_amount AS paidAmount,(i.fee_amount-i.paid_amount) AS balance,i.due_date AS dueDate,CASE WHEN i.fee_amount=i.paid_amount THEN 'PAID' WHEN i.due_date<CURDATE() THEN 'OVERDUE' WHEN EXISTS(SELECT 1 FROM payment_submissions p WHERE p.invoice_id=i.id AND p.status='PENDING') THEN 'PENDING_VERIFICATION' WHEN i.paid_amount>0 THEN 'PARTIALLY_PAID' ELSE 'UNPAID' END AS status FROM fee_invoices i JOIN students s ON s.id=i.student_id LEFT JOIN student_assignments a ON a.student_id=s.id AND a.ended_at IS NULL LEFT JOIN rooms r ON r.id=a.room_id ORDER BY i.billing_month DESC,s.full_name LIMIT ? OFFSET ?",limit,offset);
    }

    private List<Map<String,Object>> studentInvoices(long sid,int page,int size) {
        int limit=pageSize(size),offset=pageIndex(page)*limit;
        return db.queryForList("SELECT i.id,i.billing_month AS billingMonth,i.fee_amount AS feeAmount,i.paid_amount AS paidAmount,(i.fee_amount-i.paid_amount) AS balance,i.due_date AS dueDate,CASE WHEN i.fee_amount=i.paid_amount THEN 'PAID' WHEN i.due_date<CURDATE() THEN 'OVERDUE' WHEN EXISTS(SELECT 1 FROM payment_submissions p WHERE p.invoice_id=i.id AND p.status='PENDING') THEN 'PENDING_VERIFICATION' WHEN i.paid_amount>0 THEN 'PARTIALLY_PAID' ELSE 'UNPAID' END AS status FROM fee_invoices i WHERE i.student_id=? ORDER BY i.billing_month DESC LIMIT ? OFFSET ?",sid,limit,offset);
    }

    @Transactional
    public int generateInvoices(YearMonth period,Authentication auth) {
        requireAny(auth,"OWNER","MANAGER"); return generateInvoicesFor(period);
    }

    public int generateInvoicesFor(YearMonth period) {
        LocalDate month=period.atDay(1);
        return db.update("INSERT IGNORE INTO fee_invoices(student_id,billing_month,fee_amount,due_date) SELECT id,?,monthly_fee,DATE_ADD(?,INTERVAL LEAST(due_day,DAY(LAST_DAY(?)))-1 DAY) FROM students WHERE status='ACTIVE' AND (check_in_date IS NULL OR check_in_date<=LAST_DAY(?))",month,month,month,month);
    }

    @Transactional
    public Map<String,Object> submitPayment(Requests.Payment req,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"STUDENT"); long sid=longValue(actor.get("student_id"));
        List<Map<String,Object>> prior=db.queryForList("SELECT id FROM payment_submissions WHERE student_id=? AND idempotency_key=?",sid,req.idempotencyKey());
        if(!prior.isEmpty())return paymentById(((Number)prior.get(0).get("id")).longValue());
        Map<String,Object> invoice;
        try { invoice=db.queryForMap("SELECT id,student_id,fee_amount,paid_amount FROM fee_invoices WHERE id=? FOR UPDATE",req.invoiceId()); }
        catch(EmptyResultDataAccessException e){throw new IllegalArgumentException("Fee record not found");}
        if(longValue(invoice.get("student_id"))!=sid) throw new AccessDeniedException("Forbidden");
        BigDecimal remaining=FinancialRules.remaining(decimal(invoice.get("fee_amount")),decimal(invoice.get("paid_amount")));
        BigDecimal pending=db.queryForObject("SELECT COALESCE(SUM(amount),0) FROM payment_submissions WHERE invoice_id=? AND status='PENDING'",BigDecimal.class,req.invoiceId());
        if(req.amount().compareTo(remaining.subtract(pending))>0) throw new IllegalArgumentException("Amount is greater than the remaining unsubmitted balance");
        if(req.proofFileId()!=null) ensureFileUploadedBy(req.proofFileId(),actor.get("id"));
        long id=insert("INSERT INTO payment_submissions(invoice_id,student_id,amount,payment_date,method,reference,proof_file_id,idempotency_key) VALUES(?,?,?,?,?,?,?,?)",req.invoiceId(),sid,req.amount(),req.paymentDate(),blankNull(req.method()),blankNull(req.reference()),req.proofFileId(),req.idempotencyKey());
        db.update("INSERT INTO notifications(user_id,kind,title,message,related_type,related_id,dedupe_key) SELECT id,'PAYMENT_SUBMITTED','Payment sent for verification',CONCAT('A student submitted a payment for invoice #',?), 'payment',?,CONCAT('payment-submitted-',?) FROM users WHERE role IN ('OWNER','MANAGER') ON DUPLICATE KEY UPDATE id=id",id,id,id);
        audit(actor,"PAYMENT_SUBMITTED","payment",id,null,Map.of("invoiceId",req.invoiceId(),"amount",req.amount()));
        return paymentById(id);
    }

    public List<Map<String,Object>> payments(Authentication auth,int page,int size,String status) {
        Map<String,Object> u=user(auth); int limit=pageSize(size),offset=pageIndex(page)*limit;
        String st=blankNull(status);
        if("STUDENT".equals(u.get("role"))) return db.queryForList("SELECT p.id,p.invoice_id AS invoiceId,i.billing_month AS billingMonth,p.amount,p.payment_date AS paymentDate,p.method,p.reference,p.status,p.rejection_reason AS rejectionReason,p.submitted_at AS submittedAt,r.receipt_number AS receiptNumber FROM payment_submissions p JOIN fee_invoices i ON i.id=p.invoice_id LEFT JOIN receipts r ON r.payment_submission_id=p.id WHERE p.student_id=? ORDER BY p.submitted_at DESC LIMIT ? OFFSET ?",u.get("student_id"),limit,offset);
        requireAny(auth,"OWNER","MANAGER");
        return db.queryForList("SELECT p.id,p.invoice_id AS invoiceId,p.student_id AS studentId,s.student_code AS studentCode,s.full_name AS studentName,i.billing_month AS billingMonth,p.amount,p.payment_date AS paymentDate,p.method,p.reference,p.proof_file_id AS proofFileId,p.status,p.rejection_reason AS rejectionReason,p.submitted_at AS submittedAt,r.receipt_number AS receiptNumber FROM payment_submissions p JOIN students s ON s.id=p.student_id JOIN fee_invoices i ON i.id=p.invoice_id LEFT JOIN receipts r ON r.payment_submission_id=p.id WHERE (? IS NULL OR p.status=?) ORDER BY p.submitted_at DESC LIMIT ? OFFSET ?",st,st,limit,offset);
    }

    @Transactional
    public Map<String,Object> approvePayment(long id,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER");
        Map<String,Object> p;
        try {p=db.queryForMap("SELECT * FROM payment_submissions WHERE id=? FOR UPDATE",id);}catch(EmptyResultDataAccessException e){throw new IllegalArgumentException("Payment submission not found");}
        if("APPROVED".equals(p.get("status"))) return paymentById(id);
        if(!"PENDING".equals(p.get("status"))) throw new IllegalArgumentException("Only pending payments can be approved");
        Map<String,Object> invoice=db.queryForMap("SELECT * FROM fee_invoices WHERE id=? FOR UPDATE",p.get("invoice_id"));
        BigDecimal newPaid=FinancialRules.applyApprovedPayment(decimal(invoice.get("fee_amount")),decimal(invoice.get("paid_amount")),decimal(p.get("amount")));
        db.update("UPDATE payment_submissions SET status='APPROVED',reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'",actor.get("id"),id);
        db.update("UPDATE fee_invoices SET paid_amount=?,status=? WHERE id=?",newPaid,newPaid.compareTo(decimal(invoice.get("fee_amount")))==0?"PAID":"PARTIALLY_PAID",p.get("invoice_id"));
        db.update("INSERT INTO ledger_entries(entry_type,direction,amount,source_type,source_id,occurred_on,recorded_by,description) VALUES('FEE_COLLECTION','IN',?,'payment',?,?,?,?)",p.get("amount"),id,p.get("payment_date"),actor.get("id"),"Verified student fee payment");
        BigDecimal previousBalance=decimal(invoice.get("fee_amount")).subtract(decimal(invoice.get("paid_amount")));
        BigDecimal remainingBalance=decimal(invoice.get("fee_amount")).subtract(newPaid);
        long rid=insert("INSERT INTO receipts(receipt_number,payment_submission_id,previous_balance,remaining_balance,issued_by) VALUES(?,?,?,?,?)","TEMP",id,previousBalance,remainingBalance,actor.get("id"));
        String receipt="IHS-"+LocalDate.now().getYear()+"-"+String.format("%06d",rid);
        db.update("UPDATE receipts SET receipt_number=? WHERE id=?",receipt,rid);
        Long studentUser=db.queryForObject("SELECT id FROM users WHERE student_id=?",Long.class,p.get("student_id"));
        db.update("INSERT INTO notifications(user_id,kind,title,message,related_type,related_id,dedupe_key) VALUES(?,'PAYMENT_APPROVED','Payment approved','Your payment was verified. Your official receipt is ready.','receipt',?,CONCAT('payment-approved-',?)) ON DUPLICATE KEY UPDATE id=id",studentUser,rid,id);
        audit(actor,"PAYMENT_APPROVED","payment",id,null,Map.of("receiptId",rid,"amount",p.get("amount")));
        return paymentById(id);
    }

    @Transactional
    public Map<String,Object> rejectPayment(long id,Requests.Rejection req,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER");
        Map<String,Object> p;
        try{p=db.queryForMap("SELECT * FROM payment_submissions WHERE id=? FOR UPDATE",id);}catch(EmptyResultDataAccessException e){throw new IllegalArgumentException("Payment submission not found");}
        if(!"PENDING".equals(p.get("status"))) throw new IllegalArgumentException("Only pending payments can be rejected");
        db.update("UPDATE payment_submissions SET status='REJECTED',rejection_reason=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=?",req.reason().trim(),actor.get("id"),id);
        Long userId=db.queryForObject("SELECT id FROM users WHERE student_id=?",Long.class,p.get("student_id"));
        db.update("INSERT INTO notifications(user_id,kind,title,message,related_type,related_id,dedupe_key) VALUES(?,'PAYMENT_REJECTED','Payment needs attention',CONCAT('Your payment was not approved: ',?),'payment',?,CONCAT('payment-rejected-',?)) ON DUPLICATE KEY UPDATE id=id",userId,req.reason().trim(),id,id);
        audit(actor,"PAYMENT_REJECTED","payment",id,req.reason(),Map.of());
        return paymentById(id);
    }

    @Transactional
    public Map<String,Object> reversePayment(long id,String reason,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER");
        Map<String,Object> p=db.queryForMap("SELECT * FROM payment_submissions WHERE id=? FOR UPDATE",id);
        if(!"APPROVED".equals(p.get("status"))) throw new IllegalArgumentException("Only an approved payment can be reversed");
        db.update("UPDATE payment_submissions SET status='REVERSED' WHERE id=?",id);
        db.update("UPDATE fee_invoices SET status=IF(paid_amount-?<=0,'UNPAID','PARTIALLY_PAID'),paid_amount=paid_amount-? WHERE id=?",p.get("amount"),p.get("amount"),p.get("invoice_id"));
        db.update("UPDATE receipts SET voided_at=CURRENT_TIMESTAMP,reversal_reason=? WHERE payment_submission_id=?",reason,id);
        db.update("UPDATE ledger_entries SET reversed_at=CURRENT_TIMESTAMP,reversal_reason=? WHERE source_type='payment' AND source_id=?",reason,id);
        audit(actor,"PAYMENT_REVERSED","payment",id,reason,Map.of("amount",p.get("amount")));
        return paymentById(id);
    }

    public byte[] receiptPdf(long paymentId,Authentication auth,ReceiptService receipts) { return receipts.create(paymentId,auth); }

    public List<Map<String,Object>> expenses(Authentication auth,int page,int size,String from,String to) {
        requireAny(auth,"OWNER","MANAGER"); int limit=pageSize(size),offset=pageIndex(page)*limit;
        LocalDate f=parseDate(from),t=parseDate(to);
        return db.queryForList("SELECT e.id,c.name AS category,e.amount,e.expense_date AS expenseDate,e.description,e.vendor,e.payment_method AS paymentMethod,e.bill_file_id AS billFileId,e.reversed_at AS reversedAt FROM expenses e JOIN expense_categories c ON c.id=e.category_id WHERE (? IS NULL OR e.expense_date>=?) AND (? IS NULL OR e.expense_date<=?) ORDER BY e.expense_date DESC,e.id DESC LIMIT ? OFFSET ?",f,f,t,t,limit,offset);
    }

    @Transactional
    public Map<String,Object> createExpense(Requests.Expense req,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER");
        List<Map<String,Object>> prior=db.queryForList("SELECT id FROM expenses WHERE created_by=? AND idempotency_key=?",actor.get("id"),req.idempotencyKey());
        if(!prior.isEmpty())return db.queryForMap("SELECT e.id,c.name AS category,e.amount,e.expense_date AS expenseDate,e.description,e.vendor FROM expenses e JOIN expense_categories c ON c.id=e.category_id WHERE e.id=?",prior.get(0).get("id"));
        if(req.billFileId()!=null) ensureFileUploadedBy(req.billFileId(),actor.get("id"));
        String category=db.queryForObject("SELECT name FROM expense_categories WHERE id=? AND active=TRUE",String.class,req.categoryId());
        if(Set.of("Employee salaries","Employee advances").contains(category))throw new IllegalArgumentException("Salary and advances must be recorded in the employee module");
        long id=insert("INSERT INTO expenses(category_id,amount,expense_date,description,vendor,payment_method,bill_file_id,created_by,idempotency_key) VALUES(?,?,?,?,?,?,?,?,?)",req.categoryId(),req.amount(),req.expenseDate(),req.description().trim(),blankNull(req.vendor()),blankNull(req.paymentMethod()),req.billFileId(),actor.get("id"),req.idempotencyKey());
        db.update("INSERT INTO ledger_entries(entry_type,direction,amount,source_type,source_id,occurred_on,recorded_by,description) VALUES('EXPENSE','OUT',?,'expense',?,?,?,?)",req.amount(),id,req.expenseDate(),actor.get("id"),req.description());
        audit(actor,"EXPENSE_CREATED","expense",id,null,Map.of("amount",req.amount(),"categoryId",req.categoryId()));
        return db.queryForMap("SELECT e.id,c.name AS category,e.amount,e.expense_date AS expenseDate,e.description,e.vendor FROM expenses e JOIN expense_categories c ON c.id=e.category_id WHERE e.id=?",id);
    }

    @Transactional
    public void reverseExpense(long id,String reason,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER");
        int updated=db.update("UPDATE expenses SET reversed_at=CURRENT_TIMESTAMP,reversal_reason=?,updated_by=? WHERE id=? AND reversed_at IS NULL",reason,actor.get("id"),id);
        if(updated==0) throw new IllegalArgumentException("Expense was not found or has already been reversed");
        db.update("UPDATE ledger_entries SET reversed_at=CURRENT_TIMESTAMP,reversal_reason=? WHERE source_type='expense' AND source_id=?",reason,id);
        audit(actor,"EXPENSE_REVERSED","expense",id,reason,Map.of());
    }

    public List<Map<String,Object>> categories(Authentication auth) { requireAny(auth,"OWNER","MANAGER"); return db.queryForList("SELECT id,name FROM expense_categories WHERE active=TRUE AND name NOT IN ('Employee salaries','Employee advances') ORDER BY name"); }

    public List<Map<String,Object>> employees(Authentication auth) { requireAny(auth,"OWNER","MANAGER"); return db.queryForList("SELECT id,employee_code AS employeeCode,full_name AS fullName,title,contact,joining_date AS joiningDate,salary_amount AS salaryAmount,status,notes FROM employees ORDER BY status,full_name"); }

    @Transactional
    public void deactivateEmployee(long id,Authentication auth) { Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER"); if(db.update("UPDATE employees SET status='INACTIVE' WHERE id=?",id)==0)throw new IllegalArgumentException("Employee not found");audit(actor,"EMPLOYEE_DEACTIVATED","employee",id,null,Map.of()); }

    @Transactional
    public Map<String,Object> createEmployee(Requests.Employee req,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER");
        long id=insert("INSERT INTO employees(employee_code,full_name,title,contact,joining_date,salary_amount,notes) VALUES(?,?,?,?,?,?,?)",req.employeeCode(),req.fullName(),req.title(),blankNull(req.contact()),req.joiningDate(),req.salaryAmount(),blankNull(req.notes()));
        audit(actor,"EMPLOYEE_CREATED","employee",id,null,Map.of());
        return db.queryForMap("SELECT id,employee_code AS employeeCode,full_name AS fullName,title,salary_amount AS salaryAmount,status FROM employees WHERE id=?",id);
    }

    public List<Map<String,Object>> salaries(Authentication auth,int page,int size) {
        requireAny(auth,"OWNER","MANAGER"); return db.queryForList("SELECT o.id,o.employee_id AS employeeId,e.employee_code AS employeeCode,e.full_name AS employeeName,o.salary_period AS salaryPeriod,o.amount_due AS amountDue,o.amount_paid AS amountPaid,(o.amount_due-o.amount_paid) AS outstanding FROM salary_obligations o JOIN employees e ON e.id=o.employee_id ORDER BY o.salary_period DESC LIMIT ? OFFSET ?",pageSize(size),pageIndex(page)*pageSize(size));
    }

    @Transactional
    public Map<String,Object> paySalary(Requests.SalaryPayment req,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER");
        List<Map<String,Object>> previous=db.queryForList("SELECT obligation_id AS obligationId FROM salary_payments WHERE recorded_by=? AND idempotency_key=?",actor.get("id"),req.idempotencyKey());
        if(!previous.isEmpty())return salaries(auth,0,100).stream().filter(x->longValue(x.get("id"))==longValue(previous.get(0).get("obligationId"))).findFirst().orElse(Map.of("id",previous.get(0).get("obligationId")));
        LocalDate period=req.salaryPeriod().withDayOfMonth(1);
        db.update("INSERT IGNORE INTO salary_obligations(employee_id,salary_period,amount_due) VALUES(?,?,?)",req.employeeId(),period,req.amountDue());
        Map<String,Object> obligation=db.queryForMap("SELECT id,amount_due AS amountDue,amount_paid AS amountPaid FROM salary_obligations WHERE employee_id=? AND salary_period=? FOR UPDATE",req.employeeId(),period);
        long id=longValue(obligation.get("id"));
        if(decimal(obligation.get("amountDue")).compareTo(req.amountDue())!=0)throw new IllegalArgumentException("A salary obligation already exists for this employee and period with a different amount");
        BigDecimal remaining=decimal(obligation.get("amountDue")).subtract(decimal(obligation.get("amountPaid")));
        if(req.amountPaid().compareTo(remaining)>0)throw new IllegalArgumentException("Payment exceeds the remaining salary obligation");
        if(req.amountPaid().signum()>0) {
            if(req.paymentDate()==null)throw new IllegalArgumentException("Payment date is required when recording a salary payment");
            long paymentId=insert("INSERT INTO salary_payments(obligation_id,amount,payment_date,notes,recorded_by,idempotency_key) VALUES(?,?,?,?,?,?)",id,req.amountPaid(),req.paymentDate(),blankNull(req.notes()),actor.get("id"),req.idempotencyKey());
            db.update("UPDATE salary_obligations SET amount_paid=amount_paid+? WHERE id=?",req.amountPaid(),id);
            db.update("INSERT IGNORE INTO expense_categories(name) VALUES('Employee salaries')");
            long expenseId=insert("INSERT INTO expenses(category_id,amount,expense_date,description,vendor,created_by,idempotency_key) SELECT id,?,?,?,e.full_name,?,? FROM expense_categories c JOIN employees e ON e.id=? WHERE c.name='Employee salaries' LIMIT 1",req.amountPaid(),req.paymentDate(),"Salary payment for "+period,actor.get("id"),"salary-payment-"+paymentId,req.employeeId());
            db.update("INSERT INTO ledger_entries(entry_type,direction,amount,source_type,source_id,occurred_on,recorded_by,description) VALUES('EXPENSE','OUT',?,'expense',?,?,?,?)",req.amountPaid(),expenseId,req.paymentDate(),actor.get("id"),"Employee salary payment");
            audit(actor,"SALARY_PAYMENT_RECORDED","salary_payment",paymentId,null,Map.of("amount",req.amountPaid(),"period",period.toString()));
        }
        audit(actor,"SALARY_OBLIGATION_RECORDED","salary_obligation",id,null,Map.of("amountDue",req.amountDue()));
        return salaries(auth,0,100).stream().filter(x->longValue(x.get("id"))==id).findFirst().orElse(Map.of("id",id));
    }

    @Transactional
    public Map<String,Object> issueAdvance(Requests.Advance req,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER");
        List<Map<String,Object>> prior=db.queryForList("SELECT id FROM employee_advances WHERE recorded_by=? AND idempotency_key=?",actor.get("id"),req.idempotencyKey());
        if(!prior.isEmpty())return db.queryForMap("SELECT a.id,a.employee_id AS employeeId,e.full_name AS employeeName,a.amount,a.repaid_amount AS repaidAmount,(a.amount-a.repaid_amount) AS outstanding,a.issued_date AS issuedDate,a.reason FROM employee_advances a JOIN employees e ON e.id=a.employee_id WHERE a.id=?",prior.get(0).get("id"));
        long id=insert("INSERT INTO employee_advances(employee_id,amount,issued_date,reason,recorded_by,idempotency_key) VALUES(?,?,?,?,?,?)",req.employeeId(),req.amount(),req.issuedDate(),req.reason(),actor.get("id"),req.idempotencyKey());
        db.update("INSERT INTO advance_transactions(advance_id,amount,transaction_date,kind,note,recorded_by,idempotency_key) VALUES(?,?,?,'ISSUED',?,?,?)",id,req.amount(),req.issuedDate(),req.reason(),actor.get("id"),"advance-issued-"+id);
        db.update("INSERT INTO ledger_entries(entry_type,direction,amount,source_type,source_id,occurred_on,recorded_by,description) VALUES('ADVANCE_ISSUED','OUT',?,'advance',?,?,?,?)",req.amount(),id,req.issuedDate(),actor.get("id"),req.reason());
        audit(actor,"ADVANCE_ISSUED","employee_advance",id,req.reason(),Map.of("amount",req.amount()));
        return db.queryForMap("SELECT a.id,a.employee_id AS employeeId,e.full_name AS employeeName,a.amount,a.repaid_amount AS repaidAmount,(a.amount-a.repaid_amount) AS outstanding,a.issued_date AS issuedDate,a.reason FROM employee_advances a JOIN employees e ON e.id=a.employee_id WHERE a.id=?",id);
    }

    public List<Map<String,Object>> advances(Authentication auth) {
        requireAny(auth,"OWNER","MANAGER"); return db.queryForList("SELECT a.id,a.employee_id AS employeeId,e.full_name AS employeeName,a.amount,a.repaid_amount AS repaidAmount,(a.amount-a.repaid_amount) AS outstanding,a.issued_date AS issuedDate,a.reason FROM employee_advances a JOIN employees e ON e.id=a.employee_id ORDER BY a.issued_date DESC");
    }

    @Transactional
    public void repayAdvance(long id,Requests.AdvanceRepayment req,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER"); Map<String,Object> a=db.queryForMap("SELECT * FROM employee_advances WHERE id=? FOR UPDATE",id);
        if(count("SELECT COUNT(*) FROM advance_transactions WHERE recorded_by=? AND idempotency_key=?",actor.get("id"),req.idempotencyKey())>0)return;
        BigDecimal balance=decimal(a.get("amount")).subtract(decimal(a.get("repaid_amount")));
        if(req.amount().compareTo(balance)>0) throw new IllegalArgumentException("Repayment is greater than the outstanding advance");
        db.update("UPDATE employee_advances SET repaid_amount=repaid_amount+? WHERE id=?",req.amount(),id);
        long tx=insert("INSERT INTO advance_transactions(advance_id,amount,transaction_date,kind,note,recorded_by,idempotency_key) VALUES(?,?,?,'REPAYMENT',?,?,?)",id,req.amount(),req.transactionDate(),blankNull(req.note()),actor.get("id"),req.idempotencyKey());
        db.update("INSERT INTO ledger_entries(entry_type,direction,amount,source_type,source_id,occurred_on,recorded_by,description) VALUES('ADVANCE_REPAYMENT','IN',?,'advance_repayment',?,?,?,?)",req.amount(),tx,req.transactionDate(),actor.get("id"),"Employee advance repayment");
        audit(actor,"ADVANCE_REPAYMENT","employee_advance",id,req.note(),Map.of("amount",req.amount()));
    }

    @Transactional
    public Map<String,Object> withdrawal(Requests.Withdrawal req,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER");
        List<Map<String,Object>> prior=db.queryForList("SELECT id FROM owner_withdrawals WHERE recorded_by=? AND idempotency_key=?",actor.get("id"),req.idempotencyKey());
        if(!prior.isEmpty())return db.queryForMap("SELECT id,amount,withdrawal_date AS date,reason,method,note FROM owner_withdrawals WHERE id=?",prior.get(0).get("id"));
        long id=insert("INSERT INTO owner_withdrawals(amount,withdrawal_date,reason,method,note,recorded_by,idempotency_key) VALUES(?,?,?,?,?,?,?)",req.amount(),req.date(),req.reason(),blankNull(req.method()),blankNull(req.note()),actor.get("id"),req.idempotencyKey());
        db.update("INSERT INTO ledger_entries(entry_type,direction,amount,source_type,source_id,occurred_on,recorded_by,description) VALUES('OWNER_WITHDRAWAL','OUT',?,'withdrawal',?,?,?,?)",req.amount(),id,req.date(),actor.get("id"),req.reason());
        audit(actor,"OWNER_WITHDRAWAL_RECORDED","withdrawal",id,req.reason(),Map.of("amount",req.amount()));
        return db.queryForMap("SELECT id,amount,withdrawal_date AS date,reason,method,note FROM owner_withdrawals WHERE id=?",id);
    }

    public List<Map<String,Object>> withdrawals(Authentication auth) { requireAny(auth,"OWNER"); return db.queryForList("SELECT id,amount,withdrawal_date AS date,reason,method,note FROM owner_withdrawals ORDER BY withdrawal_date DESC LIMIT 500"); }

    @Transactional
    public Map<String,Object> createBillSchedule(Requests.BillSchedule req,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER");
        if(!Set.of("MONTHLY","QUARTERLY","YEARLY").contains(req.frequency())) throw new IllegalArgumentException("Unsupported bill frequency");
        List<Map<String,Object>> prior=db.queryForList("SELECT id FROM recurring_bills WHERE idempotency_key=?",req.idempotencyKey());
        if(!prior.isEmpty())return db.queryForMap("SELECT id,category,vendor,expected_amount AS expectedAmount,frequency,next_due_date AS nextDueDate,active FROM recurring_bills WHERE id=?",prior.get(0).get("id"));
        long id=insert("INSERT INTO recurring_bills(category,vendor,expected_amount,frequency,next_due_date,notes,idempotency_key) VALUES(?,?,?,?,?,?,?)",req.category(),blankNull(req.vendor()),req.expectedAmount(),req.frequency(),req.nextDueDate(),blankNull(req.notes()),req.idempotencyKey());
        audit(actor,"BILL_SCHEDULE_CREATED","recurring_bill",id,null,Map.of());
        db.update("INSERT INTO bill_payments(recurring_bill_id,category,amount,due_date,status,notes,recorded_by) VALUES(?,?,?,?,'UPCOMING',?,?)",id,req.category(),req.expectedAmount(),req.nextDueDate(),blankNull(req.notes()),actor.get("id"));
        return db.queryForMap("SELECT id,category,vendor,expected_amount AS expectedAmount,frequency,next_due_date AS nextDueDate,active FROM recurring_bills WHERE id=?",id);
    }

    public List<Map<String,Object>> bills(Authentication auth,int page,int size) {
        requireAny(auth,"OWNER","MANAGER"); return db.queryForList("SELECT id,recurring_bill_id AS recurringBillId,category,amount,due_date AS dueDate,payment_date AS paymentDate,status,bill_file_id AS billFileId,notes FROM bill_payments ORDER BY due_date DESC LIMIT ? OFFSET ?",pageSize(size),pageIndex(page)*pageSize(size));
    }

    @Transactional
    public Map<String,Object> payBill(long id,Requests.BillPayment req,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER");
        if(req.billFileId()!=null) ensureFileUploadedBy(req.billFileId(),actor.get("id"));
        Map<String,Object> prior;
        try{prior=db.queryForMap("SELECT status FROM bill_payments WHERE id=? FOR UPDATE",id);}catch(EmptyResultDataAccessException e){throw new IllegalArgumentException("Bill not found");}
        if("PAID".equals(prior.get("status"))) throw new IllegalArgumentException("This bill has already been marked paid");
        db.update("UPDATE bill_payments SET category=?,amount=?,due_date=?,payment_date=?,status=?,bill_file_id=?,notes=? WHERE id=?",req.category(),req.amount(),req.dueDate(),req.paymentDate(),req.paymentDate()==null?"DUE":"PAID",req.billFileId(),blankNull(req.notes()),id);
        if(req.paymentDate()!=null) {
            db.update("INSERT IGNORE INTO expense_categories(name) VALUES(?)",req.category());
            long exp=insert("INSERT INTO expenses(category_id,amount,expense_date,description,vendor,bill_file_id,created_by,idempotency_key) SELECT id,?,?,CONCAT('Recurring bill: ',?),?,?,?,? FROM expense_categories WHERE name=? LIMIT 1",req.amount(),req.paymentDate(),req.category(),req.vendor(),req.billFileId(),actor.get("id"),"bill-payment-"+id,req.category());
            if(exp>0) db.update("INSERT INTO ledger_entries(entry_type,direction,amount,source_type,source_id,occurred_on,recorded_by,description) VALUES('EXPENSE','OUT',?,'expense',?,?,?,?)",req.amount(),exp,req.paymentDate(),actor.get("id"),"Recurring bill paid");
        }
        audit(actor,"BILL_UPDATED","bill_payment",id,null,Map.of("status",req.paymentDate()==null?"DUE":"PAID"));
        return db.queryForMap("SELECT id,category,amount,due_date AS dueDate,payment_date AS paymentDate,status FROM bill_payments WHERE id=?",id);
    }

    public List<Map<String,Object>> notifications(Authentication auth,int page,int size) {
        Map<String,Object> u=user(auth); return db.queryForList("SELECT id,kind,title,message,related_type AS relatedType,related_id AS relatedId,created_at AS createdAt,read_at AS readAt FROM notifications WHERE user_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?",u.get("id"),pageSize(size),pageIndex(page)*pageSize(size));
    }
    public void readNotification(long id,Authentication auth) { Map<String,Object> u=user(auth); int n=db.update("UPDATE notifications SET read_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",id,u.get("id")); if(n==0)throw new IllegalArgumentException("Notification not found"); }

    @Transactional
    public Map<String,Object> createAccount(Requests.Account req,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER");
        if(!Set.of("MANAGER","STUDENT").contains(req.role())) throw new IllegalArgumentException("Only Manager or Student accounts can be added here");
        long id=insert("INSERT INTO users(email,password_hash,display_name,role) VALUES(?,?,?,?)",req.email().toLowerCase().trim(),passwords.encode(req.initialPassword()),req.displayName().trim(),req.role());
        audit(actor,"USER_CREATED","user",id,null,Map.of("role",req.role()));
        return Map.of("id",id,"email",req.email().toLowerCase().trim(),"displayName",req.displayName(),"role",req.role());
    }

    public List<Map<String,Object>> accounts(Authentication auth) { requireAny(auth,"OWNER"); return db.queryForList("SELECT id,email,display_name AS displayName,role,enabled,created_at AS createdAt FROM users ORDER BY role,display_name"); }
    @Transactional
    public void setAccountEnabled(long id,boolean enabled,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER");
        Map<String,Object> target=db.queryForMap("SELECT id,role FROM users WHERE id=?",id);
        if(longValue(actor.get("id"))==id)throw new IllegalArgumentException("You cannot deactivate your own account");
        if("OWNER".equals(target.get("role")))throw new IllegalArgumentException("Owner accounts require protected administrator review");
        db.update("UPDATE users SET enabled=? WHERE id=?",enabled,id);
        audit(actor,enabled?"USER_REACTIVATED":"USER_DEACTIVATED","user",id,null,Map.of("enabled",enabled));
    }
    public List<Map<String,Object>> auditLogs(Authentication auth,int page,int size) { requireAny(auth,"OWNER"); return db.queryForList("SELECT a.id,a.action,a.entity_type AS entityType,a.entity_id AS entityId,a.reason,a.details,a.occurred_at AS occurredAt,u.display_name AS actor FROM audit_logs a JOIN users u ON u.id=a.actor_user_id ORDER BY a.occurred_at DESC LIMIT ? OFFSET ?",pageSize(size),pageIndex(page)*pageSize(size)); }
    public List<Map<String,Object>> ledger(Authentication auth,int page,int size,String from,String to) { requireAny(auth,"OWNER","MANAGER");LocalDate f=parseDate(from),t=parseDate(to);return db.queryForList("SELECT l.id,l.entry_type AS entryType,l.direction,l.amount,l.occurred_on AS occurredOn,l.source_type AS sourceType,l.source_id AS sourceId,l.description,l.reversed_at AS reversedAt,l.reversal_reason AS reversalReason,u.display_name AS recordedBy FROM ledger_entries l JOIN users u ON u.id=l.recorded_by WHERE (? IS NULL OR l.occurred_on>=?) AND (? IS NULL OR l.occurred_on<=?) ORDER BY l.occurred_on DESC,l.id DESC LIMIT ? OFFSET ?",f,f,t,t,pageSize(size),pageIndex(page)*pageSize(size)); }
    public List<Map<String,Object>> reports(Authentication auth) { requireAny(auth,"OWNER"); return db.queryForList("SELECT id,reporting_month AS reportingMonth,generated_at AS generatedAt,generation_kind AS generationKind,delivery_status AS deliveryStatus,delivery_error AS deliveryError,delivered_at AS deliveredAt FROM monthly_reports ORDER BY generated_at DESC LIMIT 60"); }

    @Transactional
    public long saveUpload(String name,String type,byte[] bytes,java.nio.file.Path storage,Authentication auth) {
        Map<String,Object> actor=user(auth);
        String normalized=Objects.requireNonNullElse(type,"").toLowerCase(Locale.ROOT);
        if(bytes.length==0 || bytes.length>8*1024*1024) throw new IllegalArgumentException("File must be between 1 byte and 8 MB");
        if(!isSupported(bytes,normalized)) throw new IllegalArgumentException("Only valid PNG, JPEG, WebP, or PDF files are supported");
        String ext=switch(normalized){case "image/png"->"png";case "image/jpeg"->"jpg";case "image/webp"->"webp";case "application/pdf"->"pdf";default->throw new IllegalArgumentException("Unsupported file type");};
        String key=UUID.randomUUID()+"."+ext;
        try { java.nio.file.Files.createDirectories(storage); java.nio.file.Files.write(storage.resolve(key),bytes,java.nio.file.StandardOpenOption.CREATE_NEW); }
        catch(java.io.IOException e){throw new IllegalStateException("Unable to store the uploaded file");}
        try {long id=insert("INSERT INTO files(storage_key,original_name,content_type,byte_size,uploaded_by) VALUES(?,?,?,?,?)",key,safeName(name),normalized,bytes.length,actor.get("id")); audit(actor,"FILE_UPLOADED","file",id,null,Map.of("contentType",normalized,"byteSize",bytes.length)); return id;}
        catch(RuntimeException ex){try{java.nio.file.Files.deleteIfExists(storage.resolve(key));}catch(Exception ignored){} throw ex;}
    }

    public Map<String,Object> file(long id,Authentication auth) {
        Map<String,Object> f;
        try{f=db.queryForMap("SELECT id,storage_key AS storageKey,original_name AS originalName,content_type AS contentType,uploaded_by AS uploadedBy FROM files WHERE id=?",id);}catch(EmptyResultDataAccessException e){throw new IllegalArgumentException("File not found");}
        Map<String,Object> u=user(auth); String role=(String)u.get("role");
        if("STUDENT".equals(role)) {
            Long sid=longValue(u.get("student_id"));
            int allowed=count("SELECT COUNT(*) FROM payment_submissions p WHERE p.student_id=? AND p.proof_file_id=?",sid,id);
            allowed+=count("SELECT COUNT(*) FROM students s WHERE s.id=? AND s.photo_file_id=?",sid,id);
            if(allowed==0) throw new AccessDeniedException("Forbidden");
        }
        return f;
    }

    private boolean isSupported(byte[] b,String type) {
        if(type.equals("image/png")) return b.length>8 && (b[0]&255)==137 && b[1]==80 && b[2]==78 && b[3]==71;
        if(type.equals("image/jpeg")) return b.length>3 && (b[0]&255)==255 && (b[1]&255)==216 && (b[2]&255)==255;
        if(type.equals("image/webp")) return b.length>12 && new String(b,0,4).equals("RIFF") && new String(b,8,4).equals("WEBP");
        if(type.equals("application/pdf")) return b.length>5 && new String(b,0,5).equals("%PDF-");
        return false;
    }

    public Map<String,Object> studentById(long id) {
        try{return db.queryForMap("SELECT s.id,s.student_code AS studentCode,s.full_name AS fullName,s.contact,s.emergency_contact AS emergencyContact,s.address,s.registration_date AS registrationDate,s.check_in_date AS checkInDate,s.check_out_date AS checkOutDate,s.monthly_fee AS monthlyFee,s.due_day AS dueDay,s.status,s.notes,s.photo_file_id AS photoFileId,r.room_number AS room,a.bed_number AS bedNumber FROM students s LEFT JOIN student_assignments a ON a.student_id=s.id AND a.ended_at IS NULL LEFT JOIN rooms r ON r.id=a.room_id WHERE s.id=?",id);}catch(EmptyResultDataAccessException e){throw new IllegalArgumentException("Student not found");}
    }

    public Map<String,Object> getStudent(long id,Authentication auth) {
        Map<String,Object> u=user(auth);
        if("STUDENT".equals(u.get("role")) && longValue(u.get("student_id"))!=id) throw new AccessDeniedException("Forbidden");
        requireAny(auth,"OWNER","MANAGER","STUDENT");
        return studentById(id);
    }

    @Transactional
    public void updateStudentPhoto(long studentId,Long fileId,Authentication auth) {
        Map<String,Object> actor=requireAny(auth,"OWNER","MANAGER"); ensureFileExists(fileId);
        db.update("UPDATE students SET photo_file_id=? WHERE id=?",fileId,studentId);
        audit(actor,"STUDENT_PHOTO_UPDATED","student",studentId,null,Map.of("fileId",fileId));
    }

    private Map<String,Object> paymentById(long id) { return db.queryForMap("SELECT p.id,p.invoice_id AS invoiceId,p.student_id AS studentId,s.student_code AS studentCode,s.full_name AS studentName,i.billing_month AS billingMonth,p.amount,p.payment_date AS paymentDate,p.method,p.reference,p.status,p.rejection_reason AS rejectionReason,r.receipt_number AS receiptNumber FROM payment_submissions p JOIN students s ON s.id=p.student_id JOIN fee_invoices i ON i.id=p.invoice_id LEFT JOIN receipts r ON r.payment_submission_id=p.id WHERE p.id=?",id); }
    public Map<String,Object> user(Authentication auth) { if(auth==null||!auth.isAuthenticated())throw new AccessDeniedException("Authentication required"); try{return db.queryForMap("SELECT id,email,display_name,role,student_id FROM users WHERE email=? AND enabled=TRUE",auth.getName());}catch(EmptyResultDataAccessException e){throw new AccessDeniedException("Authentication required");} }
    private Map<String,Object> require(Authentication a,String role) { Map<String,Object> u=user(a); if(!role.equals(u.get("role"))) throw new AccessDeniedException("Forbidden"); return u; }
    private Map<String,Object> requireAny(Authentication a,String... roles) { Map<String,Object> u=user(a); if(Arrays.stream(roles).noneMatch(r->r.equals(u.get("role"))))throw new AccessDeniedException("Forbidden"); return u; }
    private void ensureFileExists(Long id){ if(count("SELECT COUNT(*) FROM files WHERE id=?",id)==0)throw new IllegalArgumentException("File not found"); }
    private void ensureFileUploadedBy(Long id,Object userId){if(count("SELECT COUNT(*) FROM files WHERE id=? AND uploaded_by=?",id,userId)==0)throw new IllegalArgumentException("File not found or not uploaded by this account");}
    private void audit(Map<String,Object> actor,String action,String entity,long id,String reason,Map<String,?> details){ db.update("INSERT INTO audit_logs(actor_user_id,action,entity_type,entity_id,reason,details) VALUES(?,?,?,?,?,?)",actor.get("id"),action,entity,id,blankNull(reason),json(details)); }
    private String json(Map<String,?> map){ try{return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);}catch(Exception e){return "{}";} }
    private int count(String sql,Object... args){Integer n=db.queryForObject(sql,Integer.class,args);return n==null?0:n;}
    private BigDecimal money(String sql,Object...args){BigDecimal n=db.queryForObject(sql,BigDecimal.class,args);return n==null?BigDecimal.ZERO:n;}
    private long insert(String sql,Object... args){KeyHolder key=new GeneratedKeyHolder(); db.update(c->{PreparedStatement s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS); for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},key); return Objects.requireNonNull(key.getKey()).longValue();}
    private static String blankNull(String s){return s==null||s.isBlank()?null:s.trim();}
    private static int pageSize(int size){return Math.max(1,Math.min(size,100));}
    private static int pageIndex(int page){return Math.max(0,page);}
    private static LocalDate parseDate(String v){return v==null||v.isBlank()?null:LocalDate.parse(v);}
    private static long longValue(Object x){return x==null?0:((Number)x).longValue();}
    private static BigDecimal decimal(Object x){if(x instanceof BigDecimal d)return d;if(x instanceof Number n)return new BigDecimal(n.toString());return new BigDecimal(String.valueOf(x));}
    private static String safeName(String name){String s=Objects.requireNonNullElse(name,"upload").replaceAll("[^A-Za-z0-9._ -]","_");return s.length()>150?s.substring(s.length()-150):s;}
}
