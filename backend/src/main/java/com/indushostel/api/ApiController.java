package com.indushostel.api;

import com.indushostel.service.HostelService;
import com.indushostel.service.MonthlyReportService;
import com.indushostel.service.PasswordResetService;
import com.indushostel.service.ReceiptService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final HostelService service;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contexts;
    private final ReceiptService receipts;
    private final MonthlyReportService reports;
    private final PasswordResetService passwordResets;
    private final Path fileStorage;
    private final Map<String,ArrayDeque<Long>> loginAttempts=new ConcurrentHashMap<>();

    public ApiController(HostelService service, AuthenticationManager authenticationManager,
                         SecurityContextRepository contexts, ReceiptService receipts, MonthlyReportService reports,PasswordResetService passwordResets,
                         @Value("${app.file-storage}") String fileStorage) {
        this.service=service; this.authenticationManager=authenticationManager; this.contexts=contexts;
        this.receipts=receipts; this.reports=reports;this.passwordResets=passwordResets; this.fileStorage=Path.of(fileStorage).toAbsolutePath().normalize();
    }

    @GetMapping("/auth/csrf") public Map<String,String> csrf(CsrfToken token) { return Map.of("token",token.getToken()); }
    @PostMapping("/auth/login") public Map<String,Object> login(@Valid @RequestBody Requests.Login input,HttpServletRequest request,HttpServletResponse response) {
        String ip=Objects.requireNonNullElse(request.getRemoteAddr(),"unknown"); ArrayDeque<Long> attempts=loginAttempts.computeIfAbsent(ip,k->new ArrayDeque<>());
        synchronized(attempts){long now=System.currentTimeMillis();while(!attempts.isEmpty()&&now-attempts.peekFirst()>600_000)attempts.removeFirst();if(attempts.size()>=8)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"Too many login attempts");}
        try {
            Authentication auth=authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(input.email().toLowerCase(Locale.ROOT).trim(),input.password()));
            synchronized(attempts){attempts.clear();}
            SecurityContext context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);
            return service.current(auth);
        } catch(BadCredentialsException ex) { synchronized(attempts){attempts.addLast(System.currentTimeMillis());} throw new BadCredentialsException("Email or password is incorrect"); }
    }
    @PostMapping("/auth/password-reset/request") public Map<String,String> resetRequest(@Valid @RequestBody Requests.PasswordResetRequest req) { passwordResets.request(req.email());return Map.of("message","If this account can receive password reset email, instructions have been sent."); }
    @PostMapping("/auth/password-reset/confirm") public ResponseEntity<Void> resetConfirm(@Valid @RequestBody Requests.PasswordResetConfirm req) { passwordResets.confirm(req.token(),req.newPassword());return ResponseEntity.noContent().build(); }
    @PostMapping("/auth/logout") public ResponseEntity<Void> logout(Authentication auth,HttpServletRequest request,HttpServletResponse response) { new SecurityContextLogoutHandler().logout(request,response,auth); return ResponseEntity.noContent().build(); }
    @GetMapping("/auth/me") public Map<String,Object> me(Authentication auth) { return service.current(auth); }

    @GetMapping("/branding") public Map<String,Object> branding() { return service.branding(); }
    @GetMapping(value="/branding/logo") public ResponseEntity<byte[]> publicLogo() throws Exception {
        Map<String,Object> f=service.publicLogoFile();if(f==null)return ResponseEntity.notFound().build();Path target=fileStorage.resolve((String)f.get("storageKey")).normalize();if(!target.startsWith(fileStorage)||!Files.exists(target))return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType((String)f.get("contentType"))).header("X-Content-Type-Options","nosniff").body(Files.readAllBytes(target));
    }
    @GetMapping("/settings") public Map<String,Object> settings(Authentication auth) { return service.ownerBranding(auth); }
    @PutMapping("/branding") public Map<String,Object> branding(@Valid @RequestBody Requests.Branding req,Authentication auth) { return service.saveBranding(req,auth); }
    @PostMapping("/branding/logo") public Map<String,Object> logo(@RequestParam("file") MultipartFile file,Authentication auth) throws Exception {
        requireImage(file); long id=upload(file,auth); Map<String,Object> b=service.ownerBranding(auth); service.saveBranding(new Requests.Branding((String)b.get("hostelName"),(String)b.get("ownerEmail"),id),auth); return Map.of("id",id);
    }

    @GetMapping("/dashboard") public Map<String,Object> dashboard(Authentication auth) { return service.dashboard(auth); }
    @GetMapping("/rooms") public List<Map<String,Object>> rooms(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size,Authentication auth) { return service.rooms(page,size,auth); }
    @PostMapping("/rooms") public Map<String,Object> createRoom(@Valid @RequestBody Requests.Room req,Authentication auth) { return service.createRoom(req,auth); }
    @PutMapping("/rooms/{id}") public Map<String,Object> updateRoom(@PathVariable long id,@Valid @RequestBody Requests.Room req,Authentication auth) { return service.updateRoom(id,req,auth); }
    @DeleteMapping("/rooms/{id}") public ResponseEntity<Void> deactivateRoom(@PathVariable long id,Authentication auth) { service.deactivateRoom(id,auth);return ResponseEntity.noContent().build(); }

    @GetMapping("/students") public List<Map<String,Object>> students(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,@RequestParam(required=false) String search,@RequestParam(required=false) String status,Authentication auth) { return service.students(auth,page,size,search,status); }
    @GetMapping("/students/{id}") public Map<String,Object> student(@PathVariable long id,Authentication auth) { return service.getStudent(id,auth); }
    @PostMapping("/students") public Map<String,Object> createStudent(@Valid @RequestBody Requests.Student req,Authentication auth) { return service.createStudent(req,auth); }
    @PutMapping("/students/{id}") public Map<String,Object> updateStudent(@PathVariable long id,@Valid @RequestBody Requests.StudentUpdate req,Authentication auth) { return service.updateStudent(id,req,auth); }
    @PostMapping("/students/{id}/assign") public Map<String,Object> assignStudent(@PathVariable long id,@Valid @RequestBody Requests.Assignment req,Authentication auth) { return service.assignStudent(id,req,auth); }
    @PostMapping("/students/{id}/photo") public Map<String,Object> studentPhoto(@PathVariable long id,@RequestParam("file") MultipartFile file,Authentication auth) throws Exception { requireImage(file); long fileId=upload(file,auth);service.updateStudentPhoto(id,fileId,auth);return Map.of("fileId",fileId); }
    @PostMapping("/students/{id}/checkout") public Map<String,Object> checkout(@PathVariable long id,@RequestParam(required=false) LocalDate date,@RequestParam(required=false) String reason,Authentication auth) { return service.checkout(id,Objects.requireNonNullElse(date,LocalDate.now()),reason,auth); }

    @GetMapping("/invoices") public List<Map<String,Object>> invoices(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,Authentication auth) { return service.invoices(auth,page,size); }
    @PostMapping("/invoices/generate") public Map<String,Object> generateInvoices(@RequestParam(required=false) String month,Authentication auth) { YearMonth period=month==null?YearMonth.now():YearMonth.parse(month);return Map.of("period",period.toString(),"created",service.generateInvoices(period,auth)); }
    @GetMapping("/payments") public List<Map<String,Object>> payments(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,@RequestParam(required=false) String status,Authentication auth) { return service.payments(auth,page,size,status); }
    @PostMapping("/payments") public Map<String,Object> submitPayment(@Valid @RequestBody Requests.Payment req,Authentication auth) { return service.submitPayment(req,auth); }
    @PostMapping("/payments/{id}/approve") public Map<String,Object> approve(@PathVariable long id,Authentication auth) { return service.approvePayment(id,auth); }
    @PostMapping("/payments/{id}/reject") public Map<String,Object> reject(@PathVariable long id,@Valid @RequestBody Requests.Rejection req,Authentication auth) { return service.rejectPayment(id,req,auth); }
    @PostMapping("/payments/{id}/reverse") public Map<String,Object> reverse(@PathVariable long id,@RequestBody Map<String,String> body,Authentication auth) { String reason=Objects.requireNonNullElse(body.get("reason"),"").trim();if(reason.isEmpty())throw new IllegalArgumentException("A reason is required");return service.reversePayment(id,reason,auth); }
    @GetMapping(value="/receipts/{paymentId}.pdf",produces="application/pdf") public ResponseEntity<byte[]> receipt(@PathVariable long paymentId,Authentication auth) {
        byte[] data=service.receiptPdf(paymentId,auth,receipts);return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"receipt-"+paymentId+".pdf\"").body(data);
    }

    @GetMapping("/expenses") public List<Map<String,Object>> expenses(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,@RequestParam(required=false) String from,@RequestParam(required=false) String to,Authentication auth) { return service.expenses(auth,page,size,from,to); }
    @PostMapping("/expenses") public Map<String,Object> expense(@Valid @RequestBody Requests.Expense req,Authentication auth) { return service.createExpense(req,auth); }
    @PostMapping("/expenses/{id}/reverse") public ResponseEntity<Void> reverseExpense(@PathVariable long id,@RequestBody Map<String,String> body,Authentication auth) { String reason=Objects.requireNonNullElse(body.get("reason"),"").trim();if(reason.isEmpty())throw new IllegalArgumentException("A reason is required");service.reverseExpense(id,reason,auth);return ResponseEntity.noContent().build(); }
    @GetMapping("/expense-categories") public List<Map<String,Object>> categories(Authentication auth) { return service.categories(auth); }

    @GetMapping("/employees") public List<Map<String,Object>> employees(Authentication auth) { return service.employees(auth); }
    @PostMapping("/employees") public Map<String,Object> employee(@Valid @RequestBody Requests.Employee req,Authentication auth) { return service.createEmployee(req,auth); }
    @DeleteMapping("/employees/{id}") public ResponseEntity<Void> deactivateEmployee(@PathVariable long id,Authentication auth) { service.deactivateEmployee(id,auth);return ResponseEntity.noContent().build(); }
    @GetMapping("/salaries") public List<Map<String,Object>> salaries(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,Authentication auth) { return service.salaries(auth,page,size); }
    @PostMapping("/salaries") public Map<String,Object> salary(@Valid @RequestBody Requests.SalaryPayment req,Authentication auth) { return service.paySalary(req,auth); }
    @GetMapping("/advances") public List<Map<String,Object>> advances(Authentication auth) { return service.advances(auth); }
    @PostMapping("/advances") public Map<String,Object> advance(@Valid @RequestBody Requests.Advance req,Authentication auth) { return service.issueAdvance(req,auth); }
    @PostMapping("/advances/{id}/repayments") public ResponseEntity<Void> advanceRepayment(@PathVariable long id,@Valid @RequestBody Requests.AdvanceRepayment req,Authentication auth) { service.repayAdvance(id,req,auth);return ResponseEntity.noContent().build(); }
    @GetMapping("/withdrawals") public List<Map<String,Object>> withdrawals(Authentication auth) { return service.withdrawals(auth); }
    @PostMapping("/withdrawals") public Map<String,Object> withdrawal(@Valid @RequestBody Requests.Withdrawal req,Authentication auth) { return service.withdrawal(req,auth); }

    @GetMapping("/bills") public List<Map<String,Object>> bills(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size,Authentication auth) { return service.bills(auth,page,size); }
    @PostMapping("/bills/schedules") public Map<String,Object> billSchedule(@Valid @RequestBody Requests.BillSchedule req,Authentication auth) { return service.createBillSchedule(req,auth); }
    @PostMapping("/bills/{id}/payment") public Map<String,Object> billPayment(@PathVariable long id,@Valid @RequestBody Requests.BillPayment req,Authentication auth) { return service.payBill(id,req,auth); }

    @GetMapping("/notifications") public List<Map<String,Object>> notifications(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,Authentication auth) { return service.notifications(auth,page,size); }
    @PostMapping("/notifications/{id}/read") public ResponseEntity<Void> readNotification(@PathVariable long id,Authentication auth) { service.readNotification(id,auth);return ResponseEntity.noContent().build(); }
    @GetMapping("/users") public List<Map<String,Object>> users(Authentication auth) { return service.accounts(auth); }
    @PostMapping("/users") public Map<String,Object> createUser(@Valid @RequestBody Requests.Account req,Authentication auth) { return service.createAccount(req,auth); }
    @PutMapping("/users/{id}/status") public ResponseEntity<Void> userStatus(@PathVariable long id,@Valid @RequestBody Requests.UserStatus req,Authentication auth) { service.setAccountEnabled(id,req.enabled(),auth);return ResponseEntity.noContent().build(); }
    @GetMapping("/audit-logs") public List<Map<String,Object>> audit(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,Authentication auth) { return service.auditLogs(auth,page,size); }
    @GetMapping("/ledger") public List<Map<String,Object>> ledger(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size,@RequestParam(required=false) String from,@RequestParam(required=false) String to,Authentication auth) { return service.ledger(auth,page,size,from,to); }

    @PostMapping("/files") public Map<String,Object> fileUpload(@RequestParam("file") MultipartFile file,Authentication auth) throws Exception { return Map.of("id",upload(file,auth)); }
    @GetMapping("/files/{id}") public ResponseEntity<byte[]> file(@PathVariable long id,Authentication auth) throws Exception {
        Map<String,Object> f=service.file(id,auth);Path target=fileStorage.resolve((String)f.get("storageKey")).normalize();if(!target.startsWith(fileStorage))throw new IllegalArgumentException("File not found");byte[] bytes=Files.readAllBytes(target);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType((String)f.get("contentType"))).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename((String)f.get("originalName")).build().toString()).header("X-Content-Type-Options","nosniff").body(bytes);
    }

    @GetMapping("/reports") public List<Map<String,Object>> reportList(Authentication auth) { return service.reports(auth); }
    @PostMapping("/reports") public Map<String,Object> generateReport(@RequestParam String month,Authentication auth) { return reports.generate(YearMonth.parse(month),"MANUAL",auth); }
    @GetMapping("/reports/{id}/download") public ResponseEntity<byte[]> downloadReport(@PathVariable long id,Authentication auth) throws Exception { Path p=reports.pathFor(id,auth);return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+p.getFileName()+"\"").body(Files.readAllBytes(p)); }

    private long upload(MultipartFile file,Authentication auth) throws Exception { return service.saveUpload(file.getOriginalFilename(),file.getContentType(),file.getBytes(),fileStorage,auth); }
    private void requireImage(MultipartFile file) { String type=Objects.requireNonNullElse(file.getContentType(),"");if(!Set.of("image/png","image/jpeg","image/webp").contains(type))throw new IllegalArgumentException("Choose a PNG, JPEG, or WebP image"); }
}
